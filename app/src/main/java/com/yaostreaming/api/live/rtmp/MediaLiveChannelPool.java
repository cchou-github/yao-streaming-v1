package com.yaostreaming.api.live.rtmp;

import com.yaostreaming.api.live.IngestMode;
import com.yaostreaming.api.live.LiveChannelPool;
import com.yaostreaming.api.live.Stream;
import com.yaostreaming.api.live.StreamRepository;
import com.yaostreaming.api.live.StreamStatusTransitions;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.medialive.MediaLiveClient;
import software.amazon.awssdk.services.medialive.model.ChannelState;
import software.amazon.awssdk.services.medialive.model.ConflictException;
import software.amazon.awssdk.services.medialive.model.DescribeChannelRequest;
import software.amazon.awssdk.services.medialive.model.InputDestinationRequest;
import software.amazon.awssdk.services.medialive.model.StartChannelRequest;
import software.amazon.awssdk.services.medialive.model.StopChannelRequest;
import software.amazon.awssdk.services.medialive.model.UpdateInputRequest;
import software.amazon.awssdk.services.mediapackagev2.MediaPackageV2Client;
import software.amazon.awssdk.services.mediapackagev2.model.ResetOriginEndpointStateRequest;

/**
 * The fixed-pool {@link LiveChannelPool} implementation: reads the 5 static
 * candidate channels straight from Terraform-sourced config
 * ({@link MediaLiveChannelPoolProperties}) and never calls
 * {@code CreateChannel}/{@code DeleteChannel} - only
 * {@code Start}/{@code Stop}, matching the narrow IAM policy
 * {@code terraform/iam.tf} grants {@code app_irsa}.
 */
@Component
public class MediaLiveChannelPool implements LiveChannelPool {

	private final StreamRepository streamRepository;
	private final StreamStatusTransitions statusTransitions;
	private final MediaLiveClient mediaLiveClient;
	private final MediaPackageV2Client mediaPackageV2Client;
	private final MediaLiveChannelPoolProperties properties;
	private final SecureRandom secureRandom = new SecureRandom();

	public MediaLiveChannelPool(StreamRepository streamRepository, StreamStatusTransitions statusTransitions,
			MediaLiveClient mediaLiveClient, MediaPackageV2Client mediaPackageV2Client,
			MediaLiveChannelPoolProperties properties) {
		int size = properties.poolChannelIds().size();
		if (properties.poolInputIds().size() != size || properties.poolOriginSlugs().size() != size) {
			throw new IllegalStateException(
					"app.live pool-channel-ids/pool-input-ids/pool-origin-slugs must all be the same "
							+ "length (got %d/%d/%d) - check for a Terraform/deploy.sh drift".formatted(
									size, properties.poolInputIds().size(), properties.poolOriginSlugs().size()));
		}
		this.streamRepository = streamRepository;
		this.statusTransitions = statusTransitions;
		this.mediaLiveClient = mediaLiveClient;
		this.mediaPackageV2Client = mediaPackageV2Client;
		this.properties = properties;
	}

	@Override
	public IngestMode supportedMode() {
		return IngestMode.RTMP;
	}

	/**
	 * Iterates the fixed candidate list attempting
	 * {@link StreamStatusTransitions#claimChannel} until one succeeds or all
	 * are exhausted. Each claim attempt runs as its own short transaction
	 * (never one transaction spanning the whole loop), so a loss on one
	 * candidate can't roll back a win already committed on an earlier one.
	 *
	 * <p>Checks each candidate's real MediaLive state via {@code
	 * DescribeChannel} before ever attempting to claim it - skips anything
	 * not {@code IDLE}. {@code claimChannel}'s own {@code NOT EXISTS} check
	 * should already keep a row {@code PENDING} out of contention until its
	 * previous stream reaches {@code ENDED} (which only happens once the
	 * async callback confirms the channel is genuinely stopped - see
	 * {@code LiveStreamingService#endStream}), so this is defense in depth
	 * against that DB/reality picture ever diverging, not compensating for
	 * a known-bad DB state the way it originally had to. Without it, a
	 * divergence could 503 a request outright just because the *first*
	 * candidate in the fixed list happened to be the stale one, even with
	 * other genuinely idle channels sitting unused right behind it -
	 * confirmed live, back when the DB routinely lagged reality. {@link
	 * ConflictException} below stays as a second safety net for the
	 * narrower race between this check and the claim itself.
	 */
	@Override
	public Optional<ReservedChannel> reserve(Long streamId) {
		List<String> channelIds = properties.poolChannelIds();
		List<String> inputIds = properties.poolInputIds();
		List<String> originSlugs = properties.poolOriginSlugs();

		for (int i = 0; i < channelIds.size(); i++) {
			String channelId = channelIds.get(i);
			String originSlug = originSlugs.get(i);

			if (!isIdle(channelId)) {
				continue;
			}

			boolean claimed;
			try {
				claimed = statusTransitions.claimChannel(streamId, channelId, originSlug) == 1;
			} catch (CannotAcquireLockException deadlockLoser) {
				// Confirmed under real contention (see
				// StreamRepositoryConcurrencyTest): InnoDB's deadlock
				// detector can roll back a losing claim outright instead of
				// resolving it to a clean 0-row update. Exactly as valid a
				// "didn't win this slot" outcome as a plain 0 - try the next
				// candidate rather than failing the whole request.
				claimed = false;
			}

			if (claimed) {
				String ingestUrl;
				try {
					// A second, independent reset, on top of the one
					// LiveCallbackController triggers via confirmStopped()
					// once the *previous* claimant's async stop
					// confirmation lands. Defense in depth: that path
					// should now be reliable (see confirmStopped's own
					// javadoc for why it wasn't, and terraform/lambda.tf
					// for the real fix), but this claim already knows
					// isIdle() confirmed the channel truly finished
					// stopping - so the flush is guaranteed complete right
					// here too, cheaply, without depending on the
					// callback having already arrived.
					mediaPackageV2Client.resetOriginEndpointState(ResetOriginEndpointStateRequest.builder()
							.channelGroupName(properties.channelGroupName())
							.channelName(originSlug)
							.originEndpointName(originSlug)
							.build());
					// Rotate the input's destination before starting the
					// channel, not after - so the channel never spends any
					// time reachable at the *previous* claimant's secret.
					ingestUrl = rotateIngestSecret(inputIds.get(i));
					mediaLiveClient.startChannel(
							StartChannelRequest.builder().channelId(bareChannelId(channelId)).build());
				} catch (ConflictException stillStopping) {
					// Confirmed live: MediaLive rejects UpdateInput/
					// StartChannel with a 409 ("Your input is being used by
					// a running channel") if the previous claimant's
					// StopChannel hasn't physically finished yet. With
					// endStream now waiting on the real async confirmation
					// (see LiveStreamingService#endStream) rather than
					// marking ENDED synchronously, claimChannel's own
					// NOT EXISTS check should already keep a row out of
					// PENDING until the channel is genuinely free - this
					// catch is a safety net for the DB/reality gap ever
					// diverging (a delayed callback, a bug, manual DB
					// surgery), not the primary defense it used to be.
					//
					// Deliberately NOT "try the next candidate" here, unlike
					// the CannotAcquireLockException case above: this claim
					// already committed (row moved PENDING -> STARTING), so
					// markFailed's STARTING -> FAILED write leaves the row
					// somewhere claimChannel's own PENDING-only precondition
					// can never match again - a second claimChannel call in
					// this same loop for a different candidate would just
					// fail regardless of whether that candidate is actually
					// free (confirmed live: exactly this bug, on the first
					// version of this fix). Fail this attempt cleanly
					// instead, same as pool exhaustion below - the client
					// retries with a fresh goLive() call/fresh PENDING row.
					statusTransitions.markFailed(streamId);
					return Optional.empty();
				} catch (RuntimeException e) {
					// The DB claim already succeeded but the channel never
					// actually ended up correctly configured and running -
					// don't leave the slot stuck reserved-but-unusable.
					statusTransitions.markFailed(streamId);
					throw e;
				}
				return Optional.of(ReservedChannel.rtmp(channelId, originSlug, ingestUrl));
			}
		}

		// Every candidate was already held by another active stream - an
		// expected, at-capacity outcome, not an exceptional one.
		return Optional.empty();
	}

	/**
	 * Rewrites this slot's MediaLive input destination to a fresh random
	 * secret and returns the resulting ingest URL. Without this, the ingest
	 * URL handed back on every claim would be the slot's own static,
	 * Terraform-created value ({@code pool-N}) - permanently reusable by
	 * anyone who ever held it, including to push into whatever stream
	 * currently holds that slot after them. Confirmed live before relying
	 * on it: {@code UpdateInput} works on an input that's still {@code
	 * ATTACHED} to its channel, no detach needed first (see
	 * {@code terraform/medialive.tf}'s {@code ignore_changes} comment for
	 * why Terraform has to stay out of this field's way once this runs).
	 * 128 bits of {@link SecureRandom}, hex-encoded so it's already a valid
	 * RTMP path segment with no escaping needed.
	 */
	private String rotateIngestSecret(String inputId) {
		byte[] secretBytes = new byte[16];
		secureRandom.nextBytes(secretBytes);
		String secret = HexFormat.of().formatHex(secretBytes);

		return mediaLiveClient.updateInput(UpdateInputRequest.builder()
						.inputId(inputId)
						.destinations(InputDestinationRequest.builder().streamName(secret).build())
						.build())
				.input().destinations().get(0).url();
	}

	private boolean isIdle(String channelArn) {
		return mediaLiveClient.describeChannel(
				DescribeChannelRequest.builder().channelId(bareChannelId(channelArn)).build())
				.state() == ChannelState.IDLE;
	}

	/**
	 * Requests the stop - accept-and-return, same as {@code StartChannel}.
	 * Deliberately does *not* reset the origin endpoint here anymore: an
	 * earlier version of this method did, immediately after this call, and
	 * that was confirmed live to be actively wrong, not just imprecise -
	 * the channel is still physically flushing its last real segments into
	 * MediaPackage for some seconds after {@code StopChannel} returns, so
	 * resetting this early let those trailing segments land in a
	 * *just-cleared* manifest a moment too late, where they then sat
	 * indefinitely, served in full to whoever claimed the slot next.
	 * {@link #confirmStopped} - run once the async callback confirms the
	 * channel is genuinely, physically done - is where that reset actually
	 * belongs now.
	 */
	@Override
	public void release(Long streamId) {
		streamRepository.findById(streamId)
				.map(Stream::getChannelId)
				.filter(channelId -> channelId != null && !channelId.isBlank())
				.ifPresent(channelId -> mediaLiveClient.stopChannel(
						StopChannelRequest.builder().channelId(bareChannelId(channelId)).build()));
	}

	/**
	 * Clears every segment MediaPackage is still holding for this slot -
	 * without this, a viewer who lands on this slot's *next* claimant
	 * within the origin endpoint's manifest window (60s by default,
	 * confirmed against the real, applied resource) could be served this
	 * stream's actual content under someone else's session: MediaPackage
	 * doesn't know or care that the app considers these two MediaLive
	 * sessions on the same physical channel unrelated streams.
	 *
	 * <p>Called only from {@code LiveCallbackController}, once the async
	 * live-state callback has confirmed {@code STOPPED} - by then the
	 * channel's final segment flush (see {@link #release}'s own javadoc)
	 * is guaranteed complete, which is the whole reason this reset actually
	 * works where an earlier, {@code release()}-time version of it didn't.
	 * {@link #reserve}'s own reset-on-claim stays in place too, as a
	 * second, independent safety net.
	 */
	@Override
	public void confirmStopped(Long streamId) {
		streamRepository.findById(streamId)
				.map(Stream::getOriginSlug)
				.filter(originSlug -> originSlug != null && !originSlug.isBlank())
				.ifPresent(originSlug -> mediaPackageV2Client.resetOriginEndpointState(
						ResetOriginEndpointStateRequest.builder()
								.channelGroupName(properties.channelGroupName())
								.channelName(originSlug)
								.originEndpointName(originSlug)
								.build()));
	}

	/**
	 * {@code channelId} everywhere else in this class (claimed into the DB,
	 * matched against MediaLive's own {@code channel_arn} EventBridge field
	 * by the async live-state callback, granted by {@code terraform/iam.tf}'s
	 * IAM policy) is always the full channel ARN - confirmed live against
	 * real AWS to be the correct choice everywhere except here.
	 * {@code Start}/{@code Stop}/{@code DescribeChannel} take {@code channelId}
	 * as a URI path segment (confirmed against the real MediaLive service
	 * model: {@code requestUri: "/prod/channels/{channelId}/start"}, distinct
	 * from the {@code Channel} shape's own separate {@code Arn} field) -
	 * passing the ARN there 403s, since AWS's own authorization check
	 * substitutes it into that same template, producing a resource string
	 * with the ARN embedded twice.
	 */
	private static String bareChannelId(String channelArn) {
		return channelArn.substring(channelArn.lastIndexOf(':') + 1);
	}

}

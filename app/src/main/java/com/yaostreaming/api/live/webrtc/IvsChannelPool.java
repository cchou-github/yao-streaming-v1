package com.yaostreaming.api.live.webrtc;

import com.yaostreaming.api.live.IngestMode;
import com.yaostreaming.api.live.LiveChannelPool;
import com.yaostreaming.api.live.Stream;
import com.yaostreaming.api.live.StreamRepository;
import com.yaostreaming.api.live.StreamStatusTransitions;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ivs.IvsClient;
import software.amazon.awssdk.services.ivs.model.ChannelNotBroadcastingException;
import software.amazon.awssdk.services.ivs.model.CreateStreamKeyRequest;
import software.amazon.awssdk.services.ivs.model.CreateStreamKeyResponse;
import software.amazon.awssdk.services.ivs.model.DeleteStreamKeyRequest;
import software.amazon.awssdk.services.ivs.model.StopStreamRequest;

/**
 * The IVS-backed {@link LiveChannelPool} implementation - the browser/WebRTC
 * go-live path's counterpart to {@code rtmp.MediaLiveChannelPool}. Reads the
 * fixed pool straight from Terraform-sourced config
 * ({@link IvsChannelPoolProperties}), same as the MediaLive side.
 *
 * <p>Structurally simpler than {@code MediaLiveChannelPool} in two ways that
 * both trace back to the same fact - IVS channels are always on, with no
 * {@code StartChannel}/{@code StopChannel}-equivalent lifecycle (verified:
 * IVS bills only for actual streaming minutes, nothing for an idle,
 * provisioned channel): {@link #reserve} never checks a candidate's state
 * before claiming it (there is no "still physically stopping" race to guard
 * against the way {@code isIdle()} does), and what churns per claim is the
 * *stream key*, not the channel's own configuration - rotated via
 * {@code CreateStreamKey}/{@code DeleteStreamKey}, since IVS enforces
 * exactly one stream key per channel at a time and has no reset/rotate
 * action (confirmed against the real API action list).
 *
 * <p>Only became a {@code @Component} once {@link LiveChannelPoolConfig}'s
 * {@code Map<IngestMode, LiveChannelPool>} and
 * {@code LiveStreamingService}'s switch to routing through that map were
 * ready - registering this as a bean any earlier, on its own, broke
 * {@code LiveStreamingService}'s previous single-field autowiring the
 * moment a second {@link LiveChannelPool} bean existed
 * ({@code NoUniqueBeanDefinitionException}, confirmed live by a
 * full-context test failure). The two changes had to land together.
 */
@Component
public class IvsChannelPool implements LiveChannelPool {

	private final StreamRepository streamRepository;
	private final StreamStatusTransitions statusTransitions;
	private final IvsClient ivsClient;
	private final IvsChannelPoolProperties properties;

	public IvsChannelPool(StreamRepository streamRepository, StreamStatusTransitions statusTransitions,
			IvsClient ivsClient, IvsChannelPoolProperties properties) {
		int size = properties.poolChannelArns().size();
		if (properties.poolIngestEndpoints().size() != size || properties.poolOriginSlugs().size() != size) {
			throw new IllegalStateException(
					"app.ivs pool-channel-arns/pool-ingest-endpoints/pool-origin-slugs must all be the same "
							+ "length (got %d/%d/%d) - check for a Terraform/deploy.sh drift".formatted(
									size, properties.poolIngestEndpoints().size(),
									properties.poolOriginSlugs().size()));
		}
		this.streamRepository = streamRepository;
		this.statusTransitions = statusTransitions;
		this.ivsClient = ivsClient;
		this.properties = properties;
	}

	@Override
	public IngestMode supportedMode() {
		return IngestMode.WEBRTC;
	}

	/**
	 * Iterates the fixed candidate list attempting
	 * {@link StreamStatusTransitions#claimChannel} until one succeeds or all
	 * are exhausted - same loop shape as
	 * {@code MediaLiveChannelPool.reserve}, minus its {@code isIdle} check
	 * (nothing to check: an IVS channel is always ready for a new stream
	 * key, never mid-stop the way a MediaLive channel can be).
	 */
	@Override
	public Optional<ReservedChannel> reserve(Long streamId) {
		List<String> channelArns = properties.poolChannelArns();
		List<String> ingestEndpoints = properties.poolIngestEndpoints();
		List<String> originSlugs = properties.poolOriginSlugs();

		for (int i = 0; i < channelArns.size(); i++) {
			String channelArn = channelArns.get(i);
			String originSlug = originSlugs.get(i);

			boolean claimed;
			try {
				claimed = statusTransitions.claimChannel(streamId, channelArn, originSlug) == 1;
			} catch (CannotAcquireLockException deadlockLoser) {
				// Same reasoning as MediaLiveChannelPool.reserve's identical
				// catch - a losing claim under real contention, try the next
				// candidate rather than failing the whole request.
				claimed = false;
			}

			if (claimed) {
				try {
					CreateStreamKeyResponse response = ivsClient.createStreamKey(
							CreateStreamKeyRequest.builder().channelArn(channelArn).build());
					statusTransitions.recordIngestSecretArn(streamId, response.streamKey().arn());
					return Optional.of(ReservedChannel.webrtc(
							channelArn, originSlug, ingestEndpoints.get(i), response.streamKey().value()));
				} catch (RuntimeException e) {
					// The DB claim already succeeded but the key never ended
					// up created - don't leave the slot stuck
					// reserved-but-unusable, same reasoning
					// MediaLiveChannelPool.reserve's own catch documents.
					statusTransitions.markFailed(streamId);
					throw e;
				}
			}
		}

		// Every candidate was already held by another active stream - an
		// expected, at-capacity outcome, not an exceptional one.
		return Optional.empty();
	}

	/**
	 * Disconnects the current WebRTC/RTMPS session on the channel currently
	 * bound to {@code streamId}, if any - mirrors
	 * {@code MediaLiveChannelPool.release}'s immediate {@code StopChannel}
	 * call. Tolerates {@link ChannelNotBroadcastingException}: a stream
	 * still in {@code STARTING} (claimed a channel, but the browser never
	 * actually established a session before "End Stream" was clicked) is a
	 * real, reachable case - {@code StopStream} 404s on a channel with
	 * nothing actively streaming, which is a legitimate no-op here, not a
	 * failure.
	 */
	@Override
	public void release(Long streamId) {
		streamRepository.findById(streamId)
				.map(Stream::getChannelId)
				.filter(channelId -> channelId != null && !channelId.isBlank())
				.ifPresent(channelArn -> {
					try {
						ivsClient.stopStream(StopStreamRequest.builder().channelArn(channelArn).build());
					} catch (ChannelNotBroadcastingException nothingToStop) {
						// Expected - see this method's own javadoc.
					}
				});
	}

	/**
	 * Deletes the claim's stream key, once the async callback has confirmed
	 * the stream is genuinely stopped - not at {@link #release} time, same
	 * timing discipline {@code MediaLiveChannelPool.confirmStopped}'s own
	 * javadoc documents for the equivalent MediaPackage reset (don't make a
	 * claim's secret unusable until the stop is confirmed real). Frees the
	 * channel to be claimed again: IVS enforces exactly one stream key per
	 * channel at a time, so the next claimant's {@code CreateStreamKey}
	 * would otherwise fail outright.
	 */
	@Override
	public void confirmStopped(Long streamId) {
		streamRepository.findById(streamId)
				.map(Stream::getIngestSecretArn)
				.filter(arn -> arn != null && !arn.isBlank())
				.ifPresent(arn -> ivsClient.deleteStreamKey(DeleteStreamKeyRequest.builder().arn(arn).build()));
	}

}

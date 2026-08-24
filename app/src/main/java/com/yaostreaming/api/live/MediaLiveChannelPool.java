package com.yaostreaming.api.live;

import java.util.List;
import java.util.Optional;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.medialive.MediaLiveClient;
import software.amazon.awssdk.services.medialive.model.StartChannelRequest;
import software.amazon.awssdk.services.medialive.model.StopChannelRequest;

/**
 * The fixed-pool {@link LiveChannelPool} implementation: reads the 5 static
 * candidate channels straight from Terraform-sourced config
 * ({@link LiveChannelPoolProperties}) and never calls
 * {@code CreateChannel}/{@code DeleteChannel} - only
 * {@code Start}/{@code Stop}, matching the narrow IAM policy
 * {@code terraform/iam.tf} grants {@code app_irsa}.
 */
@Component
public class MediaLiveChannelPool implements LiveChannelPool {

	private final StreamRepository streamRepository;
	private final StreamStatusTransitions statusTransitions;
	private final MediaLiveClient mediaLiveClient;
	private final LiveChannelPoolProperties properties;

	public MediaLiveChannelPool(StreamRepository streamRepository, StreamStatusTransitions statusTransitions,
			MediaLiveClient mediaLiveClient, LiveChannelPoolProperties properties) {
		int size = properties.poolChannelIds().size();
		if (properties.poolIngestUrls().size() != size || properties.poolOriginSlugs().size() != size) {
			throw new IllegalStateException(
					"app.live pool-channel-ids/pool-ingest-urls/pool-origin-slugs must all be the same "
							+ "length (got %d/%d/%d) - check for a Terraform/deploy.sh drift".formatted(
									size, properties.poolIngestUrls().size(), properties.poolOriginSlugs().size()));
		}
		this.streamRepository = streamRepository;
		this.statusTransitions = statusTransitions;
		this.mediaLiveClient = mediaLiveClient;
		this.properties = properties;
	}

	/**
	 * Iterates the fixed candidate list attempting
	 * {@link StreamRepository#claimChannel} until one succeeds or all are
	 * exhausted. {@code claimChannel} runs through the repository proxy, each
	 * call its own short transaction - same reasoning
	 * {@code VideoStatusTransitions} documents for why it's a separate bean,
	 * applied here to why this loop makes no attempt to wrap itself in one
	 * transaction spanning multiple claim attempts.
	 */
	@Override
	public Optional<ReservedChannel> reserve(Long streamId) {
		List<String> channelIds = properties.poolChannelIds();
		List<String> ingestUrls = properties.poolIngestUrls();
		List<String> originSlugs = properties.poolOriginSlugs();

		for (int i = 0; i < channelIds.size(); i++) {
			String channelId = channelIds.get(i);
			String originSlug = originSlugs.get(i);

			boolean claimed;
			try {
				claimed = streamRepository.claimChannel(streamId, channelId, originSlug) == 1;
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
				try {
					mediaLiveClient.startChannel(StartChannelRequest.builder().channelId(channelId).build());
				} catch (RuntimeException e) {
					// The DB claim already succeeded but MediaLive never
					// actually started - don't leave the slot stuck
					// reserved-but-never-started.
					statusTransitions.markFailed(streamId);
					throw e;
				}
				return Optional.of(new ReservedChannel(channelId, originSlug, ingestUrls.get(i)));
			}
		}

		// Every candidate was already held by another active stream - an
		// expected, at-capacity outcome, not an exceptional one.
		return Optional.empty();
	}

	@Override
	public void release(Long streamId) {
		streamRepository.findById(streamId)
				.map(Stream::getChannelId)
				.filter(channelId -> channelId != null && !channelId.isBlank())
				.ifPresent(channelId ->
						mediaLiveClient.stopChannel(StopChannelRequest.builder().channelId(channelId).build()));
	}

}

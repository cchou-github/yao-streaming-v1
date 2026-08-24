package com.yaostreaming.api.live;

import com.yaostreaming.api.storage.CloudFrontProperties;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the live catalog. Mirrors {@code VideoCatalogService}: maps
 * entities to view models while the transaction is still open, so pages
 * never depend on {@code open-in-view} to resolve the lazily-loaded
 * streamer.
 */
@Service
@Transactional(readOnly = true)
public class LiveCatalogService {

	private final StreamRepository streamRepository;

	private final CloudFrontProperties cloudFrontProperties;

	public LiveCatalogService(StreamRepository streamRepository, CloudFrontProperties cloudFrontProperties) {
		this.streamRepository = streamRepository;
		this.cloudFrontProperties = cloudFrontProperties;
	}

	/** Currently-live streams only, most recently started first - unlike VOD's catalog, nothing else belongs here. */
	public List<LiveStreamSummary> listLive() {
		return streamRepository.findLiveOrderByStartedAtDesc().stream()
				.map(LiveCatalogService::toSummary)
				.toList();
	}

	public Optional<LiveStreamDetail> findDetail(Long id) {
		return streamRepository.findById(id).map(this::toDetail);
	}

	/** Backs the go-live page's "you already have one running" notice. */
	public Optional<LiveStreamDetail> findActiveForUser(Long userId) {
		return streamRepository.findFirstByUser_IdAndStatusInOrderByCreatedAtDesc(userId, StreamStatus.CHANNEL_BOUND)
				.map(this::toDetail);
	}

	private static LiveStreamSummary toSummary(Stream stream) {
		return new LiveStreamSummary(
				stream.getId(),
				stream.getTitle(),
				stream.getUser().getDisplayName(),
				stream.getStartedAt());
	}

	private LiveStreamDetail toDetail(Stream stream) {
		boolean canBuildUrl = stream.getStatus() == StreamStatus.LIVE
				&& stream.getOriginSlug() != null
				&& cloudFrontProperties.enabled();
		String playbackUrl = canBuildUrl ? playbackUrlFor(stream) : null;
		return new LiveStreamDetail(
				stream.getId(),
				stream.getTitle(),
				stream.getDescription(),
				stream.getStatus(),
				stream.getOriginSlug(),
				playbackUrl,
				stream.getUser().getDisplayName(),
				stream.getStartedAt());
	}

	/**
	 * Unlike {@code VideoCatalogService.playbackUrlFor}, no presigned-URL
	 * fallback for when CloudFront is disabled: MediaPackage/MediaLive have no
	 * LocalStack equivalent, so there is no local playback path to fall back
	 * to at all - {@code toDetail} only calls this once it already knows
	 * CloudFront is enabled.
	 */
	private String playbackUrlFor(Stream stream) {
		return "https://" + cloudFrontProperties.domainName() + "/live/" + stream.getOriginSlug() + "/master.m3u8";
	}

}

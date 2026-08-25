package com.yaostreaming.api.live;

import java.time.Instant;

/**
 * A single stream's watch page. {@code playbackUrl} is null until {@code
 * LIVE} (and CloudFront is enabled - see {@code LiveCatalogService}).
 * {@code originSlug} is exposed separately, not just folded into {@code
 * playbackUrl}: {@code LiveWatchController} needs the bare slug to build the
 * CloudFront cookie's playback key, the same way {@code VideoPageController}
 * needs a video's bare id - unlike VOD's {@code videos/{id}/master.m3u8},
 * which is fully deterministic from the id alone, a stream's {@code
 * originSlug} is only known at claim time (any of the 5 pool slots), so it
 * has to come from the loaded row rather than being reconstructed.
 * {@code ingestMode} is exposed for the same reason as {@code originSlug} -
 * {@code LiveWatchController} needs it to pick {@link IngestMode#cloudFrontPathPrefix()}
 * when building the cookie's playback key, not just to display it.
 */
public record LiveStreamDetail(
		Long id,
		String title,
		String description,
		StreamStatus status,
		String originSlug,
		String playbackUrl,
		String streamerName,
		Instant startedAt,
		IngestMode ingestMode) {

	public boolean isPlayable() {
		return status == StreamStatus.LIVE && playbackUrl != null;
	}

	/** Message shown in place of the player while a stream is not watchable. */
	public String statusMessage() {
		return switch (status) {
			case PENDING -> "This stream hasn't started yet.";
			case STARTING -> "Starting up — this page will play it once it goes live.";
			case LIVE -> playbackUrl == null
					? "Marked live but no playback URL was recorded."
					: "";
			case ENDING -> "This stream is wrapping up.";
			case ENDED -> "This stream has ended.";
			case FAILED -> "This stream failed to start.";
		};
	}

}

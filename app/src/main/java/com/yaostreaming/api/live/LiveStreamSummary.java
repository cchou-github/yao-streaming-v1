package com.yaostreaming.api.live;

import java.time.Instant;

/**
 * A row in the live catalog. Built inside the service's transaction so the
 * view never touches a lazily-loaded association. Unlike {@code
 * VideoSummary}, no {@code isPlayable()} - the catalog only ever lists
 * {@code LIVE} streams to begin with, so every row is playable by
 * construction.
 *
 * @param ingestMode purely cosmetic - lets {@code catalog.html} show a
 *                    small OBS/Browser badge. A viewer never needs this to
 *                    actually watch; playback works identically either way.
 */
public record LiveStreamSummary(
		Long id,
		String title,
		String streamerName,
		Instant startedAt,
		IngestMode ingestMode) {
}

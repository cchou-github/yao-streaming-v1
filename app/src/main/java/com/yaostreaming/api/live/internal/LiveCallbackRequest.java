package com.yaostreaming.api.live.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Body the live-state-change Lambda POSTs to {@code /internal/live/callback}.
 * Simpler than {@code TranscodeCallbackRequest}: MediaLive's event already
 * carries the channel's own ARN, so there's no {@code userMetadata}-parsing
 * convention to invent the way the transcode pipeline needed one for the
 * video id.
 */
public record LiveCallbackRequest(
		@NotBlank String channelId,
		@NotNull LiveCallbackStatus status) {
}

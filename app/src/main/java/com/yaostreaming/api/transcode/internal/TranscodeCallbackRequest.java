package com.yaostreaming.api.transcode.internal;

import jakarta.validation.constraints.NotNull;

/**
 * Body the completion Lambda POSTs to {@code /internal/transcode/callback}.
 * {@code outputKey}/{@code durationSeconds} are only meaningful for
 * {@link TranscodeCallbackStatus#READY} — the Lambda simply omits them on a
 * {@code FAILED} job, since MediaConvert never produced output to describe.
 *
 * @param outputKey deterministic {@code videos/{videoId}/master.m3u8}
 *                   convention, not parsed out of the MediaConvert response
 */
public record TranscodeCallbackRequest(
		@NotNull Long videoId,
		@NotNull TranscodeCallbackStatus status,
		String outputKey,
		Integer durationSeconds) {
}

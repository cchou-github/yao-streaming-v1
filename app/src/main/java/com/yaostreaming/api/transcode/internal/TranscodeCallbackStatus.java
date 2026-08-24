package com.yaostreaming.api.transcode.internal;

/**
 * The only two outcomes MediaConvert's "Job State Change" event reports —
 * intentionally narrower than {@link com.yaostreaming.api.video.VideoStatus},
 * which also has states (UPLOADING, PROCESSING) that make no sense as a
 * callback result.
 */
public enum TranscodeCallbackStatus {
	READY, FAILED
}

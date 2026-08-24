package com.yaostreaming.api.live.webrtc;

import com.yaostreaming.api.live.GoLiveResponse;
import com.yaostreaming.api.live.StreamStatus;

/**
 * @param streamId       the created row, used to end the stream later
 * @param ingestEndpoint IVS channel ingest endpoint - the Broadcast SDK for
 *                       Web needs this and {@code streamKey} as two
 *                       independent values, not one combined URL
 * @param streamKey      freshly-issued for this claim, never reused across
 *                       streamers - never logged, never persisted anywhere
 *                       but the response itself ({@code Stream.ingestSecretArn}
 *                       stores the key's ARN, not its value)
 * @param status         always {@code STARTING} - the async callback is
 *                       what confirms {@code LIVE}
 */
public record WebrtcGoLiveResponse(Long streamId, String ingestEndpoint, String streamKey, StreamStatus status)
		implements GoLiveResponse {
}

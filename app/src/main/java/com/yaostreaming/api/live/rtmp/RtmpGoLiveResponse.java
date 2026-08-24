package com.yaostreaming.api.live.rtmp;

import com.yaostreaming.api.live.GoLiveResponse;
import com.yaostreaming.api.live.StreamStatus;

/**
 * @param streamId  the created row, used to end the stream later
 * @param ingestUrl RTMP URL the streamer's encoder (e.g. OBS) pushes to -
 *                  the secret is already baked in
 * @param status    always {@code STARTING} - {@code StartChannel} is
 *                  accept-and-return; the internal callback is what
 *                  confirms {@code LIVE}
 */
public record RtmpGoLiveResponse(Long streamId, String ingestUrl, StreamStatus status) implements GoLiveResponse {
}

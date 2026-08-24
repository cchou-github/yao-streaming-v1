package com.yaostreaming.api.live;

/**
 * @param streamId  the created row, used to end the stream later
 * @param ingestUrl RTMP URL the streamer's encoder (e.g. OBS) pushes to
 * @param status    always {@code STARTING} - {@code StartChannel} is
 *                  accept-and-return; the internal callback (a later PR) is
 *                  what confirms {@code LIVE}
 */
public record GoLiveResponse(Long streamId, String ingestUrl, StreamStatus status) {
}

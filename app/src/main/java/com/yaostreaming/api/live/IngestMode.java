package com.yaostreaming.api.live;

/**
 * Which of the two coexisting go-live mechanisms a {@link Stream} uses -
 * {@code RTMP} for the existing OBS/MediaLive path
 * ({@code com.yaostreaming.api.live.rtmp}), {@code WEBRTC} for the browser
 * camera/AWS IVS path ({@code com.yaostreaming.api.live.webrtc}). With two
 * active {@code LiveChannelPool} implementations, {@code goLive},
 * {@code endStream}, and the async callback routing all need to know which
 * one a given stream is bound to.
 */
public enum IngestMode {
	RTMP, WEBRTC
}

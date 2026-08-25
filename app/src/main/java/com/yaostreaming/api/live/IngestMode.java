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
	RTMP, WEBRTC;

	/**
	 * The CloudFront path prefix a stream's playback URL is built under -
	 * {@code live/pool-N/...} for RTMP (routes to MediaPackage v2),
	 * {@code live-webrtc/pool-N/...} for WEBRTC (routes to IVS). Centralized
	 * here so {@code LiveCatalogService} and {@code LiveWatchController}
	 * can't drift from each other the way two independent hardcoded
	 * {@code "live/"} literals would risk.
	 */
	public String cloudFrontPathPrefix() {
		return switch (this) {
			case RTMP -> "live";
			case WEBRTC -> "live-webrtc";
		};
	}
}

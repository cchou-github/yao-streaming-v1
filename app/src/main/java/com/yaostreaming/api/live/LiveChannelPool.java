package com.yaostreaming.api.live;

import com.yaostreaming.api.live.rtmp.MediaLiveChannelPool;
import java.util.Optional;

/**
 * The entire migration seam between this project's current fixed 5-channel
 * pool ({@link MediaLiveChannelPool}) and a future dynamic, per-user
 * provisioning scheme. {@link Stream}, its status transitions, the
 * EventBridge/callback wiring, CloudFront routing, and the UI never touch
 * pool mechanics directly - only this interface. A dynamic implementation
 * later needs only a second class here, not a rewrite of any of that, the
 * same "the next feature does it this way too" shape as
 * {@code TranscodeCallbackController}'s own javadoc describes for its own
 * package.
 */
public interface LiveChannelPool {

	/**
	 * Which {@link IngestMode} this implementation serves - lets
	 * {@code LiveChannelPoolConfig} (a later PR) assemble a
	 * {@code Map<IngestMode, LiveChannelPool>} from every bean implementing
	 * this interface, so {@code LiveStreamingService} can route a request to
	 * the right pool without a chain of instanceof checks.
	 */
	IngestMode supportedMode();

	/**
	 * Attempts to bind {@code streamId} to a free channel and start it.
	 *
	 * @return the reserved channel, or empty if every channel in the pool is
	 *         currently held by another active stream - an expected,
	 *         at-capacity outcome, not an error
	 */
	Optional<ReservedChannel> reserve(Long streamId);

	/** Stops the channel currently bound to {@code streamId}, if any. */
	void release(Long streamId);

	/**
	 * Called once the async live-state callback confirms a channel has
	 * actually finished stopping - see {@code LiveCallbackController} - not
	 * at {@link #release} time. {@code StopChannel} is accept-and-return, so
	 * at {@code release()} time the channel is typically still physically
	 * running/flushing its last segments; only running MediaPackage cleanup
	 * here, once that's genuinely done, avoids clearing an origin
	 * endpoint's content a moment before those trailing segments still
	 * land in it - confirmed live to be a real, exploitable gap otherwise.
	 */
	void confirmStopped(Long streamId);

	/**
	 * {@code streamKey} is {@code null} for RTMP ({@code ingestUrl} already
	 * has the secret baked in, same shape {@code UpdateInput}'s response
	 * always had) and non-null for WebRTC (IVS hands back the ingest
	 * endpoint and the stream key as two separate values - the Broadcast SDK
	 * for Web needs both independently, not a single combined URL). Use the
	 * named factory methods below rather than the canonical constructor
	 * directly, so each mechanism's call site stays self-documenting about
	 * which shape it's building.
	 */
	record ReservedChannel(String channelId, String originSlug, String ingestUrl, String streamKey) {

		public static ReservedChannel rtmp(String channelId, String originSlug, String ingestUrl) {
			return new ReservedChannel(channelId, originSlug, ingestUrl, null);
		}

		public static ReservedChannel webrtc(String channelId, String originSlug, String ingestEndpoint,
				String streamKey) {
			return new ReservedChannel(channelId, originSlug, ingestEndpoint, streamKey);
		}
	}

}

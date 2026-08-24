/**
 * OBS/RTMP-specific pieces of the "go live" seam: everything that talks to
 * AWS MediaLive/MediaPackage directly. {@link com.yaostreaming.api.live.LiveChannelPool},
 * {@code Stream}, and every mechanism-agnostic class stay in the parent
 * package instead - this split keeps each ingest mechanism's own classes
 * (channel pool, config, go-live response) grouped separately from the
 * shared plumbing both mechanisms route through.
 */
package com.yaostreaming.api.live.rtmp;

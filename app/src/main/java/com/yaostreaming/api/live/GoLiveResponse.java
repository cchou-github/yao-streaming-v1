package com.yaostreaming.api.live;

/**
 * A plain (not {@code sealed}) interface rather than a single record with
 * mechanism-specific nullable fields: each request only ever produces one
 * concrete shape (determined by {@link GoLiveRequest#mode()}), so there's no
 * polymorphic-deserialization problem on the way out, only a clean "one of
 * these" - see {@code rtmp.RtmpGoLiveResponse}/{@code webrtc.WebrtcGoLiveResponse}.
 *
 * <p><b>Not {@code sealed}</b>: tried first, and confirmed by the compiler
 * itself, not assumed - {@code permits} requires every permitted type to
 * live in the same package as the sealed type unless the whole project uses
 * the Java module system (JPMS), which this classpath-based Spring Boot app
 * doesn't. That's incompatible with the two implementations deliberately
 * living in different packages (`.rtmp`/`.webrtc`, mirroring the ingest
 * mechanism split between those two packages) - losing nothing real in
 * practice, since the actual
 * branching in {@code LiveStreamingService} switches on the
 * {@link IngestMode} enum directly, which the compiler already checks
 * exhaustively on its own.
 */
public interface GoLiveResponse {

	Long streamId();

	StreamStatus status();

}

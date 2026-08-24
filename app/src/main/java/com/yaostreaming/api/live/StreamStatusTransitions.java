package com.yaostreaming.api.live;

import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The short, transactional status writes around a stream's lifecycle.
 * Mirrors {@code VideoStatusTransitions} exactly, including why it has to be
 * its own bean rather than a method on a caller: {@code @Transactional} is
 * applied by a proxy around the bean, so a self-invocation
 * (e.g. {@code this.markFailed(...)} inside {@link MediaLiveChannelPool})
 * would silently run with no transaction at all.
 */
@Component
public class StreamStatusTransitions {

	private final StreamRepository streamRepository;

	public StreamStatusTransitions(StreamRepository streamRepository) {
		this.streamRepository = streamRepository;
	}

	/**
	 * {@code STARTING -> LIVE} + {@code startedAt}. Not called by
	 * {@link LiveStreamingService} directly - {@code StartChannel} is
	 * accept-and-return, so this is the transition the async confirmation
	 * path (EventBridge -> Lambda -> internal callback, a later PR) applies
	 * once MediaLive actually reports the channel running.
	 *
	 * @return true if this call is the one that moved the row - false means
	 *         it was already out of {@code STARTING} (a redelivered
	 *         callback, harmlessly ignored)
	 */
	@Transactional
	public boolean markLive(Long streamId) {
		return streamRepository.markLive(streamId, Instant.now()) == 1;
	}

	/**
	 * {@code LIVE -> ENDING}, the "End Stream" button's own write - see
	 * {@link LiveStreamingService#endStream}. Ending a stream that isn't
	 * {@code LIVE} (already ending, already ended, or still just
	 * {@code STARTING}) is deliberately a no-op rather than an error - v1
	 * only supports ending from {@code LIVE}.
	 *
	 * @return true if this call is the one that moved the row
	 */
	@Transactional
	public boolean markEndingRequested(Long streamId) {
		return streamRepository.changeStatus(streamId, StreamStatus.LIVE, StreamStatus.ENDING) == 1;
	}

	/**
	 * {@code ENDING -> ENDED} + {@code endedAt}. Like {@link #markLive}, this
	 * is the async confirmation path's write, not called directly from the
	 * end-stream request itself - {@code StopChannel} is accept-and-return
	 * too.
	 *
	 * @return true if this call is the one that moved the row
	 */
	@Transactional
	public boolean markEnded(Long streamId) {
		return streamRepository.markEnded(streamId, Instant.now()) == 1;
	}

	/**
	 * {@code STARTING -> FAILED}: the only path into {@code STARTING} is
	 * {@link StreamRepository#claimChannel}, so that's the only prior status
	 * this ever needs to move out of.
	 */
	@Transactional
	public void markFailed(Long streamId) {
		streamRepository.changeStatus(streamId, StreamStatus.STARTING, StreamStatus.FAILED);
	}

}

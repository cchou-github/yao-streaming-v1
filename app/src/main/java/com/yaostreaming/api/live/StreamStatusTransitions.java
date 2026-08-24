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
	 * {@code from -> ENDING}, the "End Stream" button's own write - see
	 * {@link LiveStreamingService#endStream}, which tries both {@code LIVE}
	 * and {@code STARTING} as {@code from}. A stream stuck in {@code
	 * STARTING} (never got as far as a {@code RUNNING} confirmation) still
	 * holds a real, running MediaLive channel - confirmed live, the hard
	 * way, when an early version of this button silently no-op'd for
	 * exactly that case and left the channel running uncontrolled. Ending a
	 * stream that's in neither status is still a no-op, not an error -
	 * already ending, already ended, or {@code PENDING} (never claimed a
	 * channel, nothing to release).
	 *
	 * @return true if this call is the one that moved the row
	 */
	@Transactional
	public boolean markEndingRequested(Long streamId, StreamStatus from) {
		return streamRepository.changeStatus(streamId, from, StreamStatus.ENDING) == 1;
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

	/**
	 * Wraps {@link StreamRepository#claimChannel}. {@link MediaLiveChannelPool#reserve}
	 * originally called it directly on the repository - confirmed live against
	 * real AWS to throw {@code jakarta.persistence.TransactionRequiredException}
	 * ("No active transaction for update or delete query"), the same class of
	 * bug every other {@code @Modifying} query on {@link StreamRepository} is
	 * already routed around a {@code @Transactional} bean method to avoid.
	 */
	@Transactional
	public int claimChannel(Long streamId, String channelId, String originSlug) {
		return streamRepository.claimChannel(streamId, channelId, originSlug);
	}

}

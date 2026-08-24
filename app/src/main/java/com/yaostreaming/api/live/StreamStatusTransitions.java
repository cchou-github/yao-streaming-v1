package com.yaostreaming.api.live;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The short, transactional status writes around a stream's lifecycle.
 * Mirrors {@code VideoStatusTransitions} exactly, including why it has to be
 * its own bean rather than a method on a caller: {@code @Transactional} is
 * applied by a proxy around the bean, so a self-invocation
 * (e.g. {@code this.markFailed(...)} inside {@link MediaLiveChannelPool})
 * would silently run with no transaction at all.
 *
 * <p>Only {@link #markFailed} exists so far - the compensating write
 * {@link MediaLiveChannelPool#reserve} needs when {@code StartChannel}
 * throws after a claim already succeeded. The rest of the lifecycle
 * ({@code markLive}, {@code markEndingRequested}, {@code markEnded}) lands
 * with the go-live/end-stream service in a later PR, extending this same
 * class rather than replacing it.
 */
@Component
public class StreamStatusTransitions {

	private final StreamRepository streamRepository;

	public StreamStatusTransitions(StreamRepository streamRepository) {
		this.streamRepository = streamRepository;
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

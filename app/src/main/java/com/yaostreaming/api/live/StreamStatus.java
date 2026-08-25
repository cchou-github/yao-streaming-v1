package com.yaostreaming.api.live;

import java.util.List;

public enum StreamStatus {
	PENDING,
	STARTING,
	LIVE,
	ENDING,
	ENDED,
	FAILED;

	/**
	 * Statuses where a stream currently holds (or is about to hold) a pool
	 * channel slot - the same set {@link StreamRepository#claimChannel}'s own
	 * {@code NOT EXISTS} subquery already hardcodes for "this channel is
	 * taken". Deliberately excludes {@code PENDING}: a stream that failed to
	 * reserve a channel stays {@code PENDING} forever, harmless and inert,
	 * not a reason to ever block a user from trying again.
	 */
	public static final List<StreamStatus> CHANNEL_BOUND = List.of(STARTING, LIVE, ENDING);
}

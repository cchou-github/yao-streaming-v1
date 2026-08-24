package com.yaostreaming.api.live.internal;

/**
 * The only two outcomes {@code LiveStateChangeHandler} (a separate module,
 * {@code lambda/}) ever actually sends. No {@code FAILED} here, unlike
 * {@code TranscodeCallbackStatus}: MediaLive's "Channel State Change" event
 * type has no failure state at all - a genuinely failed/crashed channel
 * would surface via a separate "MediaLive Channel Alert" event, out of
 * scope for this PR.
 *
 * <p>{@link #STOPPED} is the Lambda's translation of the event's own
 * {@code detail.state = "STOPPED"} value - confirmed live (a temporary
 * catch-all EventBridge rule logging every real transition) to be what
 * MediaLive actually emits for a normal stop. This is a genuinely
 * different, unconstrained-string vocabulary from {@code DescribeChannel}'s
 * {@code ChannelState} enum (which has {@code IDLE}, not {@code STOPPED})
 * - an earlier version of this class's own javadoc conflated the two.
 */
public enum LiveCallbackStatus {
	RUNNING, STOPPED
}

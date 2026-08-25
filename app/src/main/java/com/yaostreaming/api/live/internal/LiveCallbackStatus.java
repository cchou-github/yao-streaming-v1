package com.yaostreaming.api.live.internal;

/**
 * The only two outcomes {@code LiveStateChangeHandler} (a separate module,
 * {@code lambda/}) ever actually sends. No {@code FAILED} here, unlike
 * {@code TranscodeCallbackStatus}: MediaLive's "Channel State Change" event
 * type has no failure state at all (verified against MediaLive's real API
 * model - {@code ChannelState}'s enum is {@code CREATING, CREATE_FAILED,
 * IDLE, STARTING, RUNNING, RECOVERING, STOPPING, DELETING, DELETED,
 * UPDATING, UPDATE_FAILED}). A stopped channel reports {@code IDLE}, which
 * the Lambda translates to {@link #STOPPED}; a genuinely failed/crashed
 * channel would surface via a separate "MediaLive Channel Alert" event, out
 * of scope for this PR.
 */
public enum LiveCallbackStatus {
	RUNNING, STOPPED
}

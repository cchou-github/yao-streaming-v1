package com.yaostreaming.api.live;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StreamRepository extends JpaRepository<Stream, Long> {

	/**
	 * Backs the internal live-state callback: given a channel ARN off a
	 * MediaLive EventBridge event, finds whichever stream currently has it
	 * bound. Scoped to the active statuses on purpose - a channel gets
	 * reused across many streams over time (see {@link #claimChannel}'s own
	 * javadoc), so without this filter an old {@code ENDED} row could match
	 * instead of the one actually in flight right now. {@code claimChannel}'s
	 * own guard is what keeps at most one row ever matching this at a time.
	 */
	Optional<Stream> findByChannelIdAndStatusIn(String channelId, List<StreamStatus> statuses);

	/**
	 * Moves a row between statuses only if it is still in {@code from},
	 * returning the number of rows changed. Mirrors
	 * {@code VideoRepository.changeStatus} exactly - used by
	 * {@code StreamStatusTransitions} for every transition that isn't
	 * {@link #claimChannel}'s own compound claim-and-bind.
	 */
	@Modifying
	@Query("update Stream s set s.status = :to where s.id = :id and s.status = :from")
	int changeStatus(@Param("id") Long id, @Param("from") StreamStatus from, @Param("to") StreamStatus to);

	/**
	 * The only path into {@code STARTING} is {@link #claimChannel}, so that's
	 * the only prior status this ever needs to move out of - same reasoning
	 * as {@code VideoRepository.completeIfStillIn} for recording an extra
	 * field in the same atomic update as the status change.
	 */
	@Modifying
	@Query("update Stream s set s.status = 'LIVE', s.startedAt = :startedAt "
			+ "where s.id = :id and s.status = 'STARTING'")
	int markLive(@Param("id") Long id, @Param("startedAt") Instant startedAt);

	/** The only path into {@code ENDING} is {@link #changeStatus}'s LIVE -> ENDING call. */
	@Modifying
	@Query("update Stream s set s.status = 'ENDED', s.endedAt = :endedAt "
			+ "where s.id = :id and s.status = 'ENDING'")
	int markEnded(@Param("id") Long id, @Param("endedAt") Instant endedAt);

	/**
	 * Atomically claims {@code channelId} for {@code streamId}, moving the
	 * stream {@code PENDING -> STARTING}, but only if no other stream
	 * currently holds that channel (a stream in {@code STARTING}, {@code LIVE},
	 * or {@code ENDING}). Returns the number of rows changed: 1 means this
	 * caller won the claim, 0 means either the stream wasn't {@code PENDING}
	 * anymore or the channel was already taken.
	 *
	 * <p>InnoDB's locking read on this {@code UPDATE}'s {@code WHERE} clause
	 * (including the correlated {@code NOT EXISTS} subquery) serializes
	 * concurrent claims for the same channel correctly, without needing a
	 * unique index on {@code channel_id} — a unique index would force nulling
	 * {@code channel_id} on every {@code ENDED}/{@code FAILED} row to free the
	 * slot back up, destroying the audit trail of which slot a stream used
	 * for no benefit.
	 */
	@Modifying
	@Query("update Stream s set s.channelId = :channelId, s.originSlug = :originSlug, s.status = 'STARTING' "
			+ "where s.id = :streamId and s.status = 'PENDING' "
			+ "and not exists (select 1 from Stream s2 where s2.channelId = :channelId "
			+ "and s2.status in ('STARTING', 'LIVE', 'ENDING'))")
	int claimChannel(@Param("streamId") Long streamId, @Param("channelId") String channelId,
			@Param("originSlug") String originSlug);

}

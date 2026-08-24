package com.yaostreaming.api.live;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StreamRepository extends JpaRepository<Stream, Long> {

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

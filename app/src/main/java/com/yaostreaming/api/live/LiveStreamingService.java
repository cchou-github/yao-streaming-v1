package com.yaostreaming.api.live;

import com.yaostreaming.api.live.rtmp.RtmpGoLiveResponse;
import com.yaostreaming.api.live.webrtc.WebrtcGoLiveResponse;
import com.yaostreaming.api.user.User;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Orchestrates go-live/end-stream. Deliberately not {@code @Transactional}
 * as a whole: the row insert and the pool claim are each their own short
 * transaction (the latter via {@link LiveChannelPool#reserve}, see its own
 * javadoc), and the {@code StartChannel}/{@code StopChannel} SDK calls
 * happen with no open transaction around them - same reasoning
 * {@code VideoStatusTransitions} documents for why status writes live in
 * their own bean rather than inline in a service method.
 *
 * <p>Routes through {@link LiveChannelPoolConfig}'s
 * {@code Map<IngestMode, LiveChannelPool>} rather than a single injected
 * field - the map exists specifically because two {@link LiveChannelPool}
 * beans are active now ({@code rtmp.MediaLiveChannelPool},
 * {@code webrtc.IvsChannelPool}), and this class needs to pick the right
 * one per request rather than assuming there's only ever one.
 */
@Service
public class LiveStreamingService {

	private final StreamRepository streamRepository;
	private final Map<IngestMode, LiveChannelPool> channelPoolsByMode;
	private final StreamStatusTransitions statusTransitions;

	public LiveStreamingService(StreamRepository streamRepository,
			Map<IngestMode, LiveChannelPool> channelPoolsByMode, StreamStatusTransitions statusTransitions) {
		this.streamRepository = streamRepository;
		this.channelPoolsByMode = channelPoolsByMode;
		this.statusTransitions = statusTransitions;
	}

	/**
	 * One live stream per user at a time: rejects outright if the caller
	 * already has a channel-bound stream, rather than letting a second
	 * claim through and leaving the caller with two to manage. Checked
	 * before the {@code PENDING} row is even created - deliberately not
	 * folded into a CAS the way {@link StreamRepository#claimChannel} is,
	 * since this is a per-user cardinality rule, not a per-channel one.
	 *
	 * <p>Creates the {@code PENDING} row, then immediately attempts to
	 * reserve a channel for it - two separate steps, two separate
	 * transactions, not one atomic unit (a stream that fails to reserve a
	 * channel still exists as a row, left {@code PENDING}, harmless to leave
	 * behind - and deliberately not counted as "already live" above, or a
	 * pool-exhaustion 503 would permanently lock a user out of ever trying
	 * again).
	 */
	public GoLiveResponse goLive(User user, GoLiveRequest request) {
		streamRepository.findFirstByUser_IdAndStatusInOrderByCreatedAtDesc(user.getId(), StreamStatus.CHANNEL_BOUND)
				.ifPresent(existing -> {
					throw new ResponseStatusException(HttpStatus.CONFLICT,
							"You already have a stream in progress (id " + existing.getId()
									+ ") - end it before starting another");
				});

		Stream stream = new Stream(user, request.title(), request.mode());
		if (request.description() != null && !request.description().isBlank()) {
			stream.setDescription(request.description());
		}
		streamRepository.save(stream);

		LiveChannelPool pool = channelPoolsByMode.get(request.mode());
		LiveChannelPool.ReservedChannel reserved = pool.reserve(stream.getId())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
						"Every channel is currently in use - try again shortly"));

		return switch (request.mode()) {
			case RTMP -> new RtmpGoLiveResponse(stream.getId(), reserved.ingestUrl(), StreamStatus.STARTING);
			case WEBRTC -> new WebrtcGoLiveResponse(
					stream.getId(), reserved.ingestUrl(), reserved.streamKey(), StreamStatus.STARTING);
		};
	}

	/**
	 * Only the stream's own owner may end it - unlike VOD's watch page
	 * (any authenticated user may view any video), this is a mutation with
	 * real consequences for someone else's broadcast, so ownership is
	 * checked explicitly rather than left to any implicit gate.
	 *
	 * <p>Tries {@code LIVE} first, then {@code STARTING} - both are real,
	 * channel-holding states a stream can be sitting in when someone hits
	 * "End stream", and both need the channel released. A no-op (not an
	 * error) when the stream is in neither - see
	 * {@link StreamStatusTransitions#markEndingRequested}'s own javadoc for
	 * why.
	 *
	 * <p>Leaves the row at {@code ENDING} and waits for the async
	 * {@code STOPPED}-state-change callback to mark it {@code ENDED} -
	 * mirrors the {@code STARTING -> LIVE} confirmation path, and is the
	 * design this originally intended. (A prior version of this method
	 * marked {@code ENDED} synchronously right here instead, as a
	 * workaround: the callback wasn't delivering at all, because
	 * {@code terraform/lambda.tf}'s EventBridge rule was filtering on the
	 * wrong {@code detail.state} value - confirmed live, and fixed there.
	 * That workaround is gone now that the actual bug is fixed; keeping it
	 * would have kept marking {@code ENDED} before {@link LiveChannelPool
	 * #confirmStopped}'s MediaPackage reset could safely run, which is
	 * exactly the content-leak bug {@code confirmStopped}'s own javadoc
	 * describes.)
	 */
	public void endStream(User user, Long streamId) {
		Stream stream = streamRepository.findById(streamId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such stream"));
		if (!Objects.equals(stream.getUser().getId(), user.getId())) {
			throw new AccessDeniedException("Stream " + streamId + " does not belong to " + user.getEmail());
		}

		boolean ending = statusTransitions.markEndingRequested(streamId, StreamStatus.LIVE)
				|| statusTransitions.markEndingRequested(streamId, StreamStatus.STARTING);
		if (ending) {
			channelPoolsByMode.get(stream.getIngestMode()).release(streamId);
		}
	}

}

package com.yaostreaming.api.live;

import com.yaostreaming.api.user.User;
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
 */
@Service
public class LiveStreamingService {

	private final StreamRepository streamRepository;
	private final LiveChannelPool liveChannelPool;
	private final StreamStatusTransitions statusTransitions;

	public LiveStreamingService(StreamRepository streamRepository, LiveChannelPool liveChannelPool,
			StreamStatusTransitions statusTransitions) {
		this.streamRepository = streamRepository;
		this.liveChannelPool = liveChannelPool;
		this.statusTransitions = statusTransitions;
	}

	/**
	 * Creates the {@code PENDING} row, then immediately attempts to reserve a
	 * channel for it - two separate steps, two separate transactions, not
	 * one atomic unit (a stream that fails to reserve a channel still exists
	 * as a row, left {@code PENDING}, harmless to leave behind).
	 */
	public GoLiveResponse goLive(User user, GoLiveRequest request) {
		Stream stream = new Stream(user, request.title());
		if (request.description() != null && !request.description().isBlank()) {
			stream.setDescription(request.description());
		}
		streamRepository.save(stream);

		LiveChannelPool.ReservedChannel reserved = liveChannelPool.reserve(stream.getId())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
						"Every channel is currently in use - try again shortly"));

		return new GoLiveResponse(stream.getId(), reserved.ingestUrl(), StreamStatus.STARTING);
	}

	/**
	 * Only the stream's own owner may end it - unlike VOD's watch page
	 * (any authenticated user may view any video), this is a mutation with
	 * real consequences for someone else's broadcast, so ownership is
	 * checked explicitly rather than left to any implicit gate.
	 *
	 * <p>A no-op (not an error) when the stream isn't currently {@code LIVE}
	 * - see {@link StreamStatusTransitions#markEndingRequested}'s own
	 * javadoc for why.
	 */
	public void endStream(User user, Long streamId) {
		Stream stream = streamRepository.findById(streamId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such stream"));
		if (!Objects.equals(stream.getUser().getId(), user.getId())) {
			throw new AccessDeniedException("Stream " + streamId + " does not belong to " + user.getEmail());
		}

		if (statusTransitions.markEndingRequested(streamId)) {
			liveChannelPool.release(streamId);
		}
	}

}

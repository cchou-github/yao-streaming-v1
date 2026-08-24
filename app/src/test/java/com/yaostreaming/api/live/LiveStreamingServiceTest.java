package com.yaostreaming.api.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yaostreaming.api.user.User;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LiveStreamingServiceTest {

	@Mock
	private StreamRepository streamRepository;

	@Mock
	private LiveChannelPool liveChannelPool;

	@Mock
	private StreamStatusTransitions statusTransitions;

	private LiveStreamingService service;

	private final User owner = new User("streamer@example.com", "Streamer", "hash");

	private final AtomicLong nextId = new AtomicLong(42L);

	@BeforeEach
	void setUp() {
		service = new LiveStreamingService(streamRepository, liveChannelPool, statusTransitions);
		doAnswer(invocation -> {
			Stream stream = invocation.getArgument(0);
			ReflectionTestUtils.setField(stream, "id", nextId.getAndIncrement());
			return stream;
		}).when(streamRepository).save(any());
	}

	@Test
	void goLiveSavesAPendingStreamAndReturnsTheReservedIngestUrl() {
		when(liveChannelPool.reserve(42L)).thenReturn(
				Optional.of(LiveChannelPool.ReservedChannel.rtmp("channel-0", "pool-0", "rtmp://ingest-0")));

		GoLiveResponse response = service.goLive(owner, new GoLiveRequest("My Broadcast", "Playing games"));

		assertThat(response).isEqualTo(new GoLiveResponse(42L, "rtmp://ingest-0", StreamStatus.STARTING));
	}

	@Test
	void goLiveThrowsServiceUnavailableWhenThePoolIsExhausted() {
		when(liveChannelPool.reserve(42L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.goLive(owner, new GoLiveRequest("My Broadcast", null)))
				.isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("503");
	}

	@Test
	void goLiveThrowsConflictWhenTheCallerAlreadyHasAChannelBoundStream() {
		ReflectionTestUtils.setField(owner, "id", 1L);
		Stream existing = new Stream(owner, "Already live");
		ReflectionTestUtils.setField(existing, "id", 99L);
		when(streamRepository.findFirstByUser_IdAndStatusInOrderByCreatedAtDesc(1L, StreamStatus.CHANNEL_BOUND))
				.thenReturn(Optional.of(existing));

		assertThatThrownBy(() -> service.goLive(owner, new GoLiveRequest("My Broadcast", null)))
				.isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("409");

		verify(streamRepository, never()).save(any());
		verify(liveChannelPool, never()).reserve(any());
	}

	@Test
	void goLiveLeavesDescriptionUnsetWhenBlank() {
		when(liveChannelPool.reserve(any())).thenReturn(
				Optional.of(LiveChannelPool.ReservedChannel.rtmp("channel-0", "pool-0", "rtmp://ingest-0")));

		service.goLive(owner, new GoLiveRequest("My Broadcast", "   "));

		ArgumentCaptor<Stream> saved = ArgumentCaptor.forClass(Stream.class);
		verify(streamRepository).save(saved.capture());
		assertThat(saved.getValue().getDescription()).isNull();
	}

	/**
	 * Deliberately doesn't verify markEnded here - that's the async
	 * callback's own job now (LiveCallbackController, once the real
	 * STOPPED confirmation arrives), not endStream's. See this method's
	 * own javadoc for why an earlier version marked ENDED synchronously
	 * right here, and why that's gone now that the actual callback bug is
	 * fixed.
	 */
	@Test
	void endStreamRequestsEndingAndReleasesTheChannelWhenLive() {
		Stream stream = new Stream(owner, "My Broadcast");
		ReflectionTestUtils.setField(stream, "id", 1L);
		when(streamRepository.findById(1L)).thenReturn(Optional.of(stream));
		when(statusTransitions.markEndingRequested(1L, StreamStatus.LIVE)).thenReturn(true);

		service.endStream(owner, 1L);

		verify(liveChannelPool).release(1L);
		verify(statusTransitions, never()).markEnded(any());
		verify(statusTransitions, never()).markEndingRequested(1L, StreamStatus.STARTING);
	}

	/**
	 * A stream that never got as far as a RUNNING confirmation still holds a
	 * real MediaLive channel - confirmed live, the hard way, when an earlier
	 * version of endStream only tried LIVE and silently no-op'd for this
	 * exact case, leaving the channel running uncontrolled.
	 */
	@Test
	void endStreamReleasesTheChannelWhenStillStarting() {
		Stream stream = new Stream(owner, "My Broadcast");
		ReflectionTestUtils.setField(stream, "id", 1L);
		when(streamRepository.findById(1L)).thenReturn(Optional.of(stream));
		when(statusTransitions.markEndingRequested(1L, StreamStatus.LIVE)).thenReturn(false);
		when(statusTransitions.markEndingRequested(1L, StreamStatus.STARTING)).thenReturn(true);

		service.endStream(owner, 1L);

		verify(liveChannelPool).release(1L);
	}

	@Test
	void endStreamDoesNotReleaseWhenTheStreamWasNeitherLiveNorStarting() {
		Stream stream = new Stream(owner, "My Broadcast");
		ReflectionTestUtils.setField(stream, "id", 1L);
		when(streamRepository.findById(1L)).thenReturn(Optional.of(stream));
		when(statusTransitions.markEndingRequested(1L, StreamStatus.LIVE)).thenReturn(false);
		when(statusTransitions.markEndingRequested(1L, StreamStatus.STARTING)).thenReturn(false);

		service.endStream(owner, 1L);

		verify(liveChannelPool, never()).release(any());
	}

	@Test
	void endStreamThrowsNotFoundForAMissingStream() {
		when(streamRepository.findById(1L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.endStream(owner, 1L))
				.isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("404");
	}

	@Test
	void endStreamThrowsAccessDeniedWhenTheCallerDoesNotOwnTheStream() {
		Stream stream = new Stream(owner, "My Broadcast");
		ReflectionTestUtils.setField(stream, "id", 1L);
		when(streamRepository.findById(1L)).thenReturn(Optional.of(stream));

		User someoneElse = new User("other@example.com", "Someone Else", "hash");
		ReflectionTestUtils.setField(owner, "id", 1L);
		ReflectionTestUtils.setField(someoneElse, "id", 2L);

		assertThatThrownBy(() -> service.endStream(someoneElse, 1L))
				.isInstanceOf(AccessDeniedException.class);

		verify(statusTransitions, never()).markEndingRequested(any(), any());
		verify(statusTransitions, never()).markEnded(any());
		verify(liveChannelPool, never()).release(any());
	}

}

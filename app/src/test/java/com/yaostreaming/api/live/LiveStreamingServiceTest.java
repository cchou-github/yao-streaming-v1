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
				Optional.of(new LiveChannelPool.ReservedChannel("channel-0", "pool-0", "rtmp://ingest-0")));

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
	void goLiveLeavesDescriptionUnsetWhenBlank() {
		when(liveChannelPool.reserve(any())).thenReturn(
				Optional.of(new LiveChannelPool.ReservedChannel("channel-0", "pool-0", "rtmp://ingest-0")));

		service.goLive(owner, new GoLiveRequest("My Broadcast", "   "));

		ArgumentCaptor<Stream> saved = ArgumentCaptor.forClass(Stream.class);
		verify(streamRepository).save(saved.capture());
		assertThat(saved.getValue().getDescription()).isNull();
	}

	@Test
	void endStreamRequestsEndingAndReleasesTheChannelWhenLive() {
		Stream stream = new Stream(owner, "My Broadcast");
		ReflectionTestUtils.setField(stream, "id", 1L);
		when(streamRepository.findById(1L)).thenReturn(Optional.of(stream));
		when(statusTransitions.markEndingRequested(1L)).thenReturn(true);

		service.endStream(owner, 1L);

		verify(liveChannelPool).release(1L);
	}

	@Test
	void endStreamDoesNotReleaseWhenTheStreamWasNotActuallyLive() {
		Stream stream = new Stream(owner, "My Broadcast");
		ReflectionTestUtils.setField(stream, "id", 1L);
		when(streamRepository.findById(1L)).thenReturn(Optional.of(stream));
		when(statusTransitions.markEndingRequested(1L)).thenReturn(false);

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

		verify(statusTransitions, never()).markEndingRequested(any());
		verify(liveChannelPool, never()).release(any());
	}

}

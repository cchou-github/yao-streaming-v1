package com.yaostreaming.api.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StreamStatusTransitionsTest {

	@Mock
	private StreamRepository streamRepository;

	private StreamStatusTransitions statusTransitions;

	/** A field initializer here would run before Mockito injects @Mock. */
	@BeforeEach
	void setUp() {
		statusTransitions = new StreamStatusTransitions(streamRepository);
	}

	@Test
	void markLiveDelegatesToTheCasUpdateAndReportsSuccess() {
		when(streamRepository.markLive(eq(1L), any(Instant.class))).thenReturn(1);

		assertThat(statusTransitions.markLive(1L)).isTrue();
		verify(streamRepository).markLive(eq(1L), any(Instant.class));
	}

	@Test
	void markLiveReportsFalseWhenTheRowAlreadyLeftStarting() {
		when(streamRepository.markLive(eq(1L), any(Instant.class))).thenReturn(0);

		assertThat(statusTransitions.markLive(1L)).isFalse();
	}

	@Test
	void markEndingRequestedMovesALiveStreamToEnding() {
		when(streamRepository.changeStatus(1L, StreamStatus.LIVE, StreamStatus.ENDING)).thenReturn(1);

		assertThat(statusTransitions.markEndingRequested(1L, StreamStatus.LIVE)).isTrue();
	}

	@Test
	void markEndingRequestedMovesAStartingStreamToEnding() {
		when(streamRepository.changeStatus(1L, StreamStatus.STARTING, StreamStatus.ENDING)).thenReturn(1);

		assertThat(statusTransitions.markEndingRequested(1L, StreamStatus.STARTING)).isTrue();
	}

	@Test
	void markEndingRequestedIsANoOpWhenTheStreamIsNotInTheGivenFromStatus() {
		when(streamRepository.changeStatus(1L, StreamStatus.LIVE, StreamStatus.ENDING)).thenReturn(0);

		assertThat(statusTransitions.markEndingRequested(1L, StreamStatus.LIVE)).isFalse();
	}

	@Test
	void markEndedDelegatesToTheCasUpdateAndReportsSuccess() {
		when(streamRepository.markEnded(eq(1L), any(Instant.class))).thenReturn(1);

		assertThat(statusTransitions.markEnded(1L)).isTrue();
		verify(streamRepository).markEnded(eq(1L), any(Instant.class));
	}

	@Test
	void markFailedMovesAStartingStreamToFailed() {
		statusTransitions.markFailed(1L);

		verify(streamRepository).changeStatus(1L, StreamStatus.STARTING, StreamStatus.FAILED);
	}

	@Test
	void claimChannelDelegatesToTheCasUpdateAndReturnsTheRowCount() {
		when(streamRepository.claimChannel(1L, "channel-0", "pool-0")).thenReturn(1);

		assertThat(statusTransitions.claimChannel(1L, "channel-0", "pool-0")).isEqualTo(1);
	}

}

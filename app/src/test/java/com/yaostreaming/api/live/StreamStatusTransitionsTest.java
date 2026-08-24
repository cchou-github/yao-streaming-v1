package com.yaostreaming.api.live;

import static org.mockito.Mockito.verify;

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
	void markFailedMovesAStartingStreamToFailed() {
		statusTransitions.markFailed(1L);

		verify(streamRepository).changeStatus(1L, StreamStatus.STARTING, StreamStatus.FAILED);
	}

}

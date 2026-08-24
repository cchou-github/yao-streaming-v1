package com.yaostreaming.api.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yaostreaming.api.user.User;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.CannotAcquireLockException;
import software.amazon.awssdk.services.medialive.MediaLiveClient;
import software.amazon.awssdk.services.medialive.model.StartChannelRequest;
import software.amazon.awssdk.services.medialive.model.StopChannelRequest;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MediaLiveChannelPoolTest {

	private static final LiveChannelPoolProperties TWO_SLOT_POOL = new LiveChannelPoolProperties(
			true,
			List.of("channel-0", "channel-1"),
			List.of("rtmp://ingest-0", "rtmp://ingest-1"),
			List.of("pool-0", "pool-1"));

	@Mock
	private StreamRepository streamRepository;

	@Mock
	private StreamStatusTransitions statusTransitions;

	@Mock
	private MediaLiveClient mediaLiveClient;

	private MediaLiveChannelPool pool;

	@BeforeEach
	void setUp() {
		pool = new MediaLiveChannelPool(streamRepository, statusTransitions, mediaLiveClient, TWO_SLOT_POOL);
	}

	@Test
	void constructorRejectsMismatchedListLengths() {
		LiveChannelPoolProperties mismatched = new LiveChannelPoolProperties(
				true, List.of("channel-0", "channel-1"), List.of("rtmp://ingest-0"), List.of("pool-0", "pool-1"));

		assertThatThrownBy(() -> new MediaLiveChannelPool(streamRepository, statusTransitions, mediaLiveClient,
				mismatched))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Terraform/deploy.sh drift");
	}

	@Test
	void reserveClaimsTheFirstFreeChannelAndStartsIt() {
		when(streamRepository.claimChannel(1L, "channel-0", "pool-0")).thenReturn(1);

		Optional<LiveChannelPool.ReservedChannel> reserved = pool.reserve(1L);

		assertThat(reserved).contains(
				new LiveChannelPool.ReservedChannel("channel-0", "pool-0", "rtmp://ingest-0"));
		verify(mediaLiveClient).startChannel(StartChannelRequest.builder().channelId("channel-0").build());
	}

	@Test
	void reserveSkipsAnAlreadyClaimedChannelAndTriesTheNext() {
		when(streamRepository.claimChannel(1L, "channel-0", "pool-0")).thenReturn(0);
		when(streamRepository.claimChannel(1L, "channel-1", "pool-1")).thenReturn(1);

		Optional<LiveChannelPool.ReservedChannel> reserved = pool.reserve(1L);

		assertThat(reserved).contains(
				new LiveChannelPool.ReservedChannel("channel-1", "pool-1", "rtmp://ingest-1"));
		verify(mediaLiveClient, never()).startChannel(StartChannelRequest.builder().channelId("channel-0").build());
	}

	@Test
	void reserveTreatsADeadlockLossTheSameAsAZeroRowClaimAndMovesOn() {
		when(streamRepository.claimChannel(1L, "channel-0", "pool-0"))
				.thenThrow(new CannotAcquireLockException("deadlock victim"));
		when(streamRepository.claimChannel(1L, "channel-1", "pool-1")).thenReturn(1);

		Optional<LiveChannelPool.ReservedChannel> reserved = pool.reserve(1L);

		assertThat(reserved).isPresent();
		assertThat(reserved.orElseThrow().channelId()).isEqualTo("channel-1");
	}

	@Test
	void reserveReturnsEmptyWhenEveryChannelIsAlreadyHeld() {
		when(streamRepository.claimChannel(eq(1L), any(), any())).thenReturn(0);

		Optional<LiveChannelPool.ReservedChannel> reserved = pool.reserve(1L);

		assertThat(reserved).isEmpty();
		verifyNoInteractions(mediaLiveClient);
	}

	@Test
	void reserveCompensatesToFailedAndRethrowsWhenStartChannelFails() {
		when(streamRepository.claimChannel(1L, "channel-0", "pool-0")).thenReturn(1);
		when(mediaLiveClient.startChannel(any(StartChannelRequest.class)))
				.thenThrow(new RuntimeException("MediaLive rejected the request"));

		assertThatThrownBy(() -> pool.reserve(1L)).isInstanceOf(RuntimeException.class);

		verify(statusTransitions).markFailed(1L);
	}

	@Test
	void releaseStopsTheChannelCurrentlyBoundToTheStream() {
		var user = new User("streamer@example.com", "Streamer", "hash");
		var stream = new Stream(user, "Broadcast");
		stream.setChannelId("channel-0");
		when(streamRepository.findById(1L)).thenReturn(Optional.of(stream));

		pool.release(1L);

		verify(mediaLiveClient).stopChannel(StopChannelRequest.builder().channelId("channel-0").build());
	}

	@Test
	void releaseIsANoOpWhenTheStreamNeverClaimedAChannel() {
		var user = new User("streamer@example.com", "Streamer", "hash");
		var stream = new Stream(user, "Broadcast");
		when(streamRepository.findById(1L)).thenReturn(Optional.of(stream));

		pool.release(1L);

		verifyNoInteractions(mediaLiveClient);
	}

	@Test
	void releaseIsANoOpWhenTheStreamNoLongerExists() {
		when(streamRepository.findById(1L)).thenReturn(Optional.empty());

		pool.release(1L);

		verifyNoInteractions(mediaLiveClient);
	}

}

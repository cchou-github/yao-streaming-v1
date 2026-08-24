package com.yaostreaming.api.live.webrtc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yaostreaming.api.live.IngestMode;
import com.yaostreaming.api.live.LiveChannelPool;
import com.yaostreaming.api.live.Stream;
import com.yaostreaming.api.live.StreamRepository;
import com.yaostreaming.api.live.StreamStatusTransitions;
import com.yaostreaming.api.user.User;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.CannotAcquireLockException;
import software.amazon.awssdk.services.ivs.IvsClient;
import software.amazon.awssdk.services.ivs.model.ChannelNotBroadcastingException;
import software.amazon.awssdk.services.ivs.model.CreateStreamKeyRequest;
import software.amazon.awssdk.services.ivs.model.CreateStreamKeyResponse;
import software.amazon.awssdk.services.ivs.model.DeleteStreamKeyRequest;
import software.amazon.awssdk.services.ivs.model.StopStreamRequest;
import software.amazon.awssdk.services.ivs.model.StreamKey;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IvsChannelPoolTest {

	private static final IvsChannelPoolProperties TWO_SLOT_POOL = new IvsChannelPoolProperties(
			true,
			List.of("channel-0", "channel-1"),
			List.of("endpoint-0", "endpoint-1"),
			List.of("pool-0", "pool-1"));

	@Mock
	private StreamRepository streamRepository;

	@Mock
	private StreamStatusTransitions statusTransitions;

	@Mock
	private IvsClient ivsClient;

	private IvsChannelPool pool;

	@BeforeEach
	void setUp() {
		pool = new IvsChannelPool(streamRepository, statusTransitions, ivsClient, TWO_SLOT_POOL);
	}

	private static CreateStreamKeyResponse createStreamKeyResponse(String arn, String value) {
		return CreateStreamKeyResponse.builder()
				.streamKey(StreamKey.builder().arn(arn).value(value).build())
				.build();
	}

	@Test
	void supportedModeReturnsWebrtc() {
		assertThat(pool.supportedMode()).isEqualTo(IngestMode.WEBRTC);
	}

	@Test
	void constructorRejectsMismatchedListLengths() {
		IvsChannelPoolProperties mismatched = new IvsChannelPoolProperties(
				true, List.of("channel-0", "channel-1"), List.of("endpoint-0"), List.of("pool-0", "pool-1"));

		assertThatThrownBy(() -> new IvsChannelPool(streamRepository, statusTransitions, ivsClient, mismatched))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Terraform/deploy.sh drift");
	}

	@Test
	void reserveClaimsTheFirstFreeChannelAndCreatesAStreamKey() {
		when(statusTransitions.claimChannel(1L, "channel-0", "pool-0")).thenReturn(1);
		when(ivsClient.createStreamKey(any(CreateStreamKeyRequest.class)))
				.thenReturn(createStreamKeyResponse("arn:aws:ivs:region:acct:stream-key/abc", "sk_fresh"));

		Optional<LiveChannelPool.ReservedChannel> reserved = pool.reserve(1L);

		assertThat(reserved).contains(
				LiveChannelPool.ReservedChannel.webrtc("channel-0", "pool-0", "endpoint-0", "sk_fresh"));
		verify(statusTransitions).recordIngestSecretArn(1L, "arn:aws:ivs:region:acct:stream-key/abc");

		ArgumentCaptor<CreateStreamKeyRequest> captor = ArgumentCaptor.forClass(CreateStreamKeyRequest.class);
		verify(ivsClient).createStreamKey(captor.capture());
		assertThat(captor.getValue().channelArn()).isEqualTo("channel-0");
	}

	@Test
	void reserveSkipsAnAlreadyClaimedChannelAndTriesTheNext() {
		when(statusTransitions.claimChannel(1L, "channel-0", "pool-0")).thenReturn(0);
		when(statusTransitions.claimChannel(1L, "channel-1", "pool-1")).thenReturn(1);
		when(ivsClient.createStreamKey(any(CreateStreamKeyRequest.class)))
				.thenReturn(createStreamKeyResponse("arn:aws:ivs:region:acct:stream-key/def", "sk_fresh_1"));

		Optional<LiveChannelPool.ReservedChannel> reserved = pool.reserve(1L);

		assertThat(reserved).contains(
				LiveChannelPool.ReservedChannel.webrtc("channel-1", "pool-1", "endpoint-1", "sk_fresh_1"));

		ArgumentCaptor<CreateStreamKeyRequest> captor = ArgumentCaptor.forClass(CreateStreamKeyRequest.class);
		verify(ivsClient).createStreamKey(captor.capture());
		assertThat(captor.getValue().channelArn()).isEqualTo("channel-1");
	}

	@Test
	void reserveTreatsADeadlockLossTheSameAsAZeroRowClaimAndMovesOn() {
		when(statusTransitions.claimChannel(1L, "channel-0", "pool-0"))
				.thenThrow(new CannotAcquireLockException("deadlock victim"));
		when(statusTransitions.claimChannel(1L, "channel-1", "pool-1")).thenReturn(1);
		when(ivsClient.createStreamKey(any(CreateStreamKeyRequest.class)))
				.thenReturn(createStreamKeyResponse("arn:aws:ivs:region:acct:stream-key/def", "sk_fresh_1"));

		Optional<LiveChannelPool.ReservedChannel> reserved = pool.reserve(1L);

		assertThat(reserved).isPresent();
		assertThat(reserved.orElseThrow().channelId()).isEqualTo("channel-1");
	}

	@Test
	void reserveReturnsEmptyWhenEveryChannelIsAlreadyHeld() {
		when(statusTransitions.claimChannel(eq(1L), any(), any())).thenReturn(0);

		Optional<LiveChannelPool.ReservedChannel> reserved = pool.reserve(1L);

		assertThat(reserved).isEmpty();
		verify(ivsClient, never()).createStreamKey(any(CreateStreamKeyRequest.class));
	}

	@Test
	void reserveCompensatesToFailedAndRethrowsWhenCreateStreamKeyFails() {
		when(statusTransitions.claimChannel(1L, "channel-0", "pool-0")).thenReturn(1);
		when(ivsClient.createStreamKey(any(CreateStreamKeyRequest.class)))
				.thenThrow(new RuntimeException("IVS rejected the request"));

		assertThatThrownBy(() -> pool.reserve(1L)).isInstanceOf(RuntimeException.class);

		verify(statusTransitions).markFailed(1L);
	}

	@Test
	void releaseStopsTheStreamCurrentlyBoundToTheChannel() {
		var user = new User("streamer@example.com", "Streamer", "hash");
		var stream = new Stream(user, "Broadcast", IngestMode.WEBRTC);
		stream.setChannelId("channel-0");
		when(streamRepository.findById(1L)).thenReturn(Optional.of(stream));

		pool.release(1L);

		verify(ivsClient).stopStream(StopStreamRequest.builder().channelArn("channel-0").build());
	}

	/**
	 * A stream still in STARTING (claimed a channel, but the browser never
	 * established a WebRTC session before "End Stream" was clicked) is a
	 * real, reachable case - StopStream 404s on a channel with nothing
	 * actively streaming. Must not propagate as an error.
	 */
	@Test
	void releaseTreatsChannelNotBroadcastingAsANoOp() {
		var user = new User("streamer@example.com", "Streamer", "hash");
		var stream = new Stream(user, "Broadcast", IngestMode.WEBRTC);
		stream.setChannelId("channel-0");
		when(streamRepository.findById(1L)).thenReturn(Optional.of(stream));
		when(ivsClient.stopStream(any(StopStreamRequest.class)))
				.thenThrow(ChannelNotBroadcastingException.builder().message("not broadcasting").build());

		assertThatCode(() -> pool.release(1L)).doesNotThrowAnyException();
	}

	@Test
	void releaseIsANoOpWhenTheStreamNeverClaimedAChannel() {
		var user = new User("streamer@example.com", "Streamer", "hash");
		var stream = new Stream(user, "Broadcast", IngestMode.WEBRTC);
		when(streamRepository.findById(1L)).thenReturn(Optional.of(stream));

		pool.release(1L);

		verifyNoInteractions(ivsClient);
	}

	@Test
	void releaseIsANoOpWhenTheStreamNoLongerExists() {
		when(streamRepository.findById(1L)).thenReturn(Optional.empty());

		pool.release(1L);

		verifyNoInteractions(ivsClient);
	}

	@Test
	void confirmStoppedDeletesTheStreamKey() {
		var user = new User("streamer@example.com", "Streamer", "hash");
		var stream = new Stream(user, "Broadcast", IngestMode.WEBRTC);
		stream.setIngestSecretArn("arn:aws:ivs:region:acct:stream-key/abc");
		when(streamRepository.findById(1L)).thenReturn(Optional.of(stream));

		pool.confirmStopped(1L);

		verify(ivsClient).deleteStreamKey(
				DeleteStreamKeyRequest.builder().arn("arn:aws:ivs:region:acct:stream-key/abc").build());
	}

	@Test
	void confirmStoppedIsANoOpWhenTheStreamHasNoIngestSecretArn() {
		var user = new User("streamer@example.com", "Streamer", "hash");
		var stream = new Stream(user, "Broadcast", IngestMode.WEBRTC);
		when(streamRepository.findById(1L)).thenReturn(Optional.of(stream));

		pool.confirmStopped(1L);

		verifyNoInteractions(ivsClient);
	}

	@Test
	void confirmStoppedIsANoOpWhenTheStreamNoLongerExists() {
		when(streamRepository.findById(1L)).thenReturn(Optional.empty());

		pool.confirmStopped(1L);

		verifyNoInteractions(ivsClient);
	}

}

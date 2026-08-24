package com.yaostreaming.api.live.rtmp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import software.amazon.awssdk.services.medialive.MediaLiveClient;
import software.amazon.awssdk.services.medialive.model.ChannelState;
import software.amazon.awssdk.services.medialive.model.ConflictException;
import software.amazon.awssdk.services.medialive.model.DescribeChannelRequest;
import software.amazon.awssdk.services.medialive.model.DescribeChannelResponse;
import software.amazon.awssdk.services.medialive.model.Input;
import software.amazon.awssdk.services.medialive.model.InputDestination;
import software.amazon.awssdk.services.medialive.model.StartChannelRequest;
import software.amazon.awssdk.services.medialive.model.StopChannelRequest;
import software.amazon.awssdk.services.medialive.model.UpdateInputRequest;
import software.amazon.awssdk.services.medialive.model.UpdateInputResponse;
import software.amazon.awssdk.services.mediapackagev2.MediaPackageV2Client;
import software.amazon.awssdk.services.mediapackagev2.model.ResetOriginEndpointStateRequest;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MediaLiveChannelPoolTest {

	private static final MediaLiveChannelPoolProperties TWO_SLOT_POOL = new MediaLiveChannelPoolProperties(
			true,
			List.of("channel-0", "channel-1"),
			List.of("input-0", "input-1"),
			List.of("pool-0", "pool-1"),
			"channel-group");

	@Mock
	private StreamRepository streamRepository;

	@Mock
	private StreamStatusTransitions statusTransitions;

	@Mock
	private MediaLiveClient mediaLiveClient;

	@Mock
	private MediaPackageV2Client mediaPackageV2Client;

	private MediaLiveChannelPool pool;

	@BeforeEach
	void setUp() {
		pool = new MediaLiveChannelPool(
				streamRepository, statusTransitions, mediaLiveClient, mediaPackageV2Client, TWO_SLOT_POOL);
		// Every reserve() candidate is checked via DescribeChannel before a
		// claim is even attempted - default every channel to IDLE so
		// existing tests don't each need to restate it; tests specifically
		// about the idle-check override this per channel.
		when(mediaLiveClient.describeChannel(any(DescribeChannelRequest.class)))
				.thenReturn(describeChannelResponse(ChannelState.IDLE));
	}

	/** UpdateInput's real response shape - see MediaLiveChannelPool.rotateIngestSecret. */
	private static UpdateInputResponse updateInputResponse(String url) {
		return UpdateInputResponse.builder()
				.input(Input.builder().destinations(InputDestination.builder().url(url).build()).build())
				.build();
	}

	private static DescribeChannelResponse describeChannelResponse(ChannelState state) {
		return DescribeChannelResponse.builder().state(state).build();
	}

	@Test
	void constructorRejectsMismatchedListLengths() {
		MediaLiveChannelPoolProperties mismatched = new MediaLiveChannelPoolProperties(
				true, List.of("channel-0", "channel-1"), List.of("input-0"), List.of("pool-0", "pool-1"),
				"channel-group");

		assertThatThrownBy(() -> new MediaLiveChannelPool(
				streamRepository, statusTransitions, mediaLiveClient, mediaPackageV2Client, mismatched))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Terraform/deploy.sh drift");
	}

	@Test
	void reserveClaimsTheFirstFreeChannelResetsItRotatesItsSecretAndStartsIt() {
		when(statusTransitions.claimChannel(1L, "channel-0", "pool-0")).thenReturn(1);
		when(mediaLiveClient.updateInput(any(UpdateInputRequest.class)))
				.thenReturn(updateInputResponse("rtmp://host/fresh-secret-0"));

		Optional<LiveChannelPool.ReservedChannel> reserved = pool.reserve(1L);

		assertThat(reserved).contains(
				new LiveChannelPool.ReservedChannel("channel-0", "pool-0", "rtmp://host/fresh-secret-0"));
		verify(mediaLiveClient).startChannel(StartChannelRequest.builder().channelId("channel-0").build());

		ArgumentCaptor<UpdateInputRequest> captor = ArgumentCaptor.forClass(UpdateInputRequest.class);
		verify(mediaLiveClient).updateInput(captor.capture());
		assertThat(captor.getValue().inputId()).isEqualTo("input-0");
		// A fresh 128-bit secret, not the slot's own static name - the
		// whole point of rotating in the first place.
		assertThat(captor.getValue().destinations().getFirst().streamName())
				.hasSize(32)
				.isNotEqualTo("pool-0");
	}

	/**
	 * Confirmed live: release()'s own reset (at end-of-stream) isn't
	 * sufficient by itself - it fires right after the accept-and-return
	 * StopChannel call, but the channel is still physically flushing its
	 * last real segments into MediaPackage for some seconds afterward, so
	 * those trailing segments land in a manifest reset a moment too early
	 * and then sit there permanently. This second reset, run only once
	 * isIdle() has confirmed the channel truly finished stopping, is what
	 * actually closes the gap.
	 */
	@Test
	void reserveResetsTheClaimedSlotsOriginEndpointBeforeStartingIt() {
		when(statusTransitions.claimChannel(1L, "channel-0", "pool-0")).thenReturn(1);
		when(mediaLiveClient.updateInput(any(UpdateInputRequest.class)))
				.thenReturn(updateInputResponse("rtmp://host/fresh-secret-0"));

		pool.reserve(1L);

		verify(mediaPackageV2Client).resetOriginEndpointState(ResetOriginEndpointStateRequest.builder()
				.channelGroupName("channel-group")
				.channelName("pool-0")
				.originEndpointName("pool-0")
				.build());
	}

	@Test
	void reserveSkipsAnAlreadyClaimedChannelAndTriesTheNext() {
		when(statusTransitions.claimChannel(1L, "channel-0", "pool-0")).thenReturn(0);
		when(statusTransitions.claimChannel(1L, "channel-1", "pool-1")).thenReturn(1);
		when(mediaLiveClient.updateInput(any(UpdateInputRequest.class)))
				.thenReturn(updateInputResponse("rtmp://host/fresh-secret-1"));

		Optional<LiveChannelPool.ReservedChannel> reserved = pool.reserve(1L);

		assertThat(reserved).contains(
				new LiveChannelPool.ReservedChannel("channel-1", "pool-1", "rtmp://host/fresh-secret-1"));
		verify(mediaLiveClient, never()).startChannel(StartChannelRequest.builder().channelId("channel-0").build());

		ArgumentCaptor<UpdateInputRequest> captor = ArgumentCaptor.forClass(UpdateInputRequest.class);
		verify(mediaLiveClient).updateInput(captor.capture());
		assertThat(captor.getValue().inputId()).isEqualTo("input-1");
	}

	@Test
	void reserveTreatsADeadlockLossTheSameAsAZeroRowClaimAndMovesOn() {
		when(statusTransitions.claimChannel(1L, "channel-0", "pool-0"))
				.thenThrow(new CannotAcquireLockException("deadlock victim"));
		when(statusTransitions.claimChannel(1L, "channel-1", "pool-1")).thenReturn(1);
		when(mediaLiveClient.updateInput(any(UpdateInputRequest.class)))
				.thenReturn(updateInputResponse("rtmp://host/fresh-secret-1"));

		Optional<LiveChannelPool.ReservedChannel> reserved = pool.reserve(1L);

		assertThat(reserved).isPresent();
		assertThat(reserved.orElseThrow().channelId()).isEqualTo("channel-1");
	}

	@Test
	void reserveReturnsEmptyWhenEveryChannelIsAlreadyHeld() {
		when(statusTransitions.claimChannel(eq(1L), any(), any())).thenReturn(0);

		Optional<LiveChannelPool.ReservedChannel> reserved = pool.reserve(1L);

		assertThat(reserved).isEmpty();
		verify(mediaLiveClient, never()).startChannel(any(StartChannelRequest.class));
		verify(mediaLiveClient, never()).updateInput(any(UpdateInputRequest.class));
	}

	/**
	 * Checked against real MediaLive state via DescribeChannel before ever
	 * attempting to claim it - skips a candidate that's not IDLE (most
	 * often still physically STOPPING from its previous claimant) without
	 * even trying claimChannel on it, and moves straight to the next
	 * candidate. Confirmed live: without this, a request could 503 outright
	 * just because the *first* candidate happened to be mid-stop, even with
	 * other genuinely idle channels sitting unused right behind it.
	 */
	@Test
	void reserveSkipsANonIdleChannelWithoutAttemptingToClaimItAndTriesTheNext() {
		when(mediaLiveClient.describeChannel(DescribeChannelRequest.builder().channelId("channel-0").build()))
				.thenReturn(describeChannelResponse(ChannelState.STOPPING));
		when(statusTransitions.claimChannel(1L, "channel-1", "pool-1")).thenReturn(1);
		when(mediaLiveClient.updateInput(any(UpdateInputRequest.class)))
				.thenReturn(updateInputResponse("rtmp://host/fresh-secret-1"));

		Optional<LiveChannelPool.ReservedChannel> reserved = pool.reserve(1L);

		assertThat(reserved).contains(
				new LiveChannelPool.ReservedChannel("channel-1", "pool-1", "rtmp://host/fresh-secret-1"));
		verify(statusTransitions, never()).claimChannel(1L, "channel-0", "pool-0");
	}

	@Test
	void reserveReturnsEmptyWhenNoChannelIsIdleWithoutAttemptingAnyClaim() {
		when(mediaLiveClient.describeChannel(any(DescribeChannelRequest.class)))
				.thenReturn(describeChannelResponse(ChannelState.STOPPING));

		Optional<LiveChannelPool.ReservedChannel> reserved = pool.reserve(1L);

		assertThat(reserved).isEmpty();
		verify(statusTransitions, never()).claimChannel(any(), any(), any());
	}

	@Test
	void reserveStripsTheArnDownToTheBareIdBeforeCallingStartChannel() {
		MediaLiveChannelPoolProperties arnPool = new MediaLiveChannelPoolProperties(
				true,
				List.of("arn:aws:medialive:ap-northeast-1:720289141862:channel:4356937"),
				List.of("input-0"),
				List.of("pool-0"),
				"channel-group");
		pool = new MediaLiveChannelPool(
				streamRepository, statusTransitions, mediaLiveClient, mediaPackageV2Client, arnPool);
		when(statusTransitions.claimChannel(1L, "arn:aws:medialive:ap-northeast-1:720289141862:channel:4356937",
				"pool-0")).thenReturn(1);
		when(mediaLiveClient.updateInput(any(UpdateInputRequest.class)))
				.thenReturn(updateInputResponse("rtmp://host/fresh-secret-0"));

		Optional<LiveChannelPool.ReservedChannel> reserved = pool.reserve(1L);

		// StartChannel's channelId is a URI path segment on the real
		// MediaLive API (confirmed against the service model) - a full ARN
		// there 403s, since AWS's own authorization check embeds it into its
		// resource-ARN template a second time. claimChannel and the returned
		// ReservedChannel still carry the full ARN, unchanged - only the
		// literal SDK call needs the bare id.
		verify(mediaLiveClient).startChannel(StartChannelRequest.builder().channelId("4356937").build());
		assertThat(reserved.orElseThrow().channelId())
				.isEqualTo("arn:aws:medialive:ap-northeast-1:720289141862:channel:4356937");
	}

	@Test
	void reserveCompensatesToFailedAndRethrowsWhenStartChannelFails() {
		when(statusTransitions.claimChannel(1L, "channel-0", "pool-0")).thenReturn(1);
		when(mediaLiveClient.updateInput(any(UpdateInputRequest.class)))
				.thenReturn(updateInputResponse("rtmp://host/fresh-secret-0"));
		when(mediaLiveClient.startChannel(any(StartChannelRequest.class)))
				.thenThrow(new RuntimeException("MediaLive rejected the request"));

		assertThatThrownBy(() -> pool.reserve(1L)).isInstanceOf(RuntimeException.class);

		verify(statusTransitions).markFailed(1L);
	}

	/**
	 * The DB claim already committed and the slot's secret may already be
	 * half-rotated by the time UpdateInput itself fails - same "don't leave
	 * it stuck reserved-but-unusable" reasoning as a StartChannel failure.
	 */
	@Test
	void reserveCompensatesToFailedAndRethrowsWhenUpdateInputFails() {
		when(statusTransitions.claimChannel(1L, "channel-0", "pool-0")).thenReturn(1);
		when(mediaLiveClient.updateInput(any(UpdateInputRequest.class)))
				.thenThrow(new RuntimeException("MediaLive rejected the request"));

		assertThatThrownBy(() -> pool.reserve(1L)).isInstanceOf(RuntimeException.class);

		verify(statusTransitions).markFailed(1L);
		verify(mediaLiveClient, never()).startChannel(any(StartChannelRequest.class));
	}

	/**
	 * Confirmed live: MediaLive rejects UpdateInput with a 409 if the
	 * previous claimant's StopChannel hasn't physically finished yet, even
	 * though DescribeChannel reported IDLE and endStream already marked
	 * that row ENDED (synchronously, not waiting on the still-broken IDLE
	 * callback) - a real race between the idle check above and the claim
	 * itself, not fully closed by it.
	 *
	 * <p>Deliberately does NOT try the next candidate the way a lost
	 * claimChannel race does: this claim already committed the row to
	 * STARTING, so markFailed's STARTING -> FAILED write leaves
	 * claimChannel's own PENDING-only precondition permanently unmatchable
	 * for this row - confirmed live, the hard way, that a "try the next
	 * candidate" version of this fix breaks every later candidate in the
	 * same loop regardless of whether they're actually free. Fails this
	 * attempt cleanly instead, same as pool exhaustion - the client retries
	 * with a fresh goLive() call/fresh PENDING row.
	 */
	@Test
	void reserveFailsCleanlyOnAStillStoppingChannelConflictWithoutTryingOtherCandidates() {
		when(statusTransitions.claimChannel(1L, "channel-0", "pool-0")).thenReturn(1);
		when(mediaLiveClient.updateInput(any(UpdateInputRequest.class)))
				.thenThrow(ConflictException.builder()
						.message("Your input is being used by a running channel and cannot be updated at this time.")
						.build());

		Optional<LiveChannelPool.ReservedChannel> reserved = pool.reserve(1L);

		assertThat(reserved).isEmpty();
		verify(statusTransitions).markFailed(1L);
		verify(statusTransitions, never()).claimChannel(1L, "channel-1", "pool-1");
		verify(mediaLiveClient, never()).startChannel(any(StartChannelRequest.class));
	}

	@Test
	void releaseStopsTheChannelCurrentlyBoundToTheStream() {
		var user = new User("streamer@example.com", "Streamer", "hash");
		var stream = new Stream(user, "Broadcast");
		stream.setChannelId("channel-0");
		when(streamRepository.findById(1L)).thenReturn(Optional.of(stream));

		pool.release(1L);

		verify(mediaLiveClient).stopChannel(StopChannelRequest.builder().channelId("channel-0").build());
		verifyNoInteractions(mediaPackageV2Client);
	}

	@Test
	void releaseStripsTheArnDownToTheBareIdBeforeCallingStopChannel() {
		var user = new User("streamer@example.com", "Streamer", "hash");
		var stream = new Stream(user, "Broadcast");
		stream.setChannelId("arn:aws:medialive:ap-northeast-1:720289141862:channel:4356937");
		when(streamRepository.findById(1L)).thenReturn(Optional.of(stream));

		pool.release(1L);

		verify(mediaLiveClient).stopChannel(StopChannelRequest.builder().channelId("4356937").build());
	}

	/**
	 * release() deliberately does NOT reset MediaPackage anymore - confirmed
	 * live that resetting immediately after the accept-and-return
	 * StopChannel call is actively wrong (the channel is still flushing
	 * real segments for some seconds afterward). Only stopChannel happens
	 * here now; the reset moved to confirmStopped(), called by
	 * LiveCallbackController once the async STOPPED confirmation actually
	 * arrives.
	 */
	@Test
	void releaseNoLongerResetsTheOriginEndpoint() {
		var user = new User("streamer@example.com", "Streamer", "hash");
		var stream = new Stream(user, "Broadcast");
		stream.setChannelId("channel-0");
		stream.setOriginSlug("pool-0");
		when(streamRepository.findById(1L)).thenReturn(Optional.of(stream));

		pool.release(1L);

		verifyNoInteractions(mediaPackageV2Client);
	}

	/**
	 * Without this, a viewer who lands on this slot's *next* claimant
	 * within the origin endpoint's manifest window could be served this
	 * stream's actual content under someone else's session - confirmed
	 * live, the hard way, and a genuinely different problem from the
	 * ingest-URL rotation above (this is about playback, not push). Called
	 * only once the async callback confirms STOPPED, by which point the
	 * channel's final segment flush is guaranteed complete - unlike a
	 * release()-time reset, which isn't.
	 */
	@Test
	void confirmStoppedResetsTheOriginEndpointForTheStreamsSlug() {
		var user = new User("streamer@example.com", "Streamer", "hash");
		var stream = new Stream(user, "Broadcast");
		stream.setChannelId("channel-0");
		stream.setOriginSlug("pool-0");
		when(streamRepository.findById(1L)).thenReturn(Optional.of(stream));

		pool.confirmStopped(1L);

		verify(mediaPackageV2Client).resetOriginEndpointState(ResetOriginEndpointStateRequest.builder()
				.channelGroupName("channel-group")
				.channelName("pool-0")
				.originEndpointName("pool-0")
				.build());
		verifyNoInteractions(mediaLiveClient);
	}

	@Test
	void confirmStoppedIsANoOpWhenTheStreamHasNoOriginSlug() {
		var user = new User("streamer@example.com", "Streamer", "hash");
		var stream = new Stream(user, "Broadcast");
		when(streamRepository.findById(1L)).thenReturn(Optional.of(stream));

		pool.confirmStopped(1L);

		verifyNoInteractions(mediaPackageV2Client);
	}

	@Test
	void confirmStoppedIsANoOpWhenTheStreamNoLongerExists() {
		when(streamRepository.findById(1L)).thenReturn(Optional.empty());

		pool.confirmStopped(1L);

		verifyNoInteractions(mediaPackageV2Client);
	}

	@Test
	void releaseIsANoOpWhenTheStreamNeverClaimedAChannel() {
		var user = new User("streamer@example.com", "Streamer", "hash");
		var stream = new Stream(user, "Broadcast");
		when(streamRepository.findById(1L)).thenReturn(Optional.of(stream));

		pool.release(1L);

		verifyNoInteractions(mediaLiveClient);
		verifyNoInteractions(mediaPackageV2Client);
	}

	@Test
	void releaseIsANoOpWhenTheStreamNoLongerExists() {
		when(streamRepository.findById(1L)).thenReturn(Optional.empty());

		pool.release(1L);

		verifyNoInteractions(mediaLiveClient);
		verifyNoInteractions(mediaPackageV2Client);
	}

}

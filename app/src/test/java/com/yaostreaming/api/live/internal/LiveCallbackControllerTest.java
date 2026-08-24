package com.yaostreaming.api.live.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yaostreaming.api.live.LiveChannelPool;
import com.yaostreaming.api.live.Stream;
import com.yaostreaming.api.live.StreamRepository;
import com.yaostreaming.api.live.StreamStatus;
import com.yaostreaming.api.live.StreamStatusTransitions;
import com.yaostreaming.api.security.DatabaseUserDetailsService;
import com.yaostreaming.api.security.SecurityConfig;
import com.yaostreaming.api.storage.CloudFrontProperties;
import com.yaostreaming.api.user.User;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

/**
 * No {@code @WithMockUser} anywhere here on purpose: the live-state-change
 * Lambda calls this endpoint with no session at all, so every request below
 * is deliberately unauthenticated - proving {@code SecurityConfig}'s
 * {@code permitAll()} + CSRF exemption for {@code /internal/**} actually
 * work, not just that the dispatch logic is correct. Mirrors
 * {@code TranscodeCallbackControllerTest} exactly.
 */
@WebMvcTest(LiveCallbackController.class)
@Import(SecurityConfig.class)
class LiveCallbackControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private DatabaseUserDetailsService userDetailsService;

	@MockitoBean
	private StreamRepository streamRepository;

	@MockitoBean
	private StreamStatusTransitions statusTransitions;

	@MockitoBean
	private LiveChannelPool liveChannelPool;

	@MockitoBean
	private CloudFrontProperties cloudFrontProperties;

	private static Stream activeStream(Long id) {
		Stream stream = new Stream(new User("streamer@example.com", "Streamer", "hash"), "Broadcast");
		ReflectionTestUtils.setField(stream, "id", id);
		return stream;
	}

	@Test
	void runningCallbackMarksTheStreamLiveAndReturnsNoContent() throws Exception {
		when(streamRepository.findByChannelIdAndStatusIn("channel-0",
				List.of(StreamStatus.STARTING, StreamStatus.LIVE, StreamStatus.ENDING)))
				.thenReturn(Optional.of(activeStream(1L)));
		when(statusTransitions.markLive(1L)).thenReturn(true);

		mockMvc.perform(post("/internal/live/callback")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"channelId":"channel-0","status":"RUNNING"}
								"""))
				.andExpect(status().isNoContent());

		verify(statusTransitions).markLive(1L);
		verify(statusTransitions, never()).markEnded(any());
		verify(liveChannelPool, never()).confirmStopped(any());
	}

	/**
	 * This, not LiveChannelPool.release(), is where MediaPackage cleanup
	 * for the ended stream's slot actually happens now - confirmed live
	 * that release()-time cleanup runs before the channel's final segment
	 * flush is done, letting real content leak into the next claimant's
	 * session. See LiveChannelPool.confirmStopped's own javadoc.
	 */
	@Test
	void stoppedCallbackMarksTheStreamEndedConfirmsStoppedAndReturnsNoContent() throws Exception {
		when(streamRepository.findByChannelIdAndStatusIn("channel-0",
				List.of(StreamStatus.STARTING, StreamStatus.LIVE, StreamStatus.ENDING)))
				.thenReturn(Optional.of(activeStream(2L)));
		when(statusTransitions.markEnded(2L)).thenReturn(true);

		mockMvc.perform(post("/internal/live/callback")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"channelId":"channel-0","status":"STOPPED"}
								"""))
				.andExpect(status().isNoContent());

		verify(statusTransitions).markEnded(2L);
		verify(statusTransitions, never()).markLive(any());
		verify(liveChannelPool).confirmStopped(2L);
	}

	/**
	 * EventBridge is at-least-once - a redelivered STOPPED callback that
	 * no-ops (markEnded returns false, already applied) shouldn't
	 * re-trigger the MediaPackage reset. Harmless either way since
	 * ResetOriginEndpointState is idempotent, but no reason to make the
	 * redundant call.
	 */
	@Test
	void aRedeliveredStoppedCallbackDoesNotReconfirmStopped() throws Exception {
		when(streamRepository.findByChannelIdAndStatusIn("channel-0",
				List.of(StreamStatus.STARTING, StreamStatus.LIVE, StreamStatus.ENDING)))
				.thenReturn(Optional.of(activeStream(2L)));
		when(statusTransitions.markEnded(2L)).thenReturn(false);

		mockMvc.perform(post("/internal/live/callback")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"channelId":"channel-0","status":"STOPPED"}
								"""))
				.andExpect(status().isNoContent());

		verify(liveChannelPool, never()).confirmStopped(any());
	}

	@Test
	void ignoresACallbackForAChannelWithNoActiveStream() throws Exception {
		when(streamRepository.findByChannelIdAndStatusIn("channel-9",
				List.of(StreamStatus.STARTING, StreamStatus.LIVE, StreamStatus.ENDING)))
				.thenReturn(Optional.empty());

		mockMvc.perform(post("/internal/live/callback")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"channelId":"channel-9","status":"RUNNING"}
								"""))
				.andExpect(status().isNoContent());

		verify(statusTransitions, never()).markLive(any());
		verify(statusTransitions, never()).markEnded(any());
	}

	@Test
	void aRedeliveredCallbackStillReturnsNoContentEvenWhenAlreadyApplied() throws Exception {
		when(streamRepository.findByChannelIdAndStatusIn("channel-0",
				List.of(StreamStatus.STARTING, StreamStatus.LIVE, StreamStatus.ENDING)))
				.thenReturn(Optional.of(activeStream(1L)));
		when(statusTransitions.markLive(1L)).thenReturn(false);

		mockMvc.perform(post("/internal/live/callback")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"channelId":"channel-0","status":"RUNNING"}
								"""))
				.andExpect(status().isNoContent());
	}

	@Test
	void rejectsAPayloadMissingChannelId() throws Exception {
		mockMvc.perform(post("/internal/live/callback")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"status":"RUNNING"}
								"""))
				.andExpect(status().isBadRequest());
	}

	@Test
	void rejectsAnUnknownStatusValue() throws Exception {
		mockMvc.perform(post("/internal/live/callback")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"channelId":"channel-0","status":"FAILED"}
								"""))
				.andExpect(status().isBadRequest());
	}

}

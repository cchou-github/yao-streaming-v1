package com.yaostreaming.api.live.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
	}

	@Test
	void stoppedCallbackMarksTheStreamEndedAndReturnsNoContent() throws Exception {
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

package com.yaostreaming.api.transcode.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yaostreaming.api.security.DatabaseUserDetailsService;
import com.yaostreaming.api.security.SecurityConfig;
import com.yaostreaming.api.storage.CloudFrontProperties;
import com.yaostreaming.api.transcode.VideoStatusTransitions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * No {@code @WithMockUser} anywhere here on purpose: the completion Lambda
 * calls this endpoint with no session at all, so every request below is
 * deliberately unauthenticated - proving {@code SecurityConfig}'s
 * {@code permitAll()} + CSRF exemption for {@code /internal/**} actually
 * work, not just that the service logic is correct.
 */
@WebMvcTest(TranscodeCallbackController.class)
@Import(SecurityConfig.class)
class TranscodeCallbackControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private DatabaseUserDetailsService userDetailsService;

	@MockitoBean
	private VideoStatusTransitions statusTransitions;

	@MockitoBean
	private CloudFrontProperties cloudFrontProperties;

	@Test
	void readyCallbackCompletesTheVideoAndReturnsNoContent() throws Exception {
		when(statusTransitions.completeFromUpload(1L, "videos/1/master.m3u8", 42)).thenReturn(true);

		mockMvc.perform(post("/internal/transcode/callback")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"videoId":1,"status":"READY","outputKey":"videos/1/master.m3u8","durationSeconds":42}
						"""))
				.andExpect(status().isNoContent());

		verify(statusTransitions).completeFromUpload(1L, "videos/1/master.m3u8", 42);
		verify(statusTransitions, never()).markFailed(any());
	}

	@Test
	void failedCallbackMarksTheVideoFailedAndReturnsNoContent() throws Exception {
		mockMvc.perform(post("/internal/transcode/callback")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"videoId":2,"status":"FAILED"}
						"""))
				.andExpect(status().isNoContent());

		verify(statusTransitions).markFailed(2L);
		verify(statusTransitions, never()).completeFromUpload(any(), any(), any());
	}

	@Test
	void aRedeliveredCallbackStillReturnsNoContentEvenWhenAlreadyApplied() throws Exception {
		when(statusTransitions.completeFromUpload(eq(1L), any(), any())).thenReturn(false);

		mockMvc.perform(post("/internal/transcode/callback")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"videoId":1,"status":"READY","outputKey":"videos/1/master.m3u8","durationSeconds":42}
						"""))
				.andExpect(status().isNoContent());
	}

	@Test
	void rejectsAPayloadMissingVideoId() throws Exception {
		mockMvc.perform(post("/internal/transcode/callback")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"status":"READY"}
						"""))
				.andExpect(status().isBadRequest());
	}

	@Test
	void rejectsAnUnknownStatusValue() throws Exception {
		mockMvc.perform(post("/internal/transcode/callback")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"videoId":1,"status":"PROCESSING"}
						"""))
				.andExpect(status().isBadRequest());
	}

}

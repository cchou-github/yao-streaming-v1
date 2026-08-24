package com.yaostreaming.api.live;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yaostreaming.api.live.rtmp.RtmpGoLiveResponse;
import com.yaostreaming.api.security.SecurityConfig;
import com.yaostreaming.api.storage.CloudFrontProperties;
import com.yaostreaming.api.user.CurrentUserProvider;
import com.yaostreaming.api.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(LiveStreamController.class)
@Import(SecurityConfig.class)
@WithMockUser
class LiveStreamControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private LiveStreamingService liveStreamingService;

	@MockitoBean
	private CurrentUserProvider currentUserProvider;

	@MockitoBean
	private CloudFrontProperties cloudFrontProperties;

	private static final String VALID_BODY = """
			{"title":"My Broadcast","description":"Playing games","mode":"RTMP"}
			""";

	@Test
	void goLiveReturnsCreatedWithTheIngestUrl() throws Exception {
		User user = new User("dev@example.com", "Dev User", "hash");
		when(currentUserProvider.require()).thenReturn(user);
		when(liveStreamingService.goLive(eq(user), any()))
				.thenReturn(new RtmpGoLiveResponse(7L, "rtmp://18.177.13.33:1935/pool-0", StreamStatus.STARTING));

		mockMvc.perform(post("/api/streams").with(csrf()).contentType(MediaType.APPLICATION_JSON)
						.content(VALID_BODY))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.streamId").value(7))
				.andExpect(jsonPath("$.ingestUrl").value("rtmp://18.177.13.33:1935/pool-0"))
				.andExpect(jsonPath("$.status").value("STARTING"));
	}

	@Test
	void goLiveRejectsBlankTitle() throws Exception {
		String body = """
				{"title":"  "}
				""";

		mockMvc.perform(post("/api/streams").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest());

		verify(liveStreamingService, never()).goLive(any(), any());
	}

	@Test
	void goLiveRejectsMissingTitle() throws Exception {
		String body = """
				{"description":"Playing games"}
				""";

		mockMvc.perform(post("/api/streams").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest());

		verify(liveStreamingService, never()).goLive(any(), any());
	}

	@Test
	void endStreamReturnsNoContent() throws Exception {
		User user = new User("dev@example.com", "Dev User", "hash");
		when(currentUserProvider.require()).thenReturn(user);

		mockMvc.perform(post("/api/streams/7/end").with(csrf()))
				.andExpect(status().isNoContent());

		verify(liveStreamingService).endStream(user, 7L);
	}

}

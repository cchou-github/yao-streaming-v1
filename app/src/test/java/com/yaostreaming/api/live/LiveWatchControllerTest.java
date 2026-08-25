package com.yaostreaming.api.live;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.yaostreaming.api.security.DatabaseUserDetailsService;
import com.yaostreaming.api.security.SecurityConfig;
import com.yaostreaming.api.storage.CloudFrontCookieSigner;
import com.yaostreaming.api.storage.CloudFrontProperties;
import com.yaostreaming.api.user.CurrentUserProvider;
import com.yaostreaming.api.user.User;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseCookie;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(LiveWatchController.class)
@Import(SecurityConfig.class)
class LiveWatchControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private LiveCatalogService liveCatalogService;

	@MockitoBean
	private DatabaseUserDetailsService userDetailsService;

	// Neither is a web-layer bean, so @WebMvcTest won't load them - stub like
	// any other LiveWatchController dependency, same as VideoPageControllerTest.
	@MockitoBean
	private CloudFrontProperties cloudFrontProperties;

	@MockitoBean
	private CloudFrontCookieSigner cloudFrontCookieSigner;

	@MockitoBean
	private CurrentUserProvider currentUserProvider;

	private static LiveStreamDetail live() {
		return new LiveStreamDetail(7L, "Ranked queue", "Climbing to Diamond", StreamStatus.LIVE, "pool-2",
				"https://d123.cloudfront.net/live/pool-2/master.m3u8", "Reiko", Instant.EPOCH, IngestMode.RTMP);
	}

	private static User userWithId(long id) {
		User user = new User("streamer@example.com", "Reiko", "hash");
		ReflectionTestUtils.setField(user, "id", id);
		return user;
	}

	@Test
	@WithMockUser
	void catalogListsLiveStreams() throws Exception {
		when(liveCatalogService.listLive()).thenReturn(List.of(
				new LiveStreamSummary(1L, "Ranked queue", "Reiko", Instant.EPOCH, IngestMode.RTMP)));

		mockMvc.perform(get("/streams"))
				.andExpect(status().isOk())
				.andExpect(view().name("streams/catalog"))
				.andExpect(model().attributeExists("streams"))
				.andExpect(content().string(Matchers.containsString("Ranked queue")));
	}

	@Test
	@WithMockUser
	void catalogRendersAnEmptyState() throws Exception {
		when(liveCatalogService.listLive()).thenReturn(List.of());

		mockMvc.perform(get("/streams"))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("Nobody is live")));
	}

	@Test
	@WithMockUser
	void goLivePageRendersTheFormWhenTheCallerHasNoActiveStream() throws Exception {
		when(currentUserProvider.require()).thenReturn(userWithId(1L));
		when(liveCatalogService.findActiveForUser(1L)).thenReturn(Optional.empty());

		mockMvc.perform(get("/streams/golive"))
				.andExpect(status().isOk())
				.andExpect(view().name("streams/golive"))
				.andExpect(model().attributeDoesNotExist("activeStream"))
				.andExpect(content().string(Matchers.containsString("<form id=\"golive-form\"")));
	}

	@Test
	@WithMockUser
	void goLivePageShowsTheActiveStreamInsteadOfTheFormWhenTheCallerAlreadyHasOne() throws Exception {
		when(currentUserProvider.require()).thenReturn(userWithId(1L));
		when(liveCatalogService.findActiveForUser(1L)).thenReturn(Optional.of(live()));

		mockMvc.perform(get("/streams/golive"))
				.andExpect(status().isOk())
				.andExpect(view().name("streams/golive"))
				.andExpect(model().attributeExists("activeStream"))
				.andExpect(content().string(Matchers.containsString("end-active-stream")))
				.andExpect(content().string(Matchers.not(Matchers.containsString("<form id=\"golive-form\""))));
	}

	@Test
	@WithMockUser
	void watchPageEmbedsThePlayerAndThePlayerScriptLoadsTheSourceUrl() throws Exception {
		when(liveCatalogService.findDetail(7L)).thenReturn(Optional.of(live()));

		mockMvc.perform(get("/streams/7"))
				.andExpect(status().isOk())
				.andExpect(view().name("streams/watch"))
				.andExpect(content().string(Matchers.containsString("hls.min.js")))
				// Thymeleaf's th:inline="javascript" escapes "/" as "\/" -
				// this is the URL as it actually appears in the <script>
				// block, not the unescaped form.
				.andExpect(content().string(Matchers.containsString("live\\/pool-2\\/master.m3u8")))
				.andExpect(content().string(Matchers.containsString("<video")));
	}

	/**
	 * The playback URL must never appear as visible page text/markup - only
	 * inside the player's own <script> block, where hls.js needs it to
	 * actually fetch the stream. A visible "Source: <url>" line used to sit
	 * right on the page; removed on request, since there's no reason a
	 * viewer needs to see (or easily copy/share) the raw manifest URL.
	 */
	@Test
	@WithMockUser
	void watchPageNeverShowsThePlaybackUrlAsVisibleText() throws Exception {
		when(liveCatalogService.findDetail(7L)).thenReturn(Optional.of(live()));

		mockMvc.perform(get("/streams/7"))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.not(Matchers.containsString("Source:"))))
				.andExpect(content().string(Matchers.not(Matchers.containsString("playback-url"))));
	}

	@Test
	@WithMockUser
	void watchPageShowsStatusInsteadOfAPlayerBeforeGoingLive() throws Exception {
		when(liveCatalogService.findDetail(8L)).thenReturn(Optional.of(new LiveStreamDetail(
				8L, "Warming up", null, StreamStatus.STARTING, "pool-1", null, "Reiko", Instant.EPOCH,
				IngestMode.RTMP)));

		mockMvc.perform(get("/streams/8"))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("Starting up")))
				.andExpect(content().string(Matchers.not(Matchers.containsString("hls.min.js"))));
	}

	@Test
	@WithMockUser
	void watchPageSetsSignedCookiesWhenCloudFrontIsEnabled() throws Exception {
		when(liveCatalogService.findDetail(7L)).thenReturn(Optional.of(live()));
		when(cloudFrontProperties.enabled()).thenReturn(true);
		when(cloudFrontCookieSigner.cookiesFor("live/pool-2/master.m3u8")).thenReturn(List.of(
				ResponseCookie.from("CloudFront-Policy", "policy-value").path("/live/").build(),
				ResponseCookie.from("CloudFront-Signature", "sig-value").path("/live/").build(),
				ResponseCookie.from("CloudFront-Key-Pair-Id", "key-value").path("/live/").build()));

		mockMvc.perform(get("/streams/7"))
				.andExpect(status().isOk())
				.andExpect(cookie().value("CloudFront-Policy", "policy-value"))
				.andExpect(cookie().value("CloudFront-Signature", "sig-value"))
				.andExpect(cookie().value("CloudFront-Key-Pair-Id", "key-value"));
	}

	@Test
	@WithMockUser
	void watchPageSetsNoCookiesWhenCloudFrontIsDisabled() throws Exception {
		when(liveCatalogService.findDetail(7L)).thenReturn(Optional.of(live()));

		mockMvc.perform(get("/streams/7"))
				.andExpect(status().isOk())
				.andExpect(cookie().doesNotExist("CloudFront-Policy"));
	}

	@Test
	@WithMockUser
	void watchPageSetsNoCookiesWhenNotYetPlayableEvenIfCloudFrontIsEnabled() throws Exception {
		when(liveCatalogService.findDetail(8L)).thenReturn(Optional.of(new LiveStreamDetail(
				8L, "Warming up", null, StreamStatus.STARTING, "pool-1", null, "Reiko", Instant.EPOCH,
				IngestMode.RTMP)));
		when(cloudFrontProperties.enabled()).thenReturn(true);

		mockMvc.perform(get("/streams/8"))
				.andExpect(status().isOk())
				.andExpect(cookie().doesNotExist("CloudFront-Policy"));
	}

	@Test
	@WithMockUser
	void unknownStreamIsNotFound() throws Exception {
		when(liveCatalogService.findDetail(any())).thenReturn(Optional.empty());

		mockMvc.perform(get("/streams/404")).andExpect(status().isNotFound());
	}

	@Test
	void catalogRequiresSigningIn() throws Exception {
		mockMvc.perform(get("/streams")).andExpect(status().is3xxRedirection());
	}

	@Test
	void watchPageRequiresSigningIn() throws Exception {
		mockMvc.perform(get("/streams/7")).andExpect(status().is3xxRedirection());
	}

	@Test
	void goLivePageRequiresSigningIn() throws Exception {
		mockMvc.perform(get("/streams/golive")).andExpect(status().is3xxRedirection());
	}

}

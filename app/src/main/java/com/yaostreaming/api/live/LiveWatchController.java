package com.yaostreaming.api.live;

import com.yaostreaming.api.storage.CloudFrontCookieSigner;
import com.yaostreaming.api.storage.CloudFrontProperties;
import com.yaostreaming.api.user.CurrentUserProvider;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

/**
 * Mirrors {@code VideoPageController}. Login is already required for every
 * route here with no extra code: {@code SecurityConfig}'s
 * {@code .anyRequest().authenticated()} covers anything not explicitly
 * {@code permitAll()}'d, same as the VOD catalog/watch pages.
 */
@Controller
public class LiveWatchController {

	private final LiveCatalogService liveCatalogService;
	private final CloudFrontProperties cloudFrontProperties;
	private final CloudFrontCookieSigner cloudFrontCookieSigner;
	private final CurrentUserProvider currentUserProvider;

	public LiveWatchController(LiveCatalogService liveCatalogService, CloudFrontProperties cloudFrontProperties,
			CloudFrontCookieSigner cloudFrontCookieSigner, CurrentUserProvider currentUserProvider) {
		this.liveCatalogService = liveCatalogService;
		this.cloudFrontProperties = cloudFrontProperties;
		this.cloudFrontCookieSigner = cloudFrontCookieSigner;
		this.currentUserProvider = currentUserProvider;
	}

	@GetMapping("/streams")
	public String catalog(Model model) {
		model.addAttribute("streams", liveCatalogService.listLive());
		return "streams/catalog";
	}

	/**
	 * Explicit path, not {@code /streams/{id}}: Spring matches this literal
	 * segment before the variable route. Shows the current user's own
	 * channel-bound stream (if any) instead of the go-live form - the
	 * server-rendered mirror of {@code LiveStreamingService#goLive}'s own
	 * one-stream-per-user rule, and the fix for not being able to end a
	 * stream after navigating away from this page: the "End stream" button
	 * used to only work via in-memory JS state set right after a successful
	 * go-live POST, with no way back to it once you left.
	 */
	@GetMapping("/streams/golive")
	public String golive(Model model) {
		liveCatalogService.findActiveForUser(currentUserProvider.require().getId())
				.ifPresent(stream -> model.addAttribute("activeStream", stream));
		return "streams/golive";
	}

	@GetMapping("/streams/{id}")
	public String watch(@PathVariable Long id, Model model, HttpServletResponse response) {
		LiveStreamDetail stream = liveCatalogService.findDetail(id)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such stream"));
		if (cloudFrontProperties.enabled() && stream.isPlayable()) {
			String playbackKey = "live/" + stream.originSlug() + "/master.m3u8";
			cloudFrontCookieSigner.cookiesFor(playbackKey)
					.forEach(cookie -> response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString()));
		}
		model.addAttribute("stream", stream);
		return "streams/watch";
	}

}

package com.yaostreaming.api.live;

import com.yaostreaming.api.user.CurrentUserProvider;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Same shape as {@code VideoUploadController} - testable end-to-end via curl, no UI needed yet. */
@RestController
@RequestMapping("/api/streams")
public class LiveStreamController {

	private final LiveStreamingService liveStreamingService;

	private final CurrentUserProvider currentUserProvider;

	public LiveStreamController(LiveStreamingService liveStreamingService,
			CurrentUserProvider currentUserProvider) {
		this.liveStreamingService = liveStreamingService;
		this.currentUserProvider = currentUserProvider;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public GoLiveResponse goLive(@Valid @RequestBody GoLiveRequest request) {
		return liveStreamingService.goLive(currentUserProvider.require(), request);
	}

	@PostMapping("/{id}/end")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void endStream(@PathVariable Long id) {
		liveStreamingService.endStream(currentUserProvider.require(), id);
	}

}

package com.yaostreaming.api.live.internal;

import com.yaostreaming.api.live.IngestMode;
import com.yaostreaming.api.live.LiveChannelPool;
import com.yaostreaming.api.live.Stream;
import com.yaostreaming.api.live.StreamRepository;
import com.yaostreaming.api.live.StreamStatus;
import com.yaostreaming.api.live.StreamStatusTransitions;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * How the async live-state-confirmation path reports MediaLive's real
 * channel state back into the app - mirrors
 * {@code TranscodeCallbackController} exactly, down to the reasoning for
 * nearly every line, since {@code StartChannel}/{@code StopChannel} are
 * accept-and-return calls the same way {@code CreateJob} is.
 *
 * <p>Reachable only via the internal ALB, itself security-group-restricted
 * to the shared completion-Lambda security group (see {@code
 * terraform/lambda.tf}'s reuse of {@code aws_security_group.lambda_complete}
 * for this Lambda too) - the network path is the actual authorization gate
 * here, not anything in this class. {@code SecurityConfig}'s existing
 * {@code /internal/**} {@code permitAll()} + CSRF exemption already covers
 * this path with zero changes needed.
 */
@RestController
@RequestMapping("/internal/live")
public class LiveCallbackController {

	private static final Logger log = LoggerFactory.getLogger(LiveCallbackController.class);

	private final StreamRepository streamRepository;
	private final StreamStatusTransitions statusTransitions;
	private final Map<IngestMode, LiveChannelPool> channelPoolsByMode;

	public LiveCallbackController(StreamRepository streamRepository, StreamStatusTransitions statusTransitions,
			Map<IngestMode, LiveChannelPool> channelPoolsByMode) {
		this.streamRepository = streamRepository;
		this.statusTransitions = statusTransitions;
		this.channelPoolsByMode = channelPoolsByMode;
	}

	@PostMapping("/callback")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void handleCallback(@Valid @RequestBody LiveCallbackRequest request) {
		Optional<Stream> stream = streamRepository.findByChannelIdAndStatusIn(request.channelId(),
				StreamStatus.CHANNEL_BOUND);

		if (stream.isEmpty()) {
			// A channel this project doesn't currently have any active
			// stream bound to - an expected steady state (e.g. this channel
			// isn't the pool's, or the confirmation arrived after the
			// stream already reached a terminal status some other way), not
			// an error.
			log.info("Ignored live callback for channel {}: no active stream currently bound to it",
					request.channelId());
			return;
		}

		Long streamId = stream.orElseThrow().getId();
		boolean applied = switch (request.status()) {
			case RUNNING -> statusTransitions.markLive(streamId);
			case STOPPED -> statusTransitions.markEnded(streamId);
		};

		// This, not LiveChannelPool.release(), is where MediaPackage
		// cleanup for this stream's slot actually belongs - see
		// LiveChannelPool.confirmStopped's own javadoc for why. Gated on
		// applied too: a redelivered STOPPED callback that no-ops here
		// shouldn't re-trigger a reset that's already been done (harmless
		// either way, since ResetOriginEndpointState is idempotent, but
		// there's no reason to make the redundant call).
		if (applied && request.status() == LiveCallbackStatus.STOPPED) {
			channelPoolsByMode.get(stream.orElseThrow().getIngestMode()).confirmStopped(streamId);
		}

		// EventBridge is at-least-once, so a redelivered callback is
		// expected occasionally, not an error - each CAS write already made
		// it a no-op. Logged only so a real duplicate is visible in
		// CloudWatch rather than silently invisible.
		if (!applied) {
			log.info("Ignored live callback for stream {}: no longer in the expected prior status "
					+ "(already applied)", streamId);
		}
	}

}

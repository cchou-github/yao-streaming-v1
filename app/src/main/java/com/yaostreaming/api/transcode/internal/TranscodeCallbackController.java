package com.yaostreaming.api.transcode.internal;

import com.yaostreaming.api.transcode.VideoStatusTransitions;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * How the AWS transcode pipeline reports a finished job back into the app —
 * the one piece of application code the pipeline needs beyond infrastructure
 * itself (submit/complete are Lambdas, encoding settings are
 * Terraform-managed).
 *
 * <p>Nested under {@code api.transcode} rather than promoted to a top-level
 * package: this endpoint is specific to the transcode feature, not a
 * cross-cutting concern the way {@code api.security} is. If another feature
 * (e.g. live streaming) later needs its own internal, Lambda-facing
 * endpoint, it gets its own {@code api.<feature>.internal} the same way,
 * rather than sharing one audience-based package across unrelated features.
 *
 * <p>Reachable only via the internal ALB, itself security-group-restricted to
 * the completion Lambda's own security group (see {@code SecurityConfig} and
 * {@code terraform/internal-alb.tf}) — the network path is the actual
 * authorization gate here, not anything in this class.
 */
@RestController
@RequestMapping("/internal/transcode")
public class TranscodeCallbackController {

	private static final Logger log = LoggerFactory.getLogger(TranscodeCallbackController.class);

	private final VideoStatusTransitions statusTransitions;

	public TranscodeCallbackController(VideoStatusTransitions statusTransitions) {
		this.statusTransitions = statusTransitions;
	}

	@PostMapping("/callback")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void handleCallback(@Valid @RequestBody TranscodeCallbackRequest request) {
		boolean applied = switch (request.status()) {
			case READY -> statusTransitions.completeFromUpload(request.videoId(), request.outputKey(),
					request.durationSeconds());
			case FAILED -> {
				statusTransitions.markFailed(request.videoId());
				yield true;
			}
		};

		// EventBridge is at-least-once, so a redelivered callback is expected
		// occasionally, not an error - completeFromUpload's CAS already made it
		// a no-op. Logged only so a real duplicate is visible in CloudWatch
		// rather than silently invisible.
		if (!applied) {
			log.info("Ignored transcode callback for video {}: no longer in UPLOADING (already completed)",
					request.videoId());
		}
	}

}

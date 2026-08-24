package com.yaostreaming.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Triggered by an EventBridge rule matching MediaConvert's
 * "Job State Change" events (source {@code aws.mediaconvert}, detail.status
 * in {@code COMPLETE}/{@code ERROR} - see {@code terraform/lambda.tf}).
 *
 * <p>No typed event class exists for this in {@code aws-lambda-java-events}
 * (the {@code detail} schema is service-specific, unlike S3's well-known
 * event shape), so this takes the event as a raw {@code Map} - Lambda's
 * runtime deserializes JSON into nested Maps/Lists automatically when the
 * handler's input type is {@code Map}.
 */
public class CompleteTranscodeHandler implements RequestHandler<Map<String, Object>, Void> {

	private final HttpClient httpClient;
	private final ObjectMapper objectMapper;
	private final String callbackUrl;

	public CompleteTranscodeHandler() {
		this(HttpClient.newHttpClient(), new ObjectMapper(), System.getenv("CALLBACK_URL"));
	}

	CompleteTranscodeHandler(HttpClient httpClient, ObjectMapper objectMapper, String callbackUrl) {
		this.httpClient = httpClient;
		this.objectMapper = objectMapper;
		this.callbackUrl = callbackUrl;
	}

	@Override
	@SuppressWarnings("unchecked")
	public Void handleRequest(Map<String, Object> event, Context context) {
		LambdaLogger logger = context.getLogger();

		Map<String, Object> detail = (Map<String, Object>) event.get("detail");
		String jobStatus = (String) detail.get("status");
		Map<String, Object> userMetadata = (Map<String, Object>) detail.get("userMetadata");
		Long videoId = Long.valueOf((String) userMetadata.get("videoId"));

		CallbackPayload payload = "COMPLETE".equals(jobStatus)
				? new CallbackPayload(videoId, "READY", "videos/" + videoId + "/master.m3u8",
						durationSecondsOf(detail))
				: new CallbackPayload(videoId, "FAILED", null, null);

		try {
			String body = objectMapper.writeValueAsString(payload);
			HttpResponse<String> response = httpClient.send(
					HttpRequest.newBuilder(URI.create(callbackUrl))
							.timeout(Duration.ofSeconds(10))
							.header("Content-Type", "application/json")
							.POST(HttpRequest.BodyPublishers.ofString(body))
							.build(),
					HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 204) {
				// A non-204 here means the app rejected the callback (e.g. bad
				// payload) - not something retrying this same request would fix,
				// but worth being loud about in CloudWatch rather than silently
				// leaving the video stuck in UPLOADING.
				logger.log("Callback for video " + videoId + " returned " + response.statusCode() + ": "
						+ response.body());
			}
		}
		catch (Exception ex) {
			// EventBridge retries a failed (throwing) invocation, so an actual
			// network blip against the internal ALB gets a real second chance -
			// unlike a non-204 response above, which EventBridge would also
			// retry uselessly since the app already made its decision.
			throw new RuntimeException("Failed to POST transcode callback for video " + videoId, ex);
		}

		return null;
	}

	@SuppressWarnings("unchecked")
	private static Integer durationSecondsOf(Map<String, Object> detail) {
		Object rawGroups = detail.get("outputGroupDetails");
		if (!(rawGroups instanceof List<?> groups) || groups.isEmpty()) {
			return null;
		}
		Map<String, Object> firstGroup = (Map<String, Object>) groups.get(0);
		Object rawOutputs = firstGroup.get("outputDetails");
		if (!(rawOutputs instanceof List<?> outputs) || outputs.isEmpty()) {
			return null;
		}
		Map<String, Object> firstOutput = (Map<String, Object>) outputs.get(0);
		Object durationInMs = firstOutput.get("durationInMs");
		return durationInMs instanceof Number number ? (int) (number.longValue() / 1000) : null;
	}

	private record CallbackPayload(Long videoId, String status, String outputKey, Integer durationSeconds) {
	}

}

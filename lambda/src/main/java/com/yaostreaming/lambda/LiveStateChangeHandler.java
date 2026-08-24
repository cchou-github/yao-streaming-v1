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
import java.util.Map;

/**
 * Triggered by an EventBridge rule matching MediaLive's "Channel State
 * Change" events (source {@code aws.medialive}, {@code detail.state} in
 * {@code RUNNING}/{@code STOPPED} - see {@code terraform/lambda.tf}). Same
 * no-typed-event-class situation {@link CompleteTranscodeHandler} documents,
 * for the same reason: the {@code detail} schema is service-specific.
 *
 * <p>Genuinely simpler than the transcode handler: MediaLive's event already
 * carries {@code detail.channel_arn}, so there's no {@code userMetadata}
 * convention to invent the way the video id needed one.
 *
 * <p>An earlier version of this javadoc claimed a stopped channel reports
 * {@code IDLE} here, reasoning from {@code DescribeChannel}'s {@code
 * ChannelState} enum (which genuinely has no {@code STOPPED} value).
 * That conflated two unrelated vocabularies: this event's {@code
 * detail.state} is a plain, unconstrained string with its own values, not
 * tied to {@code ChannelState} at all. Proven live with a temporary
 * catch-all EventBridge rule logging every real transition: MediaLive
 * actually emits {@code RUNNING}, then {@code STOPPING}, then {@code
 * STOPPED} for a normal stop - never {@code IDLE} on this event type,
 * despite {@code DescribeChannel} reporting {@code IDLE} for the exact
 * same channel at the exact same moment. The original {@code IDLE} filter
 * silently dropped every stop-completion event before it ever reached
 * this handler.
 */
public class LiveStateChangeHandler implements RequestHandler<Map<String, Object>, Void> {

	private final HttpClient httpClient;
	private final ObjectMapper objectMapper;
	private final String callbackUrl;

	public LiveStateChangeHandler() {
		this(HttpClient.newHttpClient(), new ObjectMapper(), System.getenv("CALLBACK_URL"));
	}

	LiveStateChangeHandler(HttpClient httpClient, ObjectMapper objectMapper, String callbackUrl) {
		this.httpClient = httpClient;
		this.objectMapper = objectMapper;
		this.callbackUrl = callbackUrl;
	}

	@Override
	@SuppressWarnings("unchecked")
	public Void handleRequest(Map<String, Object> event, Context context) {
		LambdaLogger logger = context.getLogger();

		Map<String, Object> detail = (Map<String, Object>) event.get("detail");
		String channelArn = (String) detail.get("channel_arn");
		String state = (String) detail.get("state");
		String callbackStatus = "RUNNING".equals(state) ? "RUNNING" : "STOPPED";

		CallbackPayload payload = new CallbackPayload(channelArn, callbackStatus);

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
				// A non-204 here means the app rejected the callback - not
				// something retrying this same request would fix, but worth
				// being loud about in CloudWatch rather than silently
				// leaving the stream stuck in its prior status.
				logger.log("Callback for channel " + channelArn + " returned " + response.statusCode() + ": "
						+ response.body());
			}
		}
		catch (Exception ex) {
			// EventBridge retries a failed (throwing) invocation, so an
			// actual network blip against the internal ALB gets a real
			// second chance - unlike a non-204 response above, which
			// EventBridge would also retry uselessly since the app already
			// made its decision.
			throw new RuntimeException("Failed to POST live state callback for channel " + channelArn, ex);
		}

		return null;
	}

	private record CallbackPayload(String channelId, String status) {
	}

}

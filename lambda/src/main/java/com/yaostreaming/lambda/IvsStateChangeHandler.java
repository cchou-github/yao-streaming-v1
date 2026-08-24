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
 * Triggered by an EventBridge rule matching IVS's "Stream State Change"
 * events (source {@code aws.ivs} - see {@code terraform/lambda.tf}), the
 * browser/WebRTC go-live path's counterpart to {@link LiveStateChangeHandler}.
 * A separate class rather than a shared handler branching on
 * {@code event.source}: IVS's event shape genuinely differs from
 * MediaLive's, not just in field names - the channel's own ARN lives in the
 * envelope's top-level {@code resources[0]}, not inside {@code detail} the
 * way MediaLive's {@code detail.channel_arn} does, and the state signal is
 * {@code detail.event_name} ({@code "Stream Start"}/{@code "Stream End"}),
 * a completely different vocabulary from MediaLive's {@code detail.state}.
 * Trying to unify the two into one handler would mean more branching than
 * the two handlers save in shared code.
 *
 * <p>The rule's own {@code event_pattern} filters {@code detail.event_name}
 * down to {@code Stream Start}/{@code Stream End} before this ever runs, so
 * unlike {@link LiveStateChangeHandler}'s ternary (which has to turn
 * "anything that isn't RUNNING" into STOPPED, since its rule only admits
 * two values already), this only ever sees the two values it maps.
 */
public class IvsStateChangeHandler implements RequestHandler<Map<String, Object>, Void> {

	private final HttpClient httpClient;
	private final ObjectMapper objectMapper;
	private final String callbackUrl;

	public IvsStateChangeHandler() {
		this(HttpClient.newHttpClient(), new ObjectMapper(), System.getenv("CALLBACK_URL"));
	}

	IvsStateChangeHandler(HttpClient httpClient, ObjectMapper objectMapper, String callbackUrl) {
		this.httpClient = httpClient;
		this.objectMapper = objectMapper;
		this.callbackUrl = callbackUrl;
	}

	@Override
	@SuppressWarnings("unchecked")
	public Void handleRequest(Map<String, Object> event, Context context) {
		LambdaLogger logger = context.getLogger();

		List<String> resources = (List<String>) event.get("resources");
		String channelArn = resources.get(0);
		Map<String, Object> detail = (Map<String, Object>) event.get("detail");
		String eventName = (String) detail.get("event_name");
		String callbackStatus = "Stream Start".equals(eventName) ? "RUNNING" : "STOPPED";

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

package com.yaostreaming.lambda;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Uses a real embedded {@link HttpServer} rather than mocking
 * {@link HttpClient} - same reasoning {@code CompleteTranscodeHandlerTest}
 * documents: capturing what {@code HttpRequest} actually sent over the wire
 * is awkward to introspect after the fact, and this exercises real JSON
 * serialization instead of trusting it.
 */
@ExtendWith(MockitoExtension.class)
class LiveStateChangeHandlerTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final List<String> receivedBodies = new CopyOnWriteArrayList<>();

	private HttpServer server;

	private LiveStateChangeHandler handler;

	private volatile int responseStatus = 204;

	@Mock
	private Context context;

	@BeforeEach
	void setUp() throws IOException {
		server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		server.createContext("/internal/live/callback", exchange -> {
			receivedBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			exchange.sendResponseHeaders(responseStatus, -1);
			exchange.close();
		});
		server.start();

		String url = "http://localhost:" + server.getAddress().getPort() + "/internal/live/callback";
		handler = new LiveStateChangeHandler(HttpClient.newHttpClient(), objectMapper, url);
		when(context.getLogger()).thenReturn(new NoOpLogger());
	}

	@AfterEach
	void tearDown() {
		server.stop(0);
	}

	@Test
	void postsARunningCallbackForARunningChannel() throws Exception {
		handler.handleRequest(channelStateEvent("RUNNING", "arn:aws:medialive:us-east-1:1:channel:1"), context);

		assertThat(receivedBodies).hasSize(1);
		JsonNode body = objectMapper.readTree(receivedBodies.get(0));
		assertThat(body.get("channelId").asText()).isEqualTo("arn:aws:medialive:us-east-1:1:channel:1");
		assertThat(body.get("status").asText()).isEqualTo("RUNNING");
	}

	@Test
	void postsAStoppedCallbackForAnIdleChannel() throws Exception {
		handler.handleRequest(channelStateEvent("IDLE", "arn:aws:medialive:us-east-1:1:channel:2"), context);

		assertThat(receivedBodies).hasSize(1);
		JsonNode body = objectMapper.readTree(receivedBodies.get(0));
		assertThat(body.get("channelId").asText()).isEqualTo("arn:aws:medialive:us-east-1:1:channel:2");
		assertThat(body.get("status").asText()).isEqualTo("STOPPED");
	}

	@Test
	void throwsWhenTheCallbackCannotBeReached() {
		server.stop(0);

		assertThrows(RuntimeException.class,
				() -> handler.handleRequest(channelStateEvent("RUNNING", "arn:aws:medialive:us-east-1:1:channel:1"),
						context));
	}

	private static Map<String, Object> channelStateEvent(String state, String channelArn) {
		Map<String, Object> detail = new HashMap<>();
		detail.put("state", state);
		detail.put("channel_arn", channelArn);
		return Map.of("detail", detail);
	}

	private static final class NoOpLogger implements LambdaLogger {
		@Override
		public void log(String message) {
		}

		@Override
		public void log(byte[] message) {
		}
	}

}

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

/** Mirrors {@link LiveStateChangeHandlerTest}'s shape exactly, adapted for IVS's different event structure. */
@ExtendWith(MockitoExtension.class)
class IvsStateChangeHandlerTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final List<String> receivedBodies = new CopyOnWriteArrayList<>();

	private HttpServer server;

	private IvsStateChangeHandler handler;

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
		handler = new IvsStateChangeHandler(HttpClient.newHttpClient(), objectMapper, url);
		when(context.getLogger()).thenReturn(new NoOpLogger());
	}

	@AfterEach
	void tearDown() {
		server.stop(0);
	}

	@Test
	void postsARunningCallbackForAStreamStartEvent() throws Exception {
		handler.handleRequest(streamStateEvent("Stream Start", "arn:aws:ivs:us-east-1:1:channel/abc"), context);

		assertThat(receivedBodies).hasSize(1);
		JsonNode body = objectMapper.readTree(receivedBodies.get(0));
		assertThat(body.get("channelId").asText()).isEqualTo("arn:aws:ivs:us-east-1:1:channel/abc");
		assertThat(body.get("status").asText()).isEqualTo("RUNNING");
	}

	@Test
	void postsAStoppedCallbackForAStreamEndEvent() throws Exception {
		handler.handleRequest(streamStateEvent("Stream End", "arn:aws:ivs:us-east-1:1:channel/def"), context);

		assertThat(receivedBodies).hasSize(1);
		JsonNode body = objectMapper.readTree(receivedBodies.get(0));
		assertThat(body.get("channelId").asText()).isEqualTo("arn:aws:ivs:us-east-1:1:channel/def");
		assertThat(body.get("status").asText()).isEqualTo("STOPPED");
	}

	@Test
	void throwsWhenTheCallbackCannotBeReached() {
		server.stop(0);

		assertThrows(RuntimeException.class,
				() -> handler.handleRequest(streamStateEvent("Stream Start", "arn:aws:ivs:us-east-1:1:channel/abc"),
						context));
	}

	private static Map<String, Object> streamStateEvent(String eventName, String channelArn) {
		Map<String, Object> detail = new HashMap<>();
		detail.put("event_name", eventName);
		Map<String, Object> event = new HashMap<>();
		event.put("resources", List.of(channelArn));
		event.put("detail", detail);
		return event;
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

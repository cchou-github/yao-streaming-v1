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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Uses a real embedded {@link HttpServer} rather than mocking
 * {@link HttpClient}: capturing what {@code HttpRequest} actually sent over
 * the wire is awkward to introspect after the fact, and this exercises real
 * JSON serialization instead of trusting it.
 */
@ExtendWith(MockitoExtension.class)
class CompleteTranscodeHandlerTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final List<String> receivedBodies = new CopyOnWriteArrayList<>();

	private HttpServer server;

	private CompleteTranscodeHandler handler;

	private volatile int responseStatus = 204;

	@Mock
	private Context context;

	@BeforeEach
	void setUp() throws IOException {
		server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		server.createContext("/internal/transcode/callback", exchange -> {
			receivedBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			exchange.sendResponseHeaders(responseStatus, -1);
			exchange.close();
		});
		server.start();

		String url = "http://localhost:" + server.getAddress().getPort() + "/internal/transcode/callback";
		handler = new CompleteTranscodeHandler(HttpClient.newHttpClient(), objectMapper, url);
		when(context.getLogger()).thenReturn(new NoOpLogger());
	}

	@AfterEach
	void tearDown() {
		server.stop(0);
	}

	@Test
	void postsAReadyCallbackWithOutputKeyAndDuration() throws Exception {
		handler.handleRequest(mediaConvertEvent("COMPLETE", "1", 3500L), context);

		assertThat(receivedBodies).hasSize(1);
		JsonNode body = objectMapper.readTree(receivedBodies.get(0));
		assertThat(body.get("videoId").asLong()).isEqualTo(1L);
		assertThat(body.get("status").asText()).isEqualTo("READY");
		assertThat(body.get("outputKey").asText()).isEqualTo("videos/1/master.m3u8");
		assertThat(body.get("durationSeconds").asInt()).isEqualTo(3);
	}

	@Test
	void postsAFailedCallbackWithNoOutputKeyOrDuration() throws Exception {
		handler.handleRequest(mediaConvertEvent("ERROR", "2", null), context);

		assertThat(receivedBodies).hasSize(1);
		JsonNode body = objectMapper.readTree(receivedBodies.get(0));
		assertThat(body.get("videoId").asLong()).isEqualTo(2L);
		assertThat(body.get("status").asText()).isEqualTo("FAILED");
		assertThat(body.get("outputKey").isNull()).isTrue();
		assertThat(body.get("durationSeconds").isNull()).isTrue();
	}

	@Test
	void throwsWhenTheCallbackCannotBeReached() {
		server.stop(0);

		assertThrows(RuntimeException.class,
				() -> handler.handleRequest(mediaConvertEvent("COMPLETE", "1", 1000L), context));
	}

	private static Map<String, Object> mediaConvertEvent(String status, String videoId, Long durationInMs) {
		Map<String, Object> detail = new HashMap<>();
		detail.put("status", status);
		detail.put("userMetadata", Map.of("videoId", videoId));
		if (durationInMs != null) {
			detail.put("outputGroupDetails", List.of(
					Map.of("outputDetails", List.of(Map.of("durationInMs", durationInMs)))));
		}
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

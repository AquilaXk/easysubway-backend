package com.easysubway.datapack.adapter.out.github;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.datapack.application.port.out.DatapackWorkflowDispatchPort.DispatchCommand;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("GithubWorkflowDispatchAdapter")
class GithubWorkflowDispatchAdapterTest {

	private HttpServer server;
	private final AtomicReference<String> capturedAuth = new AtomicReference<>();
	private final AtomicReference<String> capturedBody = new AtomicReference<>();

	@AfterEach
	void tearDown() {
		if (server != null) {
			server.stop(0);
		}
	}

	private String startServer(int statusCode) throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", (HttpExchange exchange) -> {
			capturedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
			capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			exchange.sendResponseHeaders(statusCode, -1);
			exchange.close();
		});
		server.start();
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	private DispatchCommand command() {
		return new DispatchCommand("production", "release-request-1", "tools/datapack/build-spec.json");
	}

	@Test
	@DisplayName("토큰 미설정이면 dispatch를 skip한다(안전 기본값)")
	void skipsWhenTokenBlank() throws IOException {
		String base = startServer(204);
		var adapter = new GithubWorkflowDispatchAdapter(HttpClient.newHttpClient(), base, "");

		var result = adapter.dispatch(command());

		assertThat(result.skipped()).isTrue();
		assertThat(result.ok()).isFalse();
		assertThat(capturedBody.get()).isNull(); // 서버에 요청 안 감
	}

	@Test
	@DisplayName("204면 ok, Authorization 헤더와 inputs 4필드를 담아 POST한다")
	void dispatchesWithInputsAndAuth() throws IOException {
		String base = startServer(204);
		var adapter = new GithubWorkflowDispatchAdapter(HttpClient.newHttpClient(), base, "test-pat");

		var result = adapter.dispatch(command());

		assertThat(result.skipped()).isFalse();
		assertThat(result.ok()).isTrue();
		assertThat(capturedAuth.get()).isEqualTo("Bearer test-pat");
		assertThat(capturedBody.get())
			.contains("\"ref\":\"main\"")
			.contains("\"mode\":\"production-publish\"")
			.contains("\"targetChannel\":\"production\"")
			.contains("\"releaseRequestId\":\"release-request-1\"")
			.contains("\"buildSpecPath\":\"tools/datapack/build-spec.json\"");
	}

	@Test
	@DisplayName("production이 아닌 채널은 mode=exploratory로 보낸다(워크플로 production-publish 게이트 회피)")
	void nonProductionChannelUsesExploratoryMode() throws IOException {
		String base = startServer(204);
		var adapter = new GithubWorkflowDispatchAdapter(HttpClient.newHttpClient(), base, "test-pat");

		var result = adapter.dispatch(
			new DispatchCommand("staging", "release-request-2", "tools/datapack/build-spec.json"));

		assertThat(result.ok()).isTrue();
		assertThat(capturedBody.get())
			.contains("\"mode\":\"exploratory\"")
			.contains("\"targetChannel\":\"staging\"");
	}

	@Test
	@DisplayName("비2xx 응답이면 ok=false로 실패를 표현한다")
	void failsOnNon2xx() throws IOException {
		String base = startServer(500);
		var adapter = new GithubWorkflowDispatchAdapter(HttpClient.newHttpClient(), base, "test-pat");

		var result = adapter.dispatch(command());

		assertThat(result.skipped()).isFalse();
		assertThat(result.ok()).isFalse();
		assertThat(result.detail()).contains("500");
	}
}

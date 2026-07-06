package com.easysubway.datapack.adapter.out.github;

import com.easysubway.datapack.application.port.out.DatapackWorkflowDispatchPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 승인된 release request로 GitHub Actions workflow_dispatch를 트리거한다(TopisRealtimeProvider의
 * java.net.http.HttpClient 패턴). 토큰 미설정이면 skip = 자동화 dormant(안전 기본값).
 * 빌드·게시는 워크플로가 수행하며 backend는 dispatch만 한다(불변 전제).
 */
@Component
public class GithubWorkflowDispatchAdapter implements DatapackWorkflowDispatchPort {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);
	private static final String WORKFLOW_PATH =
		"/repos/AquilaXk/easysubway/actions/workflows/datapack-release.yml/dispatches";
	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private final HttpClient httpClient;
	private final String apiBaseUrl;
	private final String token;

	@org.springframework.beans.factory.annotation.Autowired
	public GithubWorkflowDispatchAdapter(
		@Value("${easysubway.datapack.github-actions-dispatch-token:}") String token,
		@Value("${easysubway.datapack.github-api-base-url:https://api.github.com}") String apiBaseUrl
	) {
		this(HttpClient.newBuilder().connectTimeout(TIMEOUT).build(), apiBaseUrl, token);
	}

	GithubWorkflowDispatchAdapter(HttpClient httpClient, String apiBaseUrl, String token) {
		this.httpClient = httpClient;
		this.apiBaseUrl = apiBaseUrl;
		this.token = token;
	}

	// mode는 targetChannel에서 도출한다. datapack-release.yml은 production-publish에 대해
	// targetChannel=production을 강제(아니면 워크플로 즉시 실패)하므로, production이 아닌 채널에
	// production-publish를 보내면 backend는 DISPATCHED로 기록하나 워크플로는 게이트에서 실패한다.
	// production이 아니면 비게이트 모드(exploratory)로 보낸다.
	// NOTE(Part C): production-publish는 androidEvidencePath·strictRouteRegressionPath·
	// releaseRequestPath 입력도 요구한다 — evidence 경로 배선은 Part C(콜백·게시 파이프라인)에서 채운다.
	private static String modeFor(String targetChannel) {
		return "production".equals(targetChannel) ? "production-publish" : "exploratory";
	}

	@Override
	public DispatchResult dispatch(DispatchCommand command) {
		if (token == null || token.isBlank()) {
			return DispatchResult.skippedResult();
		}
		String body;
		try {
			body = OBJECT_MAPPER.writeValueAsString(Map.of(
				"ref", "main",
				"inputs", Map.of(
					"mode", modeFor(command.targetChannel()),
					"targetChannel", command.targetChannel(),
					"releaseRequestId", command.releaseRequestId(),
					"buildSpecPath", command.buildSpecPath())));
		} catch (IOException e) {
			return DispatchResult.failed("dispatch payload error");
		}
		HttpRequest request = HttpRequest.newBuilder(URI.create(apiBaseUrl + WORKFLOW_PATH))
			.timeout(TIMEOUT)
			.header("Authorization", "Bearer " + token)
			.header("Accept", "application/vnd.github+json")
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
			.build();
		try {
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			int statusCode = response.statusCode();
			if (statusCode >= 200 && statusCode < 300) {
				return DispatchResult.succeeded("dispatched HTTP " + statusCode);
			}
			return DispatchResult.failed("dispatch HTTP " + statusCode);
		} catch (IOException e) {
			return DispatchResult.failed("dispatch IO error: " + e.getClass().getSimpleName());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return DispatchResult.failed("dispatch interrupted");
		}
	}
}

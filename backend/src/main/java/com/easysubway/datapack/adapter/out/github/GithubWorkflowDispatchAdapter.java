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
 * GitHub Actions workflow_dispatch 어댑터(TopisRealtimeProvider의 java.net.http.HttpClient 패턴).
 * 토큰 미설정이면 skip = 자동화 dormant(안전 기본값).
 *
 * <p>#2564로 release request 승인의 dispatch 발화가 제거되어 application 계층에는 이 포트의
 * 소비자가 없다 — 릴리스 실행 권위는 git 파일(release-request.json PR 병합)과 GitHub Environment
 * required reviewer가 가진다. 이 어댑터는 outbound integration 카탈로그 계약이 고정하고 있어
 * 유지하며, 제거는 카탈로그·CI 계약과 함께 별도로 다룬다.
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
			String modeArgs = OBJECT_MAPPER.writeValueAsString(Map.of(
				"buildSpecPath", command.buildSpecPath(),
				"releaseRequestId", command.releaseRequestId()));
			body = OBJECT_MAPPER.writeValueAsString(Map.of(
				"ref", "main",
				"inputs", Map.of(
					"mode", modeFor(command.targetChannel()),
					"targetChannel", command.targetChannel(),
					"modeArgs", modeArgs)));
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

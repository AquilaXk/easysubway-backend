package com.easysubway.journey.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.journey.application.ServiceDayResolver;
import com.easysubway.route.application.service.CapitalEndpointFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("부하·장애 시나리오 서버: 데이터팩 교체와 시설 상태 장애를 요청 중에 주입한다")
class JourneyLoadServerTest {

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final String SHA_A = "a".repeat(64);
	private static final String SHA_B = "b".repeat(64);
	private static final int REPLACEMENT_SHIFT_SECONDS = 60;
	private static final int CONCURRENT_REQUESTS = 160;
	private static final int CONCURRENT_CLIENTS = 4;

	private static JourneyLoadServer server;
	private static HttpClient client;
	private static CapitalEndpointFixture.RepresentativeCase pair;

	@BeforeAll
	static void startServer() {
		server = JourneyLoadServer.start();
		client = HttpClient.newHttpClient();
		pair = CapitalEndpointFixture.representativeProfileCases().getFirst();
	}

	@AfterAll
	static void stopServer() {
		server.close();
	}

	@BeforeEach
	void healthyAndFirstBundle() throws Exception {
		control("facility/up");
		control("bundle/a");
	}

	@Test
	@DisplayName("시작하면 첫 번째 팩이 활성이고 질의 응답이 그 팩의 식별자를 싣는다")
	void servesTheFirstBundleAfterStart() throws Exception {
		var response = search(stepFreeRequest("NONE"));

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(JSON.readTree(response.body()).path("sourceIdentity").path("routeBundleSha256").asText()).isEqualTo(SHA_A);
	}

	@Test
	@DisplayName("팩을 교체하면 응답의 팩 식별자와 운행 시각이 함께 바뀐다")
	void replacingTheBundleChangesIdentityAndTimesTogether() throws Exception {
		var before = JSON.readTree(search(stepFreeRequest("NONE")).body());
		control("bundle/b");
		var after = JSON.readTree(search(stepFreeRequest("NONE")).body());

		assertThat(after.path("sourceIdentity").path("routeBundleSha256").asText()).isEqualTo(SHA_B);
		assertThat(after.path("sourceIdentity").path("timetableSnapshotId").asText())
			.isNotEqualTo(before.path("sourceIdentity").path("timetableSnapshotId").asText());
		assertThat(after.path("journeys").toString()).isNotEqualTo(before.path("journeys").toString());
	}

	@Test
	@DisplayName("시설 상태 원천이 멈추면 계단 없는 필수 요청은 FACILITY_STATUS_UNAVAILABLE 503으로 드러난다")
	void facilityOutageSurfacesAsAnExplicitFailureForStepFreeRequests() throws Exception {
		control("facility/down");

		var response = search(stepFreeRequest("REQUIRE_STEP_FREE"));

		assertThat(response.statusCode()).isEqualTo(503);
		assertThat(JSON.readTree(response.body()).path("code").asText()).isEqualTo("FACILITY_STATUS_UNAVAILABLE");
	}

	@Test
	@DisplayName("시설 상태 원천이 멈춘 동안의 일반 요청은 시설 상태 미적용을 응답에 밝힌다")
	void facilityOutageIsDisclosedOnOrdinaryRequests() throws Exception {
		assertThat(facilityStatus(search(stepFreeRequest("NONE")))).isEqualTo("APPLIED");

		control("facility/down");
		assertThat(facilityStatus(search(stepFreeRequest("NONE")))).isEqualTo("UNOBSERVED");

		control("facility/up");
		assertThat(facilityStatus(search(stepFreeRequest("NONE")))).isEqualTo("APPLIED");
		assertThat(search(stepFreeRequest("REQUIRE_STEP_FREE")).statusCode()).as("복구 뒤에는 503이 아니다").isNotEqualTo(503);
	}

	@Test
	@DisplayName("알 수 없는 팩·시설 상태 이름은 거부한다")
	void rejectsUnknownControlTargets() throws Exception {
		assertThat(post("/__load/bundle/c").statusCode()).isEqualTo(400);
		assertThat(post("/__load/facility/sideways").statusCode()).isEqualTo(400);
	}

	@Test
	@DisplayName("고정 입력을 알려 준다: 역 목록, 서비스일, 두 팩의 식별자")
	void describesTheFixture() throws Exception {
		var fixture = JSON.readTree(get("/__load/fixture").body());

		assertThat(fixture.path("serviceDate").asText()).isEqualTo(CapitalEndpointFixture.serviceDate().toString());
		assertThat(fixture.path("stations")).hasSize(CapitalEndpointFixture.stations().size());
		assertThat(fixture.path("bundles").path("a").asText()).isEqualTo(SHA_A);
		assertThat(fixture.path("bundles").path("b").asText()).isEqualTo(SHA_B);
		assertThat(fixture.path("replacementShiftSeconds").asInt()).isEqualTo(REPLACEMENT_SHIFT_SECONDS);
	}

	@Test
	@DisplayName("요청이 계속 들어오는 중 팩을 여러 번 교체해도 모든 응답은 한 팩의 단독 응답과 같다")
	void responsesDuringRepeatedReplacementNeverMixBundles() throws Exception {
		String body = stepFreeRequest("NONE");
		Set<String> isolated = new HashSet<>();
		isolated.add(summary(search(body)));
		control("bundle/b");
		isolated.add(summary(search(body)));
		control("bundle/a");
		assertThat(isolated).as("두 팩의 단독 응답이 서로 달라야 섞임을 가려낼 수 있다").hasSize(2);

		ExecutorService clients = Executors.newFixedThreadPool(CONCURRENT_CLIENTS);
		try {
			List<Future<String>> responses = new ArrayList<>();
			Callable<String> request = () -> summary(search(body));
			for (int index = 0; index < CONCURRENT_REQUESTS; index += 1) responses.add(clients.submit(request));
			for (String label : List.of("b", "a", "b", "a", "b", "a")) control("bundle/" + label);
			for (Future<String> response : responses) {
				assertThat(isolated).as("응답은 어느 한 팩의 단독 응답이어야 한다").contains(response.get(1, TimeUnit.MINUTES));
			}
		} finally {
			clients.shutdownNow();
		}
	}

	private static String summary(HttpResponse<String> response) throws Exception {
		JsonNode body = JSON.readTree(response.body());
		return response.statusCode() + "|" + body.path("sourceIdentity") + "|" + body.path("journeys");
	}

	private static String facilityStatus(HttpResponse<String> response) throws Exception {
		assertThat(response.statusCode()).isEqualTo(200);
		return JSON.readTree(response.body()).path("stairFreeAlternative").path("facilityStatus").asText();
	}

	private static String stepFreeRequest(String constraintMode) {
		Instant at = CapitalEndpointFixture.serviceDate().atTime(8, 0).atZone(ServiceDayResolver.ZONE).toInstant();
		return """
			{"requestId":"01K1Y000000000000000000000","originStationId":"%s","destinationStationId":"%s",
			"departure":{"mode":"SCHEDULED","requestedAt":"%s"},"timePolicy":"TIMETABLE_REQUIRED","walkingPace":"STANDARD",
			"mobilityProfile":"STEP_FREE","constraintMode":"%s","maxTransfers":3,"alternativeCount":3}
			""".formatted(pair.origin(), pair.destination(), at, constraintMode);
	}

	private static HttpResponse<String> search(String body) throws Exception {
		var request = HttpRequest.newBuilder(uri("/api/v3/journeys/search"))
			.header("Authorization", "Bearer load-session").header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body)).build();
		return client.send(request, HttpResponse.BodyHandlers.ofString());
	}

	private static void control(String target) throws Exception {
		assertThat(post("/__load/" + target).statusCode()).isEqualTo(200);
	}

	private static HttpResponse<String> post(String path) throws Exception {
		return client.send(HttpRequest.newBuilder(uri(path)).POST(HttpRequest.BodyPublishers.noBody()).build(),
			HttpResponse.BodyHandlers.ofString());
	}

	private static HttpResponse<String> get(String path) throws Exception {
		return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
	}

	private static URI uri(String path) {
		return URI.create("http://127.0.0.1:" + server.port() + path);
	}
}

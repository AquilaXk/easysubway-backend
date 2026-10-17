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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("부하·장애 시나리오 서버: 데이터팩 교체와 시설 상태 장애를 요청 중에 주입한다")
class JourneyLoadServerTest {

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final String SHA_A = "a".repeat(64);
	private static final String SHA_B = "b".repeat(64);
	private static final int REPLACEMENT_SHIFT_SECONDS = 60;
	private static final int CONCURRENT_REQUESTS = 160;
	private static final int CONCURRENT_CLIENTS = 4;
	private static final int ROUTABLE_PAIR_SEARCH_STATIONS = 60;
	private static final HttpClient CLIENT = HttpClient.newHttpClient();

	/** 시설 상태 필수 모드를 켠 구성: 시설 원천이 멈추면 계단 없는 필수 요청은 명시 503이다. */
	@Nested
	@DisplayName("시설 상태 필수 구성(required=true)")
	class RequiredConfiguration {

		private static JourneyLoadServer server;
		private static CapitalEndpointFixture.RepresentativeCase pair;

		@BeforeAll
		static void startServer() {
			server = JourneyLoadServer.start(true);
			pair = CapitalEndpointFixture.representativeProfileCases().getFirst();
		}

		@AfterAll
		static void stopServer() {
			server.close();
		}

		@BeforeEach
		void healthyAndFirstBundle() throws Exception {
			control(server, "facility/up");
			control(server, "bundle/a");
		}

		@Test
		@DisplayName("시작하면 첫 번째 팩이 활성이고 질의 응답이 그 팩의 식별자를 싣는다")
		void servesTheFirstBundleAfterStart() throws Exception {
			var response = search(server, request(pair.origin(), pair.destination(), "NONE"));

			assertThat(response.statusCode()).isEqualTo(200);
			assertThat(JSON.readTree(response.body()).path("sourceIdentity").path("routeBundleSha256").asText()).isEqualTo(SHA_A);
		}

		@Test
		@DisplayName("팩을 교체하면 응답의 팩 식별자와 운행 시각이 함께 바뀐다")
		void replacingTheBundleChangesIdentityAndTimesTogether() throws Exception {
			String body = request(pair.origin(), pair.destination(), "NONE");
			var before = JSON.readTree(search(server, body).body());
			control(server, "bundle/b");
			var after = JSON.readTree(search(server, body).body());

			assertThat(after.path("sourceIdentity").path("routeBundleSha256").asText()).isEqualTo(SHA_B);
			assertThat(after.path("sourceIdentity").path("timetableSnapshotId").asText())
				.isNotEqualTo(before.path("sourceIdentity").path("timetableSnapshotId").asText());
			assertThat(after.path("journeys").toString()).isNotEqualTo(before.path("journeys").toString());
		}

		@Test
		@DisplayName("시설 상태 원천이 멈추면 계단 없는 필수 요청은 FACILITY_STATUS_UNAVAILABLE 503으로 드러나고, 복구되면 멈추기 전과 같은 응답이 돌아온다")
		void facilityOutageSurfacesAsAnExplicitFailureAndRecoveryRestoresTheAnswer() throws Exception {
			String body = request(pair.origin(), pair.destination(), "REQUIRE_STEP_FREE");
			String healthy = answer(search(server, body));
			assertThat(healthy).as("정상일 때는 명시 실패가 아니다").doesNotStartWith("503");

			control(server, "facility/down");
			var outage = search(server, body);
			assertThat(outage.statusCode()).isEqualTo(503);
			assertThat(JSON.readTree(outage.body()).path("code").asText()).isEqualTo("FACILITY_STATUS_UNAVAILABLE");

			control(server, "facility/up");
			assertThat(answer(search(server, body))).as("복구 뒤 응답은 멈추기 전과 정확히 같다").isEqualTo(healthy);
		}

		@Test
		@DisplayName("시설 상태 원천이 멈춘 동안의 일반 요청은 시설 상태 미적용을 응답에 밝힌다")
		void facilityOutageIsDisclosedOnOrdinaryRequests() throws Exception {
			String body = request(pair.origin(), pair.destination(), "NONE");
			assertThat(facilityStatus(search(server, body))).isEqualTo("APPLIED");

			control(server, "facility/down");
			assertThat(facilityStatus(search(server, body))).isEqualTo("UNOBSERVED");

			control(server, "facility/up");
			assertThat(facilityStatus(search(server, body))).isEqualTo("APPLIED");
		}

		@Test
		@DisplayName("알 수 없는 팩·시설 상태 이름은 거부한다")
		void rejectsUnknownControlTargets() throws Exception {
			assertThat(post(server, "/__load/bundle/c").statusCode()).isEqualTo(400);
			assertThat(post(server, "/__load/facility/sideways").statusCode()).isEqualTo(400);
		}

		@Test
		@DisplayName("고정 입력을 알려 준다: 역 목록, 서비스일, 두 팩의 식별자")
		void describesTheFixture() throws Exception {
			var fixture = JSON.readTree(get(server, "/__load/fixture").body());

			assertThat(fixture.path("serviceDate").asText()).isEqualTo(CapitalEndpointFixture.serviceDate().toString());
			assertThat(fixture.path("stations")).hasSize(CapitalEndpointFixture.stations().size());
			assertThat(fixture.path("bundles").path("a").asText()).isEqualTo(SHA_A);
			assertThat(fixture.path("bundles").path("b").asText()).isEqualTo(SHA_B);
			assertThat(fixture.path("replacementShiftSeconds").asInt()).isEqualTo(REPLACEMENT_SHIFT_SECONDS);
		}

		@Test
		@DisplayName("요청이 계속 들어오는 중 팩을 여러 번 교체해도 모든 응답은 한 팩의 단독 응답과 같다")
		void responsesDuringRepeatedReplacementNeverMixBundles() throws Exception {
			String body = request(pair.origin(), pair.destination(), "NONE");
			Set<String> isolated = new HashSet<>();
			isolated.add(answer(search(server, body)));
			control(server, "bundle/b");
			isolated.add(answer(search(server, body)));
			control(server, "bundle/a");
			assertThat(isolated).as("두 팩의 단독 응답이 서로 달라야 섞임을 가려낼 수 있다").hasSize(2);

			ExecutorService clients = Executors.newFixedThreadPool(CONCURRENT_CLIENTS);
			try {
				List<Future<String>> responses = new ArrayList<>();
				Callable<String> send = () -> answer(search(server, body));
				for (int index = 0; index < CONCURRENT_REQUESTS; index += 1) responses.add(clients.submit(send));
				for (String label : List.of("b", "a", "b", "a", "b", "a")) control(server, "bundle/" + label);
				for (Future<String> response : responses) {
					assertThat(isolated).as("응답은 어느 한 팩의 단독 응답이어야 한다").contains(response.get(1, TimeUnit.MINUTES));
				}
			} finally {
				clients.shutdownNow();
			}
		}
	}

	/** 운영 기본 구성(required=false): 시설 원천이 멈춰도 모든 요청이 시설 상태 미적용을 밝힌 정상 응답이다. */
	@Nested
	@DisplayName("운영 기본 구성(required=false)")
	class ProductionDefaultConfiguration {

		private static JourneyLoadServer server;
		private static String[] routablePair;

		@BeforeAll
		static void startServer() throws Exception {
			server = JourneyLoadServer.start(false);
			routablePair = findStepFreeRoutablePair();
		}

		@AfterAll
		static void stopServer() {
			server.close();
		}

		@BeforeEach
		void healthy() throws Exception {
			control(server, "facility/up");
		}

		@Test
		@DisplayName("시설 상태 원천이 멈춰도 계단 없는 필수 요청은 같은 경로를 시설 상태 미적용으로 밝혀 돌려주고, 명시 실패가 아니다")
		void stepFreeRequiredRequestKeepsItsRouteAndDisclosesTheOutage() throws Exception {
			String body = request(routablePair[0], routablePair[1], "REQUIRE_STEP_FREE");
			var healthy = search(server, body);
			assertThat(healthy.statusCode()).isEqualTo(200);
			assertThat(facilityStatus(healthy)).isEqualTo("APPLIED");

			control(server, "facility/down");
			var outage = search(server, body);

			assertThat(outage.statusCode()).isEqualTo(200);
			assertThat(facilityStatus(outage)).isEqualTo("UNOBSERVED");
			assertThat(JSON.readTree(outage.body()).path("journeys")).isEqualTo(JSON.readTree(healthy.body()).path("journeys"));
		}

		@Test
		@DisplayName("일반 요청도 시설 상태 미적용을 밝히고, 복구되면 다시 적용으로 돌아온다")
		void ordinaryRequestDisclosesTheOutageAndRecovers() throws Exception {
			String body = request(routablePair[0], routablePair[1], "NONE");

			control(server, "facility/down");
			assertThat(facilityStatus(search(server, body))).isEqualTo("UNOBSERVED");

			control(server, "facility/up");
			assertThat(facilityStatus(search(server, body))).isEqualTo("APPLIED");
		}

		/** 정상 상태에서 계단 없는 필수 요청이 경로를 찾는 역 쌍. fixture에는 접근성 경로 자료가 적어 찾아서 쓴다. */
		private static String[] findStepFreeRoutablePair() throws Exception {
			List<String> stations = CapitalEndpointFixture.stations().subList(0, ROUTABLE_PAIR_SEARCH_STATIONS);
			for (String origin : stations) {
				for (String destination : stations) {
					if (origin.equals(destination)) continue;
					if (search(server, request(origin, destination, "REQUIRE_STEP_FREE")).statusCode() == 200) return new String[] {origin, destination};
				}
			}
			throw new AssertionError("fixture has no step-free routable pair among the first " + ROUTABLE_PAIR_SEARCH_STATIONS + " stations");
		}
	}

	/** 한 응답을 정확히 비교하는 요약: 상태, 기계 코드, 팩 식별자, 여정. 요청별 필드와 시설 상태는 뺀다. */
	private static String answer(HttpResponse<String> response) throws Exception {
		JsonNode body = JSON.readTree(response.body());
		return response.statusCode() + "|" + body.path("code").asText() + "|" + body.path("sourceIdentity") + "|" + body.path("journeys");
	}

	private static String facilityStatus(HttpResponse<String> response) throws Exception {
		assertThat(response.statusCode()).isEqualTo(200);
		return JSON.readTree(response.body()).path("stairFreeAlternative").path("facilityStatus").asText();
	}

	private static String request(String origin, String destination, String constraintMode) {
		Instant at = CapitalEndpointFixture.serviceDate().atTime(8, 0).atZone(ServiceDayResolver.ZONE).toInstant();
		return """
			{"requestId":"01K1Y000000000000000000000","originStationId":"%s","destinationStationId":"%s",
			"departure":{"mode":"SCHEDULED","requestedAt":"%s"},"timePolicy":"TIMETABLE_REQUIRED","walkingPace":"STANDARD",
			"mobilityProfile":"STEP_FREE","constraintMode":"%s","maxTransfers":3,"alternativeCount":3}
			""".formatted(origin, destination, at, constraintMode);
	}

	private static HttpResponse<String> search(JourneyLoadServer target, String body) throws Exception {
		var request = HttpRequest.newBuilder(uri(target, "/api/v3/journeys/search"))
			.header("Authorization", "Bearer load-session").header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body)).build();
		return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
	}

	private static void control(JourneyLoadServer target, String path) throws Exception {
		assertThat(post(target, "/__load/" + path).statusCode()).isEqualTo(200);
	}

	private static HttpResponse<String> post(JourneyLoadServer target, String path) throws Exception {
		return CLIENT.send(HttpRequest.newBuilder(uri(target, path)).POST(HttpRequest.BodyPublishers.noBody()).build(),
			HttpResponse.BodyHandlers.ofString());
	}

	private static HttpResponse<String> get(JourneyLoadServer target, String path) throws Exception {
		return CLIENT.send(HttpRequest.newBuilder(uri(target, path)).GET().build(), HttpResponse.BodyHandlers.ofString());
	}

	private static URI uri(JourneyLoadServer target, String path) {
		return URI.create("http://127.0.0.1:" + target.port() + path);
	}
}

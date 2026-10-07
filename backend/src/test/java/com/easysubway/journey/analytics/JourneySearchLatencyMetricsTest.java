package com.easysubway.journey.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("Journey V3 검색 지연 지표")
class JourneySearchLatencyMetricsTest {

	/** platform 경보가 참조하는 `le` 값과 같아야 하는 독립 기대값이다. 바꾸면 platform 경보 규칙도 함께 바꾼다. */
	private static final List<String> EXPECTED_BUCKET_BOUNDS = List.of("0.01", "0.025", "0.05", "0.1", "0.25", "0.5", "1.0", "2.5");

	@Test
	@DisplayName("4개 모드 x 4개 결과 시계열을 0건으로 미리 등록하고 태그는 mode·outcome뿐이다")
	void preRegistersEveryModeOutcomeSeriesWithOnlyLowCardinalityTags() {
		var meters = new SimpleMeterRegistry();
		new JourneySearchLatencyMetrics(meters);

		List<Timer> timers = meters.find(JourneySearchLatencyMetrics.METER).timers().stream().toList();
		assertThat(timers).hasSize(16).allSatisfy(timer -> assertThat(timer.count()).isZero());
		assertThat(timers).allSatisfy(timer -> assertThat(timer.getId().getTags())
			.extracting(Tag::getKey).containsExactlyInAnyOrder("mode", "outcome"));
		Set<String> series = timers.stream()
			.map(timer -> timer.getId().getTag("mode") + "/" + timer.getId().getTag("outcome"))
			.collect(Collectors.toSet());
		assertThat(series).hasSize(16).contains("depart_at/ok", "depart_between/no_route",
			"arrive_by/fail_closed", "last_connection/error");
	}

	@Test
	@DisplayName("모드 태그 값은 JourneySearchKind 4종의 소문자 이름과 일치한다")
	void modeTagValuesFollowSearchKinds() {
		var meters = new SimpleMeterRegistry();
		new JourneySearchLatencyMetrics(meters);

		Set<String> modes = meters.find(JourneySearchLatencyMetrics.METER).timers().stream()
			.map(timer -> timer.getId().getTag("mode")).collect(Collectors.toSet());
		assertThat(modes).containsExactlyInAnyOrder(java.util.Arrays.stream(JourneySearchKind.values())
			.map(kind -> kind.name().toLowerCase(Locale.ROOT)).toArray(String[]::new));
	}

	@Test
	@DisplayName("성공은 ok로, 시작 시각부터 지금까지 걸린 시간을 해당 모드 시계열에 한 건 더한다")
	void recordsElapsedTimeOnTheSuccessSeries() {
		var meters = new SimpleMeterRegistry();
		var latency = new JourneySearchLatencyMetrics(meters);

		latency.recordSuccess(JourneySearchKind.ARRIVE_BY, System.nanoTime() - 5_000_000L);

		Timer timer = meters.get(JourneySearchLatencyMetrics.METER)
			.tag("mode", "arrive_by").tag("outcome", "ok").timer();
		assertThat(timer.count()).isEqualTo(1);
		assertThat(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(5.0);
		assertThat(meters.get(JourneySearchLatencyMetrics.METER)
			.tag("mode", "depart_at").tag("outcome", "ok").timer().count()).isZero();
	}

	@ParameterizedTest(name = "{0} {1} -> {2}")
	@DisplayName("실패 응답은 경로 없음·의도적 fail-closed·오류로 나뉘고, 알 수 없는 조합은 오류로 센다")
	@CsvSource({
		"422,ROUTE_NOT_FOUND,no_route",
		"404,ROUTE_NOT_FOUND,error",
		"404,NO_LAST_CONNECTION,error",
		"422,ACCESSIBILITY_CONSTRAINT_UNSATISFIED,no_route",
		"422,NO_SERVICE_IN_DEPARTURE_WINDOW,no_route",
		"422,NO_ROUTE_ARRIVING_BY_DEADLINE,no_route",
		"422,NO_LAST_CONNECTION,no_route",
		"404,STATION_NOT_FOUND,fail_closed",
		"400,TEMPORAL_WINDOW_TOO_LARGE,fail_closed",
		"422,TEMPORAL_QUERY_TOO_COMPLEX,fail_closed",
		"422,REALTIME_NOT_APPLICABLE_TO_TEMPORAL_QUERY,fail_closed",
		"503,ROUTING_BUNDLE_UNAVAILABLE,fail_closed",
		"503,ROUTING_BUNDLE_STALE,fail_closed",
		"503,TIMETABLE_UNAVAILABLE,fail_closed",
		"503,TIMETABLE_STALE,fail_closed",
		"503,REALTIME_REQUIRED_UNAVAILABLE,fail_closed",
		"503,ROUTING_IDENTITY_MISMATCH,fail_closed",
		"503,FACILITY_STATUS_UNAVAILABLE,fail_closed",
		"503,ROUTE_SERVICE_UNAVAILABLE,error",
		"503,RAPTOR_FRONTIER_CAPACITY_EXCEEDED,error",
		"504,JOURNEY_SEARCH_TIMEOUT,error",
		"504,JOURNEY_PROFILE_TIMEOUT,error",
		"500,UNEXPECTED,error",
		"422,NEW_UNKNOWN_CODE,error"})
	void classifiesFailures(int httpStatus, String machineCode, String expected) {
		var meters = new SimpleMeterRegistry();
		var latency = new JourneySearchLatencyMetrics(meters);

		latency.recordFailure(JourneySearchKind.DEPART_BETWEEN, System.nanoTime(), httpStatus, machineCode);

		assertThat(meters.get(JourneySearchLatencyMetrics.METER)
			.tag("mode", "depart_between").tag("outcome", expected).timer().count()).isEqualTo(1);
		assertThat(meters.find(JourneySearchLatencyMetrics.METER).timers().stream().mapToLong(Timer::count).sum())
			.isEqualTo(1);
	}

	@Test
	@DisplayName("예상하지 못한 예외는 error 시계열에 한 건만 더한다")
	void recordsUnexpectedExceptionsAsError() {
		var meters = new SimpleMeterRegistry();
		var latency = new JourneySearchLatencyMetrics(meters);

		latency.recordError(JourneySearchKind.LAST_CONNECTION, System.nanoTime());

		assertThat(meters.get(JourneySearchLatencyMetrics.METER)
			.tag("mode", "last_connection").tag("outcome", "error").timer().count()).isEqualTo(1);
		assertThat(meters.find(JourneySearchLatencyMetrics.METER).timers().stream().mapToLong(Timer::count).sum())
			.isEqualTo(1);
	}

	/**
	 * 계약의 모든 (상태, 코드) 행은 이 표에 명시적으로 적혀 있어야 한다. 계약에 행이 늘거나 이름이 바뀌면 표와 어긋나 RED가
	 * 되므로 새 코드가 분류 없이 error로 흘러 들어가지 못한다. 값은 기대 outcome 태그이고, 모드를 알기 전에 끝나 기록하지 않는
	 * 요청 검증·인증·속도 제한 행은 {@value #UNRECORDED}다.
	 */
	private static final String UNRECORDED = "unrecorded";
	private static final Map<String, String> EXPECTED_BY_CONTRACT_ROW = Map.ofEntries(
		Map.entry("400 INVALID_JOURNEY_REQUEST", UNRECORDED),
		Map.entry("400 INVALID_TEMPORAL_QUERY", UNRECORDED),
		Map.entry("401 ROUTE_SESSION_REQUIRED", UNRECORDED),
		Map.entry("429 ROUTE_RATE_LIMITED", UNRECORDED),
		Map.entry("404 STATION_NOT_FOUND", "fail_closed"),
		Map.entry("400 TEMPORAL_WINDOW_TOO_LARGE", "fail_closed"),
		Map.entry("422 TEMPORAL_QUERY_TOO_COMPLEX", "fail_closed"),
		Map.entry("422 REALTIME_NOT_APPLICABLE_TO_TEMPORAL_QUERY", "fail_closed"),
		Map.entry("503 ROUTING_BUNDLE_UNAVAILABLE", "fail_closed"),
		Map.entry("503 ROUTING_BUNDLE_STALE", "fail_closed"),
		Map.entry("503 TIMETABLE_UNAVAILABLE", "fail_closed"),
		Map.entry("503 TIMETABLE_STALE", "fail_closed"),
		Map.entry("503 REALTIME_REQUIRED_UNAVAILABLE", "fail_closed"),
		Map.entry("503 ROUTING_IDENTITY_MISMATCH", "fail_closed"),
		Map.entry("503 FACILITY_STATUS_UNAVAILABLE", "fail_closed"),
		Map.entry("422 ROUTE_NOT_FOUND", "no_route"),
		Map.entry("422 ACCESSIBILITY_CONSTRAINT_UNSATISFIED", "no_route"),
		Map.entry("422 NO_SERVICE_IN_DEPARTURE_WINDOW", "no_route"),
		Map.entry("422 NO_ROUTE_ARRIVING_BY_DEADLINE", "no_route"),
		Map.entry("422 NO_LAST_CONNECTION", "no_route"),
		Map.entry("503 ROUTE_SERVICE_UNAVAILABLE", "error"),
		Map.entry("503 RAPTOR_FRONTIER_CAPACITY_EXCEEDED", "error"),
		Map.entry("504 JOURNEY_SEARCH_TIMEOUT", "error"),
		Map.entry("504 JOURNEY_PROFILE_TIMEOUT", "error"));

	@Test
	@DisplayName("검색·프로필 오류 계약의 모든 행이 명시적으로 분류되고 미분류 코드는 0개이다")
	void classifiesEveryRowOfTheErrorDispositionContract() throws Exception {
		JsonNode contract = new ObjectMapper().readTree(
			Path.of("..", "contracts", "api", "journey-v3-error-disposition.json").toFile());
		Set<String> rows = new TreeSet<>();
		for (JsonNode entry : contract.path("entries")) {
			String operation = entry.path("operation").asText();
			if (!operation.equals("searchJourneys") && !operation.equals("profileJourneys")) continue;
			rows.add(entry.path("httpStatus").asInt() + " " + entry.path("machineCode").asText());
		}

		assertThat(EXPECTED_BY_CONTRACT_ROW.keySet()).as("분류 표에만 남은 낡은 행").isSubsetOf(rows);
		assertThat(rows).as("분류 표에 없는 계약 행(미분류 코드)").isSubsetOf(EXPECTED_BY_CONTRACT_ROW.keySet());
		assertThat(EXPECTED_BY_CONTRACT_ROW.values()).as("표의 기대 outcome")
			.isSubsetOf("ok", "no_route", "fail_closed", "error", UNRECORDED);

		for (String row : rows) {
			String expected = EXPECTED_BY_CONTRACT_ROW.get(row);
			int status = Integer.parseInt(row.substring(0, 3));
			String code = row.substring(4);
			if (expected.equals(UNRECORDED)) {
				// 모드를 알기 전에 끝나는 행만 기록하지 않는다: 요청 검증(400 INVALID_*), 인증(401), 속도 제한(429).
				assertThat(status == 401 || status == 429 || code.startsWith("INVALID_"))
					.as("기록하지 않는 행 " + row).isTrue();
				continue;
			}
			var meters = new SimpleMeterRegistry();
			new JourneySearchLatencyMetrics(meters).recordFailure(JourneySearchKind.DEPART_AT, System.nanoTime(), status, code);
			assertThat(meters.get(JourneySearchLatencyMetrics.METER)
				.tag("mode", "depart_at").tag("outcome", expected).timer().count()).as(row).isEqualTo(1);
		}
	}

	@Test
	@DisplayName("Prometheus 출력은 모드·결과별 SLO 버킷 경계와 +Inf 버킷을 노출한다")
	void exposesTheSloBucketBoundariesInPrometheusText() {
		var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
		var latency = new JourneySearchLatencyMetrics(registry);

		latency.recordSuccess(JourneySearchKind.DEPART_AT, System.nanoTime());

		String scrape = registry.scrape();
		for (String bound : EXPECTED_BUCKET_BOUNDS) {
			assertThat(scrape).contains(
				"easysubway_journey_search_duration_seconds_bucket{mode=\"depart_at\",outcome=\"ok\",le=\"" + bound + "\"}");
		}
		assertThat(scrape).contains(
			"easysubway_journey_search_duration_seconds_bucket{mode=\"last_connection\",outcome=\"error\",le=\"+Inf\"} 0");
		assertThat(scrape).contains(
			"easysubway_journey_search_duration_seconds_count{mode=\"depart_at\",outcome=\"ok\"} 1");
	}
}

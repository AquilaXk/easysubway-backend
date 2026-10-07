package com.easysubway.journey.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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

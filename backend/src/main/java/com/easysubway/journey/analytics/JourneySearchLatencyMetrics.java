package com.easysubway.journey.analytics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Journey V3 검색 API의 모드별 응답 지연을 {@value #METER} 히스토그램으로 남긴다.
 *
 * <p>태그는 {@code mode}(depart_at·depart_between·arrive_by·last_connection)와 {@code outcome}
 * (ok·no_route·fail_closed·error) 두 개뿐이며 역 ID·요청 ID·사용자 값은 넣지 않는다. 16개 시계열을 시작할 때 0건으로
 * 등록해 트래픽이 없는 상태(count 0)와 지표가 아예 없는 상태(배포·수집 문제)를 platform 경보가 구분할 수 있다.</p>
 *
 * <p>계측은 컨트롤러 경계에서만 한다. 엔진 hot path는 건드리지 않는다. {@code http_server_requests}는 URI 단위라
 * {@code /api/v3/journeys/profile} 한 URI 안의 세 모드를 구분하지 못하고 그 {@code outcome}도 HTTP 상태 계열이라
 * 경로 없음과 오류를 가르지 못해 모드별 SLO에는 쓰지 않는다. 그래서 이 지표가 모드별 지연의 유일한 계측이며 이중 계측은
 * 없다. 측정 구간은 요청 해석·인증·실행·응답 매핑까지이고 JSON 직렬화와 네트워크는 포함하지 않는다.</p>
 *
 * <p>모드를 알기 전에 끝나는 실패(요청 검증·인증·속도 제한)는 검색 지연이 아니므로 기록하지 않는다.</p>
 */
@Component
public class JourneySearchLatencyMetrics {

	public static final String METER = "easysubway.journey.search.duration";

	/**
	 * 지연 SLO 임계값을 포함한 히스토그램 버킷 경계(밀리초). platform의 지연 burn-rate 경보가 같은 {@code le} 경계를
	 * 쓰므로 바꾸면 platform 경보 규칙과 테스트도 함께 바꾼다.
	 *
	 * <p>SLO는 "모드별로 28일 동안 ok·no_route 요청의 99%가 아래 시간 안에 끝난다"이다. 임계값은 수도권 실데이터
	 * fixture에 운영 컨트롤러·엔진을 내장 Tomcat으로 묶어 루프백 HTTP로 잰 서버 처리 지연의 p99 최댓값(엔진 #493 전후, 순차·
	 * 동시 8 두 조건, 모두 4회 측정 중 큰 값)에 5배 여유를 곱한 값 이상인 가장 작은 버킷 경계다. 5배는 운영 노드가 측정 장비
	 * (Apple M4 Pro)보다 느린 정도(가정 3배 이하)와 동시 부하·GC·배포 직후 JIT 미적중(가정 1.7배 이하)을 합친 값이며,
	 * 운영 히스토그램이 쌓이면 다시 확인한다.</p>
	 *
	 * <table>
	 *   <caption>모드별 측정 p99와 SLO 임계값</caption>
	 *   <tr><th>mode</th><th>측정 p99(ms)</th><th>최댓값 x5(ms)</th><th>SLO 임계값(ms)</th></tr>
	 *   <tr><td>depart_at</td><td>2.1 ~ 3.9</td><td>19.5</td><td>25</td></tr>
	 *   <tr><td>arrive_by</td><td>22.8 ~ 29.2</td><td>146</td><td>250</td></tr>
	 *   <tr><td>last_connection</td><td>38.7 ~ 40.9</td><td>205</td><td>250</td></tr>
	 *   <tr><td>depart_between</td><td>58.8 ~ 66.7</td><td>334</td><td>500</td></tr>
	 * </table>
	 */
	static final long[] SLO_BUCKET_BOUNDS_MILLIS = {10, 25, 50, 100, 250, 500, 1_000, 2_500};

	private static final Set<String> NO_ROUTE_CODES = Set.of(
		"ROUTE_NOT_FOUND", "ACCESSIBILITY_CONSTRAINT_UNSATISFIED", "NO_SERVICE_IN_DEPARTURE_WINDOW",
		"NO_ROUTE_ARRIVING_BY_DEADLINE", "NO_LAST_CONNECTION");
	/** 데이터 신선도·정체성·시설 상태를 확인하지 못해 추정으로 답하지 않고 의도적으로 닫은 503 코드. */
	private static final Set<String> FAIL_CLOSED_UNAVAILABLE_CODES = Set.of(
		"ROUTING_BUNDLE_UNAVAILABLE", "ROUTING_BUNDLE_STALE", "TIMETABLE_UNAVAILABLE", "TIMETABLE_STALE",
		"REALTIME_REQUIRED_UNAVAILABLE", "ROUTING_IDENTITY_MISMATCH", "FACILITY_STATUS_UNAVAILABLE");

	/** 검증된 조건을 만족하지 않는 요청을 정책에 따라 거절한 4xx 코드. */
	private static final Set<String> REQUEST_REFUSAL_CODES = Set.of(
		"STATION_NOT_FOUND", "TEMPORAL_WINDOW_TOO_LARGE", "TEMPORAL_QUERY_TOO_COMPLEX",
		"REALTIME_NOT_APPLICABLE_TO_TEMPORAL_QUERY");

	private enum Outcome {
		OK, NO_ROUTE, FAIL_CLOSED, ERROR;

		String tag() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	private final Map<JourneySearchKind, Map<Outcome, Timer>> timers = new EnumMap<>(JourneySearchKind.class);

	public JourneySearchLatencyMetrics(MeterRegistry meterRegistry) {
		Objects.requireNonNull(meterRegistry, "meterRegistry");
		Duration[] bounds = java.util.Arrays.stream(SLO_BUCKET_BOUNDS_MILLIS).mapToObj(Duration::ofMillis)
			.toArray(Duration[]::new);
		for (JourneySearchKind kind : JourneySearchKind.values()) {
			Map<Outcome, Timer> byOutcome = new EnumMap<>(Outcome.class);
			for (Outcome outcome : Outcome.values()) {
				byOutcome.put(outcome, Timer.builder(METER)
					.description("Journey V3 검색 API 응답 지연(요청 해석부터 응답 매핑까지)")
					.tag("mode", kind.name().toLowerCase(Locale.ROOT))
					.tag("outcome", outcome.tag())
					.serviceLevelObjectives(bounds)
					.register(meterRegistry));
			}
			timers.put(kind, byOutcome);
		}
	}

	/** 요청 처리 시작 시각. 컨트롤러 진입 직후에 한 번 읽는다. */
	public long start() {
		return System.nanoTime();
	}

	public void recordSuccess(JourneySearchKind kind, long startedNanos) {
		record(kind, Outcome.OK, startedNanos);
	}

	public void recordFailure(JourneySearchKind kind, long startedNanos, int httpStatus, String machineCode) {
		record(kind, classifyFailure(httpStatus, machineCode), startedNanos);
	}

	private void record(JourneySearchKind kind, Outcome outcome, long startedNanos) {
		timers.get(Objects.requireNonNull(kind, "kind")).get(outcome)
			.record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS);
	}

	/** 알 수 없는 상태·코드는 성공이나 fail-closed로 흘리지 않고 error로 센다. */
	private static Outcome classifyFailure(int httpStatus, String machineCode) {
		if ((httpStatus == 422 || httpStatus == 404) && NO_ROUTE_CODES.contains(machineCode)) return Outcome.NO_ROUTE;
		if (httpStatus == 503 && FAIL_CLOSED_UNAVAILABLE_CODES.contains(machineCode)) return Outcome.FAIL_CLOSED;
		if ((httpStatus == 400 || httpStatus == 404 || httpStatus == 422) && REQUEST_REFUSAL_CODES.contains(machineCode)) {
			return Outcome.FAIL_CLOSED;
		}
		return Outcome.ERROR;
	}
}

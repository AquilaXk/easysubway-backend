package com.easysubway.route.application.service;

import com.easysubway.journey.application.ActiveJourneySnapshotPort;
import com.easysubway.journey.application.FacilityAvailabilityPort;
import com.easysubway.journey.application.FacilityAvailabilityView;
import com.easysubway.journey.application.FacilityStatusUnavailableException;
import com.easysubway.journey.application.JourneyCandidate;
import com.easysubway.journey.application.JourneyRaptorPort.PlanResult;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.journey.application.JourneyRequestMeasurement;
import com.easysubway.journey.application.ServiceDayResolver;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransferRule;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 고정 입력 위에서 운영과 같은 Journey V3 서버 라우팅({@link JourneyRaptorAdapter})으로 질의를 돌려 golden 행을 만든다.
 *
 * <p>경로가 없을 때의 상태는 같은 질의를 조건만 풀어 한 번 더 돌려 가린다.</p>
 * <ul>
 *   <li>BLOCKED: 시설 차단이 없으면 같은 프리셋으로 경로가 나온다.</li>
 *   <li>UNKNOWN: 계단 없는 프리셋인데 계단 제약을 풀면 경로가 나온다. 계단 상태가 확정되지 않아 경로를 내지 않는다.</li>
 *   <li>UNSUPPORTED: 조건을 모두 풀어도 경로가 없다.</li>
 *   <li>ERROR: 시설 상태 필수 요청이 낡은 시설 상태를 만나 명시적 오류가 났다.</li>
 * </ul>
 * 걸음 속도·최대 환승·대안 수는 모바일 기본 요청(표준 걸음, 환승 3, 대안 3)으로 고정한다.
 */
final class JourneyGoldenRunner {

	private static final String REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
	private static final String ROUTE_BUNDLE_SHA256 = "a".repeat(64);
	private static final int MAX_TRANSFERS = 3;
	private static final int ALTERNATIVE_COUNT = 3;
	private static final Duration STALE_FACILITY_AGE = Duration.ofMinutes(6);
	private static final String ACCESS_REASON_PREFIX = "ACCESSIBILITY_";

	private final JourneyGoldenFixtures.Fixture fixture;
	private final RaptorRouteBundleRuntimeView runtime;
	private final Instant serviceDayStart;

	JourneyGoldenRunner(JourneyGoldenFixtures.Fixture fixture) {
		this.fixture = fixture;
		this.serviceDayStart = fixture.serviceDate().atStartOfDay(ServiceDayResolver.ZONE).toInstant();
		this.runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA256, 1, fixture.timetable());
	}

	List<JourneyGoldenRow> run(JourneyGoldenCorpus.Query query) {
		List<JourneyGoldenRow> rows = new ArrayList<>();
		for (JourneyGoldenPreset preset : JourneyGoldenPreset.values()) rows.add(run(query, preset));
		return rows;
	}

	private JourneyGoldenRow run(JourneyGoldenCorpus.Query query, JourneyGoldenPreset preset) {
		Instant ready = readyAt(query);
		PlanResult result;
		try {
			result = plan(query, preset.profile(), preset.constraint(), facilityPort(query, ready), ready);
		} catch (FacilityStatusUnavailableException exception) {
			return failure(query, preset, JourneyGoldenRow.Status.ERROR, FacilityStatusUnavailableException.class.getSimpleName());
		}
		if (!result.candidates().isEmpty()) return found(query, preset, result);
		return failure(query, preset, withoutRoute(query, preset, ready), "");
	}

	private JourneyGoldenRow.Status withoutRoute(JourneyGoldenCorpus.Query query, JourneyGoldenPreset preset, Instant ready) {
		FacilityAvailabilityPort healthy = healthyFacility(ready);
		if (query.facility() != null
			&& !plan(query, preset.profile(), preset.constraint(), healthy, ready).candidates().isEmpty()) {
			return JourneyGoldenRow.Status.BLOCKED;
		}
		if (preset.requiresStepFree()
			&& !plan(query, preset.unconstrainedControlProfile(), JourneyRequest.ConstraintMode.NONE, healthy, ready)
				.candidates().isEmpty()) {
			return JourneyGoldenRow.Status.UNKNOWN;
		}
		return JourneyGoldenRow.Status.UNSUPPORTED;
	}

	private PlanResult plan(
		JourneyGoldenCorpus.Query query, JourneyRequest.MobilityProfile profile,
		JourneyRequest.ConstraintMode constraint, FacilityAvailabilityPort facility, Instant ready
	) {
		var request = new JourneyRequest(REQUEST_ID, query.origin(), query.destination(),
			new JourneyRequest.Departure.Scheduled(ready), JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.STANDARD, profile, constraint, MAX_TRANSFERS, ALTERNATIVE_COUNT, () -> false);
		var adapter = new JourneyRaptorAdapter(facility, true, Clock.fixed(ready, ServiceDayResolver.ZONE));
		return adapter.plan(request, snapshot(ready), ready, null, new JourneyRequestMeasurement(REQUEST_ID));
	}

	private ActiveJourneySnapshotPort.ActiveJourneySnapshot snapshot(Instant ready) {
		return new ActiveJourneySnapshotPort.ActiveJourneySnapshot(
			"golden-snapshot", "golden-bundle", ROUTE_BUNDLE_SHA256, "golden-timetable", "golden-accessibility", 1,
			runtime, ready.plus(Duration.ofDays(1)), true,
			ActiveJourneySnapshotPort.ActiveServingEvidence.unobservable(),
			ActiveJourneySnapshotPort.SnapshotBoundaryReceipt.observed(0, 0),
			ActiveJourneySnapshotPort.SnapshotMeasurementReceipt.unobservable());
	}

	private Instant readyAt(JourneyGoldenCorpus.Query query) {
		return ZonedDateTime.of(fixture.serviceDate(), LocalTime.parse(query.departureLocalTime()), ServiceDayResolver.ZONE)
			.toInstant();
	}

	/** 시설 가동 정보. 시나리오가 없으면 신선하고 차단이 없는 상태다. */
	private FacilityAvailabilityPort facilityPort(JourneyGoldenCorpus.Query query, Instant ready) {
		var facility = query.facility();
		if (facility == null) return healthyFacility(ready);
		FacilityAvailabilityView view = switch (facility.mode()) {
			case BLOCK_STEP_FREE_PATHWAYS ->
				FacilityAvailabilityView.blocked(ready, stepFreePathwayEdges(facility.stationIds()));
			case STALE_REQUIRED -> FacilityAvailabilityView.empty(ready.minus(STALE_FACILITY_AGE));
		};
		return () -> view;
	}

	private static FacilityAvailabilityPort healthyFacility(Instant observedAt) {
		FacilityAvailabilityView view = FacilityAvailabilityView.empty(observedAt);
		return () -> view;
	}

	/** 환승에서 계단 없는 동선으로 쓰이는 경로 edge. 번들이 경로에 쓰는 edge여야 차단 정보가 번들에 해석된다. */
	private Set<String> stepFreePathwayEdges(List<String> stationIds) {
		RouteTimetable timetable = fixture.timetable();
		return timetable.routeAccessData().transferRules().stream()
			.filter(rule -> stationIds.contains(rule.fromStationId()))
			.map(TransferRule::strictStepFreePathwayEdgeId)
			.filter(Objects::nonNull)
			.collect(Collectors.toCollection(TreeSet::new));
	}

	private JourneyGoldenRow found(JourneyGoldenCorpus.Query query, JourneyGoldenPreset preset, PlanResult result) {
		var stairFree = result.stairFreeAlternative();
		String detail = "stairFree=%s facility=%s".formatted(stairFree.status(), stairFree.facilityStatus());
		return new JourneyGoldenRow(query.id(), preset, JourneyGoldenRow.Status.FOUND, detail,
			result.candidates().stream().map(candidate -> alternative(candidate)).toList());
	}

	private static JourneyGoldenRow failure(
		JourneyGoldenCorpus.Query query, JourneyGoldenPreset preset, JourneyGoldenRow.Status status, String detail
	) {
		return new JourneyGoldenRow(query.id(), preset, status, detail, List.of());
	}

	private JourneyGoldenRow.Alternative alternative(JourneyCandidate candidate) {
		List<String> lines = candidate.legs().stream()
			.filter(JourneyCandidate.Ride.class::isInstance)
			.map(leg -> ((JourneyCandidate.Ride) leg).lineId())
			.toList();
		String access = candidate.accessibility().reasonCodes().stream()
			.map(reason -> reason.startsWith(ACCESS_REASON_PREFIX) ? reason.substring(ACCESS_REASON_PREFIX.length()) : reason)
			.collect(Collectors.joining(","));
		return new JourneyGoldenRow.Alternative(serviceDayTime(candidate.plannedDepartureTime()),
			serviceDayTime(candidate.plannedArrivalTime()),
			candidate.durationSeconds(), candidate.transferCount(), candidate.walkingDistanceMeters(), lines, access);
	}

	/** 서비스일 0시부터 흐른 시각(HH:MM:SS). 자정을 넘으면 24시 이후로 쓴다(GTFS 관례). 날짜와 무관해 서비스일을 바꿔도 같다. */
	private String serviceDayTime(Instant instant) {
		long seconds = Duration.between(serviceDayStart, instant).getSeconds();
		return "%02d:%02d:%02d".formatted(seconds / 3600, seconds / 60 % 60, seconds % 60);
	}
}

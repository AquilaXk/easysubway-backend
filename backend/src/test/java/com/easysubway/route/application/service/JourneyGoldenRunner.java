package com.easysubway.route.application.service;

import com.easysubway.journey.application.ActiveJourneySnapshotPort;
import com.easysubway.journey.application.FacilityAvailabilityPort;
import com.easysubway.journey.application.FacilityAvailabilityView;
import com.easysubway.journey.application.FacilityStatusUnavailableException;
import com.easysubway.journey.application.JourneyCandidate;
import com.easysubway.journey.application.JourneyExecutionDisposition;
import com.easysubway.journey.application.JourneyExecutionFailure;
import com.easysubway.journey.application.JourneyRaptorPort.PlanResult;
import com.easysubway.journey.application.JourneyRealtimePort.RealtimeObservation;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.journey.application.JourneyRequestMeasurement;
import com.easysubway.journey.application.ServiceDayResolver;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransferRule;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitStopTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 고정 입력 위에서 운영과 같은 Journey V3 서버 라우팅({@link JourneyRaptorAdapter})으로 질의를 돌려 golden 행을 만든다.
 *
 * <p>운영이 실제로 내는 값만 기록한다. 경로가 있으면 대안과 운영의 {@code stairFreeAlternative}를, 빈 결과는 운영이
 * {@link JourneyExecutionFailure.Reason#NO_ROUTE}에 매기는 공개 오류(422 ROUTE_NOT_FOUND)를, 시설 상태 필수 요청이 낡은
 * 시설 상태를 만나 던진 예외는 운영이 매기는 공개 오류(503 FACILITY_STATUS_UNAVAILABLE)를 쓴다. 조건을 풀어 다시 돌려
 * 원인을 추론하지 않는다. 원인이 필요한 곳은 같은 질의의 다른 프리셋 행과 운영 증거로 읽는다
 * ({@link JourneyGoldenRow}).</p>
 *
 * <p>걸음 속도·최대 환승·대안 수는 모바일 기본 요청(표준 걸음, 환승 3, 대안 3)으로 고정한다.</p>
 */
final class JourneyGoldenRunner {

	private static final String REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
	private static final String ROUTE_BUNDLE_SHA256 = "a".repeat(64);
	private static final String REALTIME_IDENTITY = "golden-realtime-snapshot";
	private static final int MAX_TRANSFERS = 3;
	private static final int ALTERNATIVE_COUNT = 3;
	/** 실시간 질의 시 활성 열차 업데이트를 생성할 탐색 창(3시간). */
	private static final int REALTIME_WINDOW_SECONDS = 10_800;
	/** 운영의 허용 나이를 넘는 시설 상태. 운영 상수에서 유도해 한도가 바뀌어도 낡은 상태로 남게 한다. */
	private static final Duration STALE_FACILITY_AGE = JourneyFacilityBlockOverlay.MAX_AGE.plusMinutes(1);
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
		Instant ready = readyAt(query);
		RealtimeObservation realtimeObservation = realtimeObservation(query, ready);
		List<JourneyGoldenRow> rows = new ArrayList<>();
		for (JourneyGoldenPreset preset : JourneyGoldenPreset.values()) {
			rows.add(run(query, preset, ready, realtimeObservation));
		}
		return rows;
	}

	private JourneyGoldenRow run(
		JourneyGoldenCorpus.Query query,
		JourneyGoldenPreset preset,
		Instant ready,
		RealtimeObservation realtimeObservation
	) {
		PlanResult result;
		try {
			result = plan(query, preset, ready, realtimeObservation);
		} catch (FacilityStatusUnavailableException exception) {
			return failure(query, preset, JourneyGoldenRow.Status.ERROR, JourneyExecutionFailure.Reason.FACILITY_STATUS_UNAVAILABLE);
		}
		if (result.candidates().isEmpty()) {
			return failure(query, preset, JourneyGoldenRow.Status.NO_ROUTE, JourneyExecutionFailure.Reason.NO_ROUTE);
		}
		return found(query, preset, result);
	}

	private PlanResult plan(
		JourneyGoldenCorpus.Query query,
		JourneyGoldenPreset preset,
		Instant ready,
		RealtimeObservation realtimeObservation
	) {
		var timePolicy = query.realtime() == null
			? JourneyRequest.TimePolicy.TIMETABLE_REQUIRED
			: JourneyRequest.TimePolicy.REALTIME_REQUIRED;
		var request = new JourneyRequest(REQUEST_ID, query.origin(), query.destination(),
			new JourneyRequest.Departure.Scheduled(ready), timePolicy,
			JourneyRequest.WalkingPace.STANDARD, preset.profile(), preset.constraint(), MAX_TRANSFERS, ALTERNATIVE_COUNT, () -> false);
		var adapter = new JourneyRaptorAdapter(facilityPort(query, ready), true, Clock.fixed(ready, ServiceDayResolver.ZONE));
		return adapter.plan(request, snapshot(ready), ready, realtimeObservation, new JourneyRequestMeasurement(REQUEST_ID));
	}

	private RealtimeObservation realtimeObservation(JourneyGoldenCorpus.Query query, Instant ready) {
		var realtime = query.realtime();
		if (realtime == null) return null;
		Set<String> targetLines = Set.copyOf(realtime.lineIds());
		int readySeconds = Math.toIntExact(Duration.between(serviceDayStart, ready).getSeconds());
		int windowEndSeconds = readySeconds + REALTIME_WINDOW_SECONDS;
		var compiledTimetable = runtime.compiledTimetable();
		var planner = new RouteTimetableRaptorPlanner();
		List<JourneyTimetableRealtimeResolver.Update> updates = new ArrayList<>();
		for (int index = 0; index < compiledTimetable.scheduledTripCount(); index += 1) {
			RouteTimetableRaptorPlanner.ScheduledTrip trip = compiledTimetable.scheduledTrip(index);
			List<TransitStopTime> stopTimes = trip.stopTimes();
			if (stopTimes.size() < 2) continue;
			TransitStopTime firstStop = stopTimes.getFirst();
			boolean targeted = targetLines.contains(firstStop.lineId());
			int delta = targeted ? realtime.delaySeconds() : 0;
			int firstDeparture = trip.departureSeconds(0);
			int lastDeparture = trip.departureSeconds(stopTimes.size() - 1);
			if (lastDeparture + Math.max(0, delta) < readySeconds || firstDeparture > windowEndSeconds) {
				continue;
			}
			var departure = new JourneyTimetableRealtimeResolver.Departure(
				firstStop.stationId(),
				firstStop.lineId(),
				trip.trip().id(),
				trip.trip().trainNo(),
				trip.trip().servicePattern(),
				fixture.serviceDate(),
				firstStop.stopSequence(),
				serviceDayStart.plusSeconds(trip.arrivalSeconds(0)),
				serviceDayStart.plusSeconds(firstDeparture)
			);
			if (!planner.matchesActiveJourneyRealtimeDeparture(compiledTimetable, departure)) {
				continue;
			}
			boolean cancelled = targeted && realtime.cancelled();
			if (!cancelled && (firstDeparture + delta < 0
				|| lastDeparture + delta >= LoadRouteTimetablePort.SERVICE_DAY_SECONDS_LIMIT_EXCLUSIVE)) {
				delta = 0;
			}
			updates.add(new JourneyTimetableRealtimeResolver.Update(
				departure,
				delta,
				delta,
				cancelled,
				REALTIME_IDENTITY,
				ready
			));
		}
		var resolved = new JourneyTimetableRealtimeResolver.Updates(REALTIME_IDENTITY, true, updates, null);
		var realtimeView = RaptorRealtimeRuntimeView.compile(REALTIME_IDENTITY, runtime, resolved);
		return new RealtimeObservation(
			REALTIME_IDENTITY,
			ROUTE_BUNDLE_SHA256,
			realtimeView,
			ready.plus(Duration.ofMinutes(5)),
			true
		);
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
		String detail = "stairFree=" + stairFree.status() + " facility=" + stairFree.facilityStatus();
		return new JourneyGoldenRow(query.id(), preset, JourneyGoldenRow.Status.FOUND, detail,
			result.candidates().stream().map(this::alternative).toList());
	}

	/** 운영이 이 실패 사유에 매기는 공개 오류 코드를 상세로 기록한다. */
	private static JourneyGoldenRow failure(
		JourneyGoldenCorpus.Query query, JourneyGoldenPreset preset, JourneyGoldenRow.Status status,
		JourneyExecutionFailure.Reason reason
	) {
		var disposition = (JourneyExecutionDisposition.PublicFailure) JourneyExecutionDisposition.from(
			new JourneyExecutionFailure(reason));
		return new JourneyGoldenRow(query.id(), preset, status, disposition.machineCode().name(), List.of());
	}

	private JourneyGoldenRow.Alternative alternative(JourneyCandidate candidate) {
		List<String> lines = candidate.legs().stream()
			.filter(JourneyCandidate.Ride.class::isInstance)
			.map(leg -> ((JourneyCandidate.Ride) leg).lineId())
			.toList();
		List<String> via = candidate.legs().stream()
			.filter(JourneyCandidate.Transfer.class::isInstance)
			.map(leg -> ((JourneyCandidate.Transfer) leg).fromStationId())
			.toList();
		String access = candidate.accessibility().reasonCodes().stream()
			.map(reason -> reason.startsWith(ACCESS_REASON_PREFIX) ? reason.substring(ACCESS_REASON_PREFIX.length()) : reason)
			.collect(Collectors.joining(","));
		List<String> roles = candidate.alternativeCategories().stream()
			.map(Enum::name)
			.toList();
		Instant departure = candidate.hasRealtime() ? candidate.realtimeDepartureTime() : candidate.plannedDepartureTime();
		Instant arrival = candidate.hasRealtime() ? candidate.realtimeArrivalTime() : candidate.plannedArrivalTime();
		long durationSeconds = Duration.between(departure, arrival).toSeconds();
		return new JourneyGoldenRow.Alternative(serviceDayTime(departure),
			serviceDayTime(arrival),
			durationSeconds, candidate.transferCount(), candidate.walkingDistanceMeters(), lines, via, access, roles);
	}

	/** 서비스일 0시부터 흐른 시각(HH:MM:SS). 자정을 넘으면 24시 이후로 쓴다(GTFS 관례). 날짜와 무관해 서비스일을 바꿔도 같다. */
	private String serviceDayTime(Instant instant) {
		long seconds = Duration.between(serviceDayStart, instant).getSeconds();
		return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
	}
}

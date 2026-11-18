package com.easysubway.journey.application;

import com.easysubway.journey.application.StationTimetableSearchService.Failure;
import com.easysubway.journey.application.StationTimetableSearchService.FailureException;
import com.easysubway.journey.application.StationTimetableSnapshotPort.StationLine;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitFrequency;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitRoute;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitStopTime;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitTrip;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * #476 F3: 번들 한 세대의 역 시간표 색인. 열차·노선·열차별 정차·열차별 간격 운행·역·노선별 정차를 세대마다 한 번만 만든다.
 *
 * <p>색인은 그 세대의 런타임 객체에 묶여 함께 버려지므로, 세대가 바뀌면 새 세대는 새 색인을 쓰고 진행 중인 요청은 자기가
 * 잡은 세대의 색인을 끝까지 쓴다. 역·노선별 출발 후보는 처음 요청될 때 계산해 같은 세대 안에서 재사용한다(실패도 재사용).
 * 계산은 결정적이라 동시에 처음 요청돼도 결과가 같다.</p>
 */
public final class StationTimetableIndex {

	private final RouteTimetable timetable;
	private final FailureException structuralFailure;
	private final Map<String, TransitTrip> trips;
	private final Map<String, TransitRoute> routes;
	private final Map<String, List<TransitStopTime>> stopsByTrip = new HashMap<>();
	private final Map<String, List<TransitFrequency>> frequenciesByTrip = new HashMap<>();
	private final Map<StationLine, List<TransitStopTime>> stopsByStationLine = new HashMap<>();
	private final ConcurrentHashMap<StationLine, Object> departures = new ConcurrentHashMap<>();

	private StationTimetableIndex(RouteTimetable timetable) {
		this.timetable = Objects.requireNonNull(timetable, "timetable");
		Map<String, TransitTrip> indexedTrips = Map.of();
		Map<String, TransitRoute> indexedRoutes = Map.of();
		FailureException failure = null;
		try {
			indexedTrips = uniqueById(timetable.transitTrips(), TransitTrip::id);
			indexedRoutes = uniqueById(timetable.transitRoutes(), TransitRoute::id);
		} catch (FailureException exception) {
			failure = exception;
		}
		this.trips = indexedTrips;
		this.routes = indexedRoutes;
		this.structuralFailure = failure;
		for (TransitStopTime stop : timetable.transitStopTimes()) {
			stopsByTrip.computeIfAbsent(stop.tripId(), ignored -> new ArrayList<>()).add(stop);
		}
		for (TransitStopTime stop : timetable.transitStopTimes()) {
			stopsByStationLine.computeIfAbsent(new StationLine(stop.stationId(), stop.lineId()), ignored -> new ArrayList<>())
				.add(stop);
		}
		for (TransitFrequency frequency : timetable.transitFrequencies()) {
			frequenciesByTrip.computeIfAbsent(frequency.tripId(), ignored -> new ArrayList<>()).add(frequency);
		}
	}

	public static StationTimetableIndex of(RouteTimetable timetable) {
		return new StationTimetableIndex(timetable);
	}

	public RouteTimetable timetable() {
		return timetable;
	}

	/** 열차·노선 id가 겹치면 어느 역·노선도 믿을 수 없으므로 모든 요청이 원천 불일치로 실패한다. */
	void requireStructurallyValid() {
		if (structuralFailure != null) throw new FailureException(structuralFailure.failure());
	}

	boolean covers(StationLine stationLine) {
		return stopsByStationLine.containsKey(stationLine);
	}

	List<TransitStopTime> stopsAt(StationLine stationLine) {
		return stopsByStationLine.getOrDefault(stationLine, List.of());
	}

	List<TransitStopTime> stopsOfTrip(String tripId) {
		return stopsByTrip.getOrDefault(tripId, List.of());
	}

	List<TransitFrequency> frequenciesOfTrip(String tripId) {
		return frequenciesByTrip.getOrDefault(tripId, List.of());
	}

	TransitTrip trip(String tripId) {
		return trips.get(tripId);
	}

	TransitRoute route(String routeId) {
		return routes.get(routeId);
	}

	@SuppressWarnings("unchecked")
	<T> List<T> departures(StationLine stationLine, Function<StationLine, List<T>> compute) {
		Object cached = departures.computeIfAbsent(stationLine, key -> {
			try {
				return List.copyOf(compute.apply(key));
			} catch (FailureException exception) {
				return exception;
			}
		});
		if (cached instanceof FailureException failure) throw new FailureException(failure.failure(), failure.detail());
		return (List<T>) cached;
	}

	private static <T> Map<String, T> uniqueById(List<T> values, Function<T, String> id) {
		Map<String, T> result = new HashMap<>();
		for (T value : values) {
			if (result.put(id.apply(value), value) != null) throw new FailureException(Failure.TIMETABLE_IDENTITY_MISMATCH);
		}
		return Map.copyOf(result);
	}
}

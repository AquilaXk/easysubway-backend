package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.application.port.in.RouteV2SearchUseCase.SearchRouteV2Command;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import com.easysubway.route.domain.BoardingSlackPolicy;
import com.easysubway.route.domain.ConstraintMode;
import com.easysubway.route.domain.ProfileWalkTimeCalculator;
import com.easysubway.route.domain.ProfileWalkTimeCalculator.WalkTimeSource;
import com.easysubway.route.domain.RouteSearchResult;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("#2250 marked-route differential")
class RouteTimetableRaptorPlannerDifferentialTest {

	private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 7, 6);
	private static final List<String> STATIONS = List.of("a", "b", "c", "d", "e");

	@Test
	@DisplayName("고정 seed 무작위 OD 64개가 기존 exhaustive scan과 같은 후보를 반환한다")
	void matchesExhaustiveScanForRandomOdSamples() {
		RouteTimetable timetable = timetable();
		var planner = new RouteTimetableRaptorPlanner();
		var random = new Random(2250);

		for (int sample = 0; sample < 64; sample += 1) {
			String origin = STATIONS.get(random.nextInt(STATIONS.size()));
			String destination = STATIONS.get(random.nextInt(STATIONS.size()));
			int minute = random.nextInt(41);
			int maxTransfers = random.nextInt(4);
			var command = command(origin, destination, minute, maxTransfers);

			assertThat(signatures(planner.search(command, timetable)))
				.as("sample=%s origin=%s destination=%s minute=%s maxTransfers=%s",
					sample, origin, destination, minute, maxTransfers)
				.isEqualTo(exhaustiveSignatures(command, timetable));
		}
	}

	private static List<Signature> exhaustiveSignatures(SearchRouteV2Command command, RouteTimetable timetable) {
		Map<String, LoadRouteTimetablePort.TransitRoute> routes = new HashMap<>();
		for (var route : timetable.transitRoutes()) {
			routes.put(route.id(), route);
		}
		Map<String, List<LoadRouteTimetablePort.TransitStopTime>> stopTimes = new HashMap<>();
		for (var stopTime : timetable.transitStopTimes()) {
			stopTimes.computeIfAbsent(stopTime.tripId(), ignored -> new ArrayList<>()).add(stopTime);
		}
		List<ReferenceTrip> trips = timetable.transitTrips().stream()
			.map(trip -> new ReferenceTrip(
				trip,
				routes.get(trip.routeId()),
				stopTimes.get(trip.id()).stream()
					.sorted(Comparator.comparingInt(LoadRouteTimetablePort.TransitStopTime::stopSequence))
					.toList()
			))
			.sorted(Comparator.comparing(reference -> reference.trip().id()))
			.toList();
		int startSeconds = command.departureTime().toLocalTime().toSecondOfDay();
		Map<String, List<ReferenceLabel>> labels = new HashMap<>();
		labels.put(command.originStationId(), List.of(new ReferenceLabel(
			command.originStationId(), startSeconds, 0, List.of())));
		int slackSeconds = BoardingSlackPolicy.secondsFor(command.mobilityType());
		for (int round = 0; round <= command.maxTransfers(); round += 1) {
			int accessSeconds = walkSeconds(command, round == 0 ? 240 : 360);
			for (ReferenceTrip trip : trips) {
				ReferenceBoarding boarding = null;
				for (int stopIndex = 0; stopIndex < trip.stopTimes().size(); stopIndex += 1) {
					var stop = trip.stopTimes().get(stopIndex);
					for (ReferenceLabel label : List.copyOf(labels.getOrDefault(stop.stationId(), List.of()))) {
						if (label.boardings() == round
							&& stop.pickupType() != 1
							&& stop.departureSeconds() >= label.arrivalSeconds() + accessSeconds + slackSeconds
							&& (boarding == null || label.arrivalSeconds() < boarding.label().arrivalSeconds())) {
							boarding = new ReferenceBoarding(label, stopIndex);
						}
					}
					if (boarding == null || stopIndex <= boarding.stopIndex() || stop.dropOffType() == 1) {
						continue;
					}
					List<ReferenceLeg> path = new ArrayList<>(boarding.label().path());
					path.add(new ReferenceLeg(trip, boarding.stopIndex(), stopIndex));
					addLabel(labels, new ReferenceLabel(
						stop.stationId(), stop.arrivalSeconds(), round + 1, List.copyOf(path)));
				}
			}
		}
		return labels.getOrDefault(command.destinationStationId(), List.of()).stream()
			.filter(label -> !label.path().isEmpty())
			.sorted(Comparator.comparingInt(ReferenceLabel::arrivalSeconds)
				.thenComparingInt(ReferenceLabel::boardings)
				.thenComparingInt(label -> label.path().size()))
			.limit(Math.max(command.alternativeCount(), command.maxTransfers() + 1))
			.map(label -> new Signature(
				label.arrivalSeconds(),
				label.boardings(),
				label.path().stream().map(leg -> leg.trip().trip().id()).toList()))
			.toList();
	}

	private static void addLabel(Map<String, List<ReferenceLabel>> labels, ReferenceLabel candidate) {
		List<ReferenceLabel> stationLabels = labels.getOrDefault(candidate.stationId(), List.of());
		if (stationLabels.stream().anyMatch(existing -> same(existing, candidate) || dominates(existing, candidate))) {
			return;
		}
		List<ReferenceLabel> kept = stationLabels.stream()
			.filter(existing -> !dominates(candidate, existing))
			.sorted(Comparator.comparingInt(ReferenceLabel::arrivalSeconds)
				.thenComparingInt(ReferenceLabel::boardings))
			.collect(java.util.stream.Collectors.toCollection(ArrayList::new));
		kept.add(candidate);
		kept.sort(Comparator.comparingInt(ReferenceLabel::arrivalSeconds)
			.thenComparingInt(ReferenceLabel::boardings));
		List<ReferenceLabel> bestByBoardings = new ArrayList<>();
		for (ReferenceLabel label : kept) {
			if (bestByBoardings.stream().noneMatch(existing -> existing.boardings() == label.boardings())) {
				bestByBoardings.add(label);
			}
		}
		labels.put(candidate.stationId(), List.copyOf(bestByBoardings.stream().limit(4).toList()));
	}

	private static boolean dominates(ReferenceLabel left, ReferenceLabel right) {
		return left.arrivalSeconds() <= right.arrivalSeconds()
			&& left.boardings() <= right.boardings()
			&& (left.arrivalSeconds() < right.arrivalSeconds() || left.boardings() < right.boardings());
	}

	private static boolean same(ReferenceLabel left, ReferenceLabel right) {
		return left.arrivalSeconds() == right.arrivalSeconds()
			&& left.boardings() == right.boardings()
			&& left.path().stream().map(leg -> leg.trip().trip().id()).toList()
			.equals(right.path().stream().map(leg -> leg.trip().trip().id()).toList());
	}

	private static List<Signature> signatures(List<RouteSearchResult> results) {
		return results.stream().map(result -> {
			var rides = result.steps().stream().filter(step -> "ride".equals(step.stepType())).toList();
			OffsetDateTime arrival = OffsetDateTime.parse(rides.getLast().plannedArrivalTime());
			int arrivalSeconds = Math.toIntExact(Duration.between(
				SERVICE_DATE.atStartOfDay().atOffset(ZoneOffset.ofHours(9)), arrival).toSeconds());
			return new Signature(arrivalSeconds, rides.size(), rides.stream().map(step -> step.tripId()).toList());
		}).toList();
	}

	private static int walkSeconds(SearchRouteV2Command command, int baselineSeconds) {
		return ProfileWalkTimeCalculator.estimateSeconds(
			baselineSeconds, command.mobilityPreset(), WalkTimeSource.OFFICIAL_BASELINE, false).seconds();
	}

	private static SearchRouteV2Command command(String origin, String destination, int minute, int maxTransfers) {
		return new SearchRouteV2Command(
			origin,
			destination,
			OffsetDateTime.of(2026, 7, 6, 7, 40, 0, 0, ZoneOffset.ofHours(9)).plusMinutes(minute),
			MobilityType.SENIOR,
			ConstraintMode.ALLOW_WITH_WARNINGS,
			false,
			maxTransfers,
			3
		);
	}

	private static RouteTimetable timetable() {
		var daily = new LoadRouteTimetablePort.ServiceCalendar(
			"daily", true, true, true, true, true, true, true,
			SERVICE_DATE, SERVICE_DATE.plusDays(1), "Asia/Seoul");
		List<LoadRouteTimetablePort.TransitRoute> routes = List.of(
			route("r1", "l1"), route("r2", "l2"), route("r3", "l3"), route("r4", "l4"));
		List<LoadRouteTimetablePort.TransitTrip> trips = List.of(
			trip("t1", "r1"), trip("t2", "r1"), trip("t3", "r2"), trip("t4", "r2"),
			trip("t5", "r3"), trip("t6", "r4"));
		List<LoadRouteTimetablePort.TransitStopTime> stops = List.of(
			stop("t1", 1, "a", "l1", 28800), stop("t1", 2, "b", "l1", 29400), stop("t1", 3, "c", "l1", 30000),
			stop("t2", 1, "a", "l1", 30000), stop("t2", 2, "b", "l1", 30600), stop("t2", 3, "c", "l1", 31200),
			stop("t3", 1, "b", "l2", 30300), stop("t3", 2, "d", "l2", 30900), stop("t3", 3, "e", "l2", 31500),
			stop("t4", 1, "b", "l2", 31500), stop("t4", 2, "d", "l2", 32100), stop("t4", 3, "e", "l2", 32700),
			stop("t5", 1, "c", "l3", 31800), stop("t5", 2, "e", "l3", 32400),
			stop("t6", 1, "x", "l4", 28800), stop("t6", 2, "y", "l4", 29400));
		return new RouteTimetable(List.of(daily), List.of(), routes, trips, stops, List.of());
	}

	private static LoadRouteTimetablePort.TransitRoute route(String id, String lineId) {
		return new LoadRouteTimetablePort.TransitRoute(id, lineId, id, id, id, "Asia/Seoul");
	}

	private static LoadRouteTimetablePort.TransitTrip trip(String id, String routeId) {
		return new LoadRouteTimetablePort.TransitTrip(id, routeId, "daily", id, "0", "LOCAL", 0);
	}

	private static LoadRouteTimetablePort.TransitStopTime stop(
		String tripId, int sequence, String stationId, String lineId, int seconds
	) {
		return new LoadRouteTimetablePort.TransitStopTime(
			tripId, sequence, stationId, lineId, seconds, seconds, 0, 0);
	}

	private record Signature(int arrivalSeconds, int boardings, List<String> tripIds) {
	}

	private record ReferenceTrip(
		LoadRouteTimetablePort.TransitTrip trip,
		LoadRouteTimetablePort.TransitRoute route,
		List<LoadRouteTimetablePort.TransitStopTime> stopTimes
	) {
	}

	private record ReferenceLeg(ReferenceTrip trip, int fromIndex, int toIndex) {
	}

	private record ReferenceLabel(
		String stationId, int arrivalSeconds, int boardings, List<ReferenceLeg> path
	) {
	}

	private record ReferenceBoarding(ReferenceLabel label, int stopIndex) {
	}
}

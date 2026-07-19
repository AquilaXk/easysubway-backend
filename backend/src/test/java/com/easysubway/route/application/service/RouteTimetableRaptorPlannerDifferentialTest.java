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
import com.easysubway.route.domain.RouteWarningCode;
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

@DisplayName("#2250/#2251 RAPTOR differential")
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

	@Test
	@DisplayName("OD×mobility×constraint×평일/주말×시간대 720조합이 접근성 계약을 보존한다")
	void preservesAccessibilitySemanticsAcrossDifferentialMatrix() {
		RouteTimetable timetable = accessibilityMatrixTimetable();
		var planner = new RouteTimetableRaptorPlanner();
		var compiled = planner.compile(timetable);
		List<OdCase> odCases = List.of(
			new OdCase("direct", "a", "c", 0, false),
			new OdCase("one-transfer", "a", "e", 1, false),
			new OdCase("two-transfer", "a", "f", 2, false),
			new OdCase("disconnected", "x", "w", 2, false),
			new OdCase("access-blocked", "blocked-a", "blocked-b", 0, true)
		);
		List<LocalDate> serviceDates = List.of(SERVICE_DATE, SERVICE_DATE.plusDays(5));
		int[] departureBands = {19800, 30600, 82800, 90000};
		int samples = 0;
		for (OdCase odCase : odCases) {
			for (MobilityType mobilityType : MobilityType.values()) {
				for (ConstraintMode constraintMode : ConstraintMode.values()) {
					for (LocalDate serviceDate : serviceDates) {
						for (int departureSeconds : departureBands) {
							var command = matrixCommand(
								odCase, mobilityType, constraintMode, serviceDate, departureSeconds - 900);
							List<Signature> legacy = exhaustiveSignatures(command, timetable);
							var current = planner.searchWithDiagnostics(command, compiled);
							String sample = "%s/%s/%s/%s/%s".formatted(
								odCase.name(), mobilityType, constraintMode, serviceDate, departureSeconds);
							if (odCase.accessBlocked() && constraintMode == ConstraintMode.STRICT_STEP_FREE) {
								assertThat(legacy).as("의도 차이[%s]: 고정 access 구 엔진은 통과", sample).isNotEmpty();
								assertThat(current.itineraries()).as(sample).isEmpty();
								assertThat(current.blockedAccessibility()).as(sample).isNotNull();
								assertThat(current.blockedAccessibility().warnings()).extracting("code")
									.as(sample).contains(RouteWarningCode.LOW_DATA_CONFIDENCE);
								assertThat(current.blockedAccessibility().blockedReasons())
									.as(sample).anyMatch(reason -> reason.contains("접근 경로"));
							} else if (legacy.isEmpty()) {
								assertThat(current.itineraries()).as(sample).isEmpty();
								assertThat(current.blockedAccessibility()).as(sample).isNull();
							} else {
								assertThat(current.blockedAccessibility()).as(sample).isNull();
								assertThat(signatures(current.itineraries())).as(sample).isEqualTo(legacy);
								if (odCase.accessBlocked()) {
									assertThat(current.itineraries().getFirst().warnings()).extracting("code")
										.as(sample).contains(RouteWarningCode.LOW_DATA_CONFIDENCE);
								} else {
									assertThat(current.itineraries()).flatExtracting(RouteSearchResult::warnings)
										.as(sample).isEmpty();
								}
							}
							if (constraintMode == ConstraintMode.STRICT_STEP_FREE) {
								assertThat(current.itineraries()).flatExtracting(RouteSearchResult::steps)
									.as("strict unsafe edge 0건[%s]", sample)
									.noneMatch(step -> step.includesStairs() || step.requiresAccessibilityCheck());
							}
							samples += 1;
						}
					}
				}
			}
		}
		assertThat(samples).isEqualTo(720);
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
		if (command.departureTime().getHour() < 3) {
			startSeconds += 86400;
		}
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
			int arrivalSeconds = arrival.toLocalTime().toSecondOfDay();
			if (arrival.getHour() < 3) {
				arrivalSeconds += 86400;
			}
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

	private static SearchRouteV2Command matrixCommand(
		OdCase odCase,
		MobilityType mobilityType,
		ConstraintMode constraintMode,
		LocalDate serviceDate,
		int departureSeconds
	) {
		return new SearchRouteV2Command(
			odCase.origin(),
			odCase.destination(),
			serviceDate.atStartOfDay().plusSeconds(departureSeconds).atOffset(ZoneOffset.ofHours(9)),
			mobilityType,
			constraintMode,
			false,
			odCase.maxTransfers(),
			3
		);
	}
	private static RouteTimetable accessibilityMatrixTimetable() {
		var daily = new LoadRouteTimetablePort.ServiceCalendar(
			"daily", true, true, true, true, true, true, true,
			SERVICE_DATE, SERVICE_DATE.plusDays(7), "Asia/Seoul");
		List<LoadRouteTimetablePort.TransitRoute> routes = List.of(
			route("r1", "l1"), route("r2", "l2"), route("r3", "l3"),
			route("rx", "lx"), route("rz", "lz"), route("rb", "lb"));
		List<LoadRouteTimetablePort.TransitTrip> trips = new ArrayList<>();
		List<LoadRouteTimetablePort.TransitStopTime> stops = new ArrayList<>();
		int[] bands = {19800, 30600, 82800, 90000};
		for (int index = 0; index < bands.length; index += 1) {
			int base = bands[index];
			addTrip(trips, stops, "l1-" + index, "r1",
				stopSpec("a", "l1", base), stopSpec("b", "l1", base + 300), stopSpec("c", "l1", base + 600));
			addTrip(trips, stops, "l2-" + index, "r2",
				stopSpec("b", "l2", base + 1200), stopSpec("d", "l2", base + 1500),
				stopSpec("e", "l2", base + 1800));
			addTrip(trips, stops, "l3-" + index, "r3",
				stopSpec("e", "l3", base + 2700), stopSpec("f", "l3", base + 3000));
			addTrip(trips, stops, "x-" + index, "rx",
				stopSpec("x", "lx", base), stopSpec("y", "lx", base + 300));
			addTrip(trips, stops, "z-" + index, "rz",
				stopSpec("z", "lz", base), stopSpec("w", "lz", base + 300));
			addTrip(trips, stops, "blocked-" + index, "rb",
				stopSpec("blocked-a", "lb", base), stopSpec("blocked-b", "lb", base + 300));
		}
		return new RouteTimetable(
			List.of(daily), List.of(), routes, trips, stops, List.of(), List.of(), null, matrixAccessData());
	}
	private static void addTrip(
		List<LoadRouteTimetablePort.TransitTrip> trips,
		List<LoadRouteTimetablePort.TransitStopTime> stops,
		String tripId,
		String routeId,
		StopSpec... stopSpecs
	) {
		trips.add(trip(tripId, routeId));
		for (int index = 0; index < stopSpecs.length; index += 1) {
			StopSpec spec = stopSpecs[index];
			stops.add(stop(tripId, index + 1, spec.stationId(), spec.lineId(), spec.seconds()));
		}
	}
	private static StopSpec stopSpec(String stationId, String lineId, int seconds) {
		return new StopSpec(stationId, lineId, seconds);
	}
	private static LoadRouteTimetablePort.RouteAccessData matrixAccessData() {
		List<LoadRouteTimetablePort.PathwayNode> nodes = new ArrayList<>();
		List<LoadRouteTimetablePort.PathwayEdge> edges = new ArrayList<>();
		List<LoadRouteTimetablePort.RouteEdgeEvidence> evidence = new ArrayList<>();
		for (String stationLine : List.of(
			"a:l1", "b:l1", "c:l1", "b:l2", "d:l2", "e:l2", "e:l3", "f:l3",
			"x:lx", "y:lx", "z:lz", "w:lz", "blocked-b:lb")) {
			String[] parts = stationLine.split(":");
			addVerifiedAccess(nodes, edges, evidence, parts[0], parts[1]);
		}
		var generatedEntry = new LoadRouteTimetablePort.PathwayEdge(
			"blocked-entry", "blocked-entrance", "blocked-platform", 240, 180, false, false, 40,
			"UNKNOWN", "GENERATED", "GENERATED");
		edges.add(generatedEntry);
		nodes.add(new LoadRouteTimetablePort.PathwayNode("blocked-entrance", "blocked-a", null, "ENTRANCE"));
		nodes.add(new LoadRouteTimetablePort.PathwayNode("blocked-platform", "blocked-a", "lb", "PLATFORM"));
		evidence.add(new LoadRouteTimetablePort.RouteEdgeEvidence(
			"blocked-entry-evidence", "blocked-a", "lb", generatedEntry.id(), "ENTRY",
			"GENERATED", "GENERATED", false, "GENERATED"));
		List<LoadRouteTimetablePort.TransferRule> transfers = new ArrayList<>();
		addVerifiedTransfer(nodes, edges, evidence, transfers, "b", "l1", "l2");
		addVerifiedTransfer(nodes, edges, evidence, transfers, "e", "l2", "l3");
		return new LoadRouteTimetablePort.RouteAccessData(nodes, edges, transfers, evidence);
	}
	private static void addVerifiedAccess(
		List<LoadRouteTimetablePort.PathwayNode> nodes,
		List<LoadRouteTimetablePort.PathwayEdge> edges,
		List<LoadRouteTimetablePort.RouteEdgeEvidence> evidence,
		String station,
		String line
	) {
		String key = station + "-" + line;
		var entry = verifiedEdge(key + "-entry", 240, 180);
		var exit = verifiedEdge(key + "-exit", 180, 120);
		edges.add(entry);
		edges.add(exit);
		nodes.add(new LoadRouteTimetablePort.PathwayNode(entry.fromNodeId(), station, null, "ENTRANCE"));
		nodes.add(new LoadRouteTimetablePort.PathwayNode(entry.toNodeId(), station, line, "PLATFORM"));
		nodes.add(new LoadRouteTimetablePort.PathwayNode(exit.fromNodeId(), station, line, "PLATFORM"));
		nodes.add(new LoadRouteTimetablePort.PathwayNode(exit.toNodeId(), station, null, "EXIT"));
		evidence.add(verifiedEvidence(key + "-entry-evidence", station, line, entry.id(), "ENTRY"));
		evidence.add(verifiedEvidence(key + "-exit-evidence", station, line, exit.id(), "EXIT"));
	}
	private static void addVerifiedTransfer(
		List<LoadRouteTimetablePort.PathwayNode> nodes,
		List<LoadRouteTimetablePort.PathwayEdge> edges,
		List<LoadRouteTimetablePort.RouteEdgeEvidence> evidence,
		List<LoadRouteTimetablePort.TransferRule> transfers,
		String station,
		String fromLine,
		String toLine
	) {
		String key = station + "-" + fromLine + "-" + toLine;
		var stairEdge = new LoadRouteTimetablePort.PathwayEdge(
			key + "-stairs", key + "-stairs-from", key + "-stairs-to", 120, 80, false, true, 100,
			"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED");
		var stepFreeEdge = verifiedEdge(key + "-step-free", 360, 260);
		edges.add(stairEdge);
		edges.add(stepFreeEdge);
		for (var edge : List.of(stairEdge, stepFreeEdge)) {
			nodes.add(new LoadRouteTimetablePort.PathwayNode(edge.fromNodeId(), station, fromLine, "PLATFORM"));
			nodes.add(new LoadRouteTimetablePort.PathwayNode(edge.toNodeId(), station, toLine, "PLATFORM"));
		}
		evidence.add(verifiedEvidence(key + "-stairs-evidence", station, toLine, stairEdge.id(), "TRANSFER"));
		evidence.add(verifiedEvidence(key + "-step-free-evidence", station, toLine, stepFreeEdge.id(), "TRANSFER"));
		transfers.add(new LoadRouteTimetablePort.TransferRule(
			key + "-rule", station, fromLine, station, toLine, "IN_STATION", 360,
			stairEdge.id(), stepFreeEdge.id(), "VERIFIED"));
	}
	private static LoadRouteTimetablePort.PathwayEdge verifiedEdge(String id, int duration, int distance) {
		return new LoadRouteTimetablePort.PathwayEdge(
			id, id + "-from", id + "-to", duration, distance, false, false, 100,
			"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED");
	}
	private static LoadRouteTimetablePort.RouteEdgeEvidence verifiedEvidence(
		String id, String station, String line, String edgeId, String edgeType
	) {
		return new LoadRouteTimetablePort.RouteEdgeEvidence(
			id, station, line, edgeId, edgeType, "OFFICIAL_SOURCE", "VERIFIED", true, null);
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

	private record OdCase(String name, String origin, String destination, int maxTransfers, boolean accessBlocked) {
	}
	private record StopSpec(String stationId, String lineId, int seconds) {
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

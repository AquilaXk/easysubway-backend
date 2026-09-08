package com.easysubway.journey.adapter.in.web;

import com.easysubway.journey.application.JourneyCandidate;
import com.easysubway.journey.application.JourneyFrontierPolicyV1;
import com.easysubway.journey.application.JourneyProfileCandidateProjectionV1;
import com.easysubway.journey.application.JourneyProfileExecutionResult;
import com.easysubway.journey.application.JourneyProfileRaptorPort;
import com.easysubway.journey.application.JourneyProfileResourcePolicy;
import com.easysubway.journey.application.JourneyProfileSummaryPolicyV1;
import com.easysubway.journey.application.JourneyRaptorPruningInventoryV1;
import com.easysubway.journey.application.JourneyRaptorQuery;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.journey.application.ServiceDayResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

final class JourneyProfileResponseMapper {

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final String CONTRACT_VERSION = "JOURNEY_PROFILE_V1";

	private JourneyProfileResponseMapper() {
	}

	static ObjectNode map(
		JourneyRaptorQuery query,
		JourneyProfileExecutionResult.Success success,
		JourneyProfileResourcePolicy policy,
		String serverQueryId
	) {
		try {
			Objects.requireNonNull(query, "query");
			Objects.requireNonNull(success, "success");
			Objects.requireNonNull(policy, "policy");
			requireText(serverQueryId);
			if (!success.countSnapshot().requestId().equals(query.requestId())
				|| !success.resourcePolicyIdentity().equals(policy.identity())
				|| query.timePolicy() != JourneyRequest.TimePolicy.TIMETABLE_REQUIRED) throw invalid();
			Projection projection = project(query, success.temporalPlan(), policy.maxDestinationProfileLabels());
			if (projection instanceof Departure departure
				&& departure.segments().size() > policy.maxProfileBreakpoints()) throw capacity();
			if (!success.countSnapshot().algorithmIdentity().equals(projection.algorithm())) throw invalid();
			ObjectNode root = common(query, success, serverQueryId);
			root.set("temporalQuery", temporal(query.temporalQuery()));
			root.set("serviceDays", serviceDays(query, projection.candidates()));
			ArrayNode journeys = root.putArray("journeys");
			for (JourneyProfileCandidateProjectionV1.Candidate candidate : projection.candidates()) {
				ObjectNode profileCandidate = journeys.addObject();
				profileCandidate.put("journeyId", candidate.candidateId());
				profileCandidate.put("readyAt", candidate.readyAt().toString());
				profileCandidate.put("journeyStartTime", candidate.journeyStartTime().toString());
				profileCandidate.put("firstBoardingTime", candidate.firstBoardingTime().toString());
				profileCandidate.put("arrivalAtPlatform", candidate.finalPlatformArrivalTime().toString());
				profileCandidate.put("arrivalAtDestination", candidate.arrivalAtDestination().toString());
				ArrayNode tags = profileCandidate.putArray("objectiveTags");
				candidate.objectiveTags().forEach(tag -> tags.add(tag.name()));
				profileCandidate.set("journey", JSON.valueToTree(JourneySearchResponseMapper.mapJourney(
					toJourney(query, candidate))));
			}
			projection.write(root);
			return root;
		} catch (MappingException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw invalid();
		}
	}

	private static Projection project(JourneyRaptorQuery query, JourneyProfileRaptorPort.TemporalPlan plan, int cap) {
		if (!query.temporalQuery().equals(plan.temporalQuery())) throw invalid();
		return switch (plan) {
			case JourneyProfileRaptorPort.DepartureWindowPlan departure -> fromDeparture(query,
				JourneyProfileCandidateProjectionV1.projectDepartureWindow(query, departure, cap));
			case JourneyProfileRaptorPort.ArriveByPlan arriveBy -> fromArriveBy(query,
				JourneyProfileCandidateProjectionV1.projectArriveBy(query, arriveBy, cap));
			case JourneyProfileRaptorPort.LastConnectionPlan lastConnection -> fromLastConnection(query,
				JourneyProfileCandidateProjectionV1.projectLastConnection(query, lastConnection, cap));
		};
	}

	private static Projection fromDeparture(JourneyRaptorQuery query, JourneyProfileCandidateProjectionV1.Outcome outcome) {
		if (outcome instanceof JourneyProfileCandidateProjectionV1.CapacityExceeded) throw capacity();
		if (outcome instanceof JourneyProfileCandidateProjectionV1.NoService) throw noService();
		if (!(outcome instanceof JourneyProfileCandidateProjectionV1.Projected projected)) throw invalid();
		var value = projected.projection();
		return new Departure(value.candidates(), value.segments(), value.summary());
	}

	private static Projection fromArriveBy(JourneyRaptorQuery query, JourneyProfileCandidateProjectionV1.Outcome outcome) {
		if (outcome instanceof JourneyProfileCandidateProjectionV1.CapacityExceeded) throw capacity();
		if (!(outcome instanceof JourneyProfileCandidateProjectionV1.ArriveByProjected projected)) throw invalid();
		return new ArriveBy(projected.projection().candidates(), projected.projection().summary());
	}

	private static Projection fromLastConnection(JourneyRaptorQuery query, JourneyProfileCandidateProjectionV1.Outcome outcome) {
		if (outcome instanceof JourneyProfileCandidateProjectionV1.CapacityExceeded) throw capacity();
		if (!(outcome instanceof JourneyProfileCandidateProjectionV1.LastConnectionProjected projected)) throw invalid();
		return new LastConnection(projected.projection().candidates(), projected.projection().summary());
	}

	private static ObjectNode common(JourneyRaptorQuery query, JourneyProfileExecutionResult.Success success, String queryId) {
		ObjectNode root = JSON.createObjectNode();
		root.put("contractVersion", CONTRACT_VERSION);
		root.put("requestId", query.requestId());
		root.put("queryId", queryId);
		root.put("calculatedAt", success.calculatedAt().toString());
		root.put("validUntil", success.validUntil().toString());
		var source = success.sourceIdentity();
		ObjectNode sourceNode = root.putObject("sourceIdentity");
		sourceNode.put("routeBundleId", source.routeBundleId());
		sourceNode.put("routeBundleGeneration", Long.toString(source.generation()));
		sourceNode.put("routeBundleSha256", source.routeBundleSha256());
		sourceNode.put("timetableSnapshotId", source.timetableSnapshotId());
		sourceNode.put("accessibilitySnapshotId", source.accessibilitySnapshotId());
		sourceNode.putNull("realtimeSnapshotId");
		var algorithm = success.countSnapshot().algorithmIdentity();
		ObjectNode algorithmNode = root.putObject("algorithmIdentity");
		algorithmNode.put("algorithmSuiteId", algorithm.algorithmSuiteId());
		algorithmNode.put("queryAlgorithmId", algorithm.queryAlgorithmId());
		algorithmNode.put("semanticVersion", algorithm.semanticVersion());
		var frontier = JourneyFrontierPolicyV1.identity();
		ObjectNode frontierNode = root.putObject("frontierPolicyIdentity");
		frontierNode.put("frontierPolicyId", frontier.frontierPolicyId());
		frontierNode.put("semanticVersion", frontier.semanticVersion());
		var policy = success.resourcePolicyIdentity();
		ObjectNode policyNode = root.putObject("resourcePolicyIdentity");
		policyNode.put("resourcePolicyId", policy.resourcePolicyId());
		policyNode.put("semanticVersion", policy.semanticVersion());
		policyNode.put("resourcePolicySha256", policy.resourcePolicySha256());
		return root;
	}

	private static ObjectNode temporal(JourneyRaptorQuery.TemporalQuery query) {
		ObjectNode node = JSON.createObjectNode();
		switch (query) {
			case JourneyRaptorQuery.DepartBetween value -> {
				node.put("kind", "DEPART_BETWEEN"); node.put("earliestReadyAt", value.earliestReadyAt().toString()); node.put("latestReadyAt", value.latestReadyAt().toString());
			}
			case JourneyRaptorQuery.ArriveBy value -> {
				node.put("kind", "ARRIVE_BY"); node.put("earliestReadyAt", value.earliestReadyAt().toString()); node.put("arrivalDeadline", value.arrivalDeadline().toString());
			}
			case JourneyRaptorQuery.LastConnection value -> { node.put("kind", "LAST_CONNECTION"); node.put("serviceDate", value.serviceDate().toString()); }
			case JourneyRaptorQuery.DepartAt ignored -> throw invalid();
		}
		return node;
	}

	private static ArrayNode serviceDays(JourneyRaptorQuery query,
		List<JourneyProfileCandidateProjectionV1.Candidate> candidates) {
		Set<String> dates = new LinkedHashSet<>();
		for (var candidate : candidates) {
			// 막차의 운행일은 선택된 시간표에 속하며, 다음 날 03:00 이후에도 바뀌지 않는다.
			var date = query.temporalQuery() instanceof JourneyRaptorQuery.LastConnection
				? candidate.itinerary().serviceDate() : ServiceDayResolver.resolve(candidate.readyAt()).serviceDate();
			dates.add(date.toString());
		}
		if (dates.isEmpty()) throw invalid();
		ArrayNode values = JSON.createArrayNode();
		for (String date : dates) {
			ObjectNode value = values.addObject(); value.put("serviceDate", date);
			value.put("serviceTimezone", ServiceDayResolver.TIMEZONE); value.put("serviceDayCutoff", ServiceDayResolver.CUTOFF_LOCAL_TIME);
		}
		return values;
	}

	private static JourneyCandidate toJourney(JourneyRaptorQuery query, JourneyProfileCandidateProjectionV1.Candidate candidate) {
		var itinerary = candidate.itinerary();
		if (itinerary.realtimeReadyAt() != null || itinerary.realtimeArrivalAtDestination() != null) {
			throw invalid();
		}
		List<JourneyCandidate.Leg> legs = new ArrayList<>();
		long distance = 0;
		int transfers = 0;
		boolean stairs = false;
		String last = null;
		boolean rideSeen = false;
		int stage = 0;
		for (JourneyProfileRaptorPort.Leg nativeLeg : itinerary.legs()) {
			if (nativeLeg instanceof JourneyProfileRaptorPort.AccessLeg access) {
				if (!access.verified()
						|| (query.constraintMode() == JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE
						&& access.includesStairs())) {
					throw invalid();
				}
				distance += access.distanceMeters();
				stairs |= access.includesStairs();
				switch (access.kind()) {
					case ENTRY -> {
						if (stage != 0 || !access.fromStationId().equals(query.originStationId())) {
							throw invalid();
						}
						legs.add(new JourneyCandidate.Entry(access.fromStationId(), access.durationSeconds()));
						stage = 1;
						last = access.toStationId();
					}
					case TRANSFER -> {
						if (stage != 2 || !access.fromStationId().equals(last)) {
							throw invalid();
						}
						legs.add(new JourneyCandidate.Transfer(
								access.fromStationId(), access.toStationId(), access.durationSeconds()));
						transfers++;
						stage = 1;
						last = access.toStationId();
					}
					case EXIT -> {
						if (stage != 2 || !access.fromStationId().equals(last)
								|| !access.toStationId().equals(query.destinationStationId())) {
							throw invalid();
						}
						legs.add(new JourneyCandidate.Exit(access.fromStationId(), access.durationSeconds()));
						stage = 3;
					}
				}
			} else if (nativeLeg instanceof JourneyProfileRaptorPort.RideLeg ride) {
				if (stage != 1 || !ride.fromStationId().equals(last)
						|| ride.realtimeDepartureTime() != null || ride.realtimeArrivalTime() != null) {
					throw invalid();
				}
				legs.add(new JourneyCandidate.Ride(
						ride.lineId(), ride.tripId(), ride.directionStationId(), ride.fromStationId(),
						ride.toStationId(), ride.plannedDepartureTime(), ride.plannedArrivalTime(), null, null));
				stage = 2;
				last = ride.toStationId();
				rideSeen = true;
			} else {
				throw invalid();
			}
		}
		if (!rideSeen || stage != 3 || distance != itinerary.metrics().accessDistanceMeters()
				|| transfers != itinerary.metrics().transfersUsed()) {
			throw invalid();
		}
		return new JourneyCandidate(candidate.candidateId(), candidate.readyAt(), candidate.arrivalAtDestination(), null, null,
			Duration.between(candidate.readyAt(), candidate.arrivalAtDestination()).toSeconds(), transfers, distance,
			JourneyCandidate.TimeSource.TIMETABLE, new JourneyCandidate.Accessibility(!stairs, List.of("ACCESSIBILITY_VERIFIED")), legs);
	}

	private sealed interface Projection permits Departure, ArriveBy, LastConnection {
		List<JourneyProfileCandidateProjectionV1.Candidate> candidates();
		JourneyRaptorPruningInventoryV1.AlgorithmSemanticIdentity algorithm();
		void write(ObjectNode root);
	}
	private record Departure(List<JourneyProfileCandidateProjectionV1.Candidate> candidates, List<com.easysubway.journey.application.JourneyProfileSegmentPolicyV1.Segment> segments, JourneyProfileSummaryPolicyV1.Departure summary) implements Projection {
		public JourneyRaptorPruningInventoryV1.AlgorithmSemanticIdentity algorithm() { return JourneyRaptorPruningInventoryV1.FORWARD_RANGE_RAPTOR; }
		public void write(ObjectNode root) { ArrayNode values = root.putArray("profileSegments"); for (var segment : segments) { ObjectNode value = values.addObject(); value.put("readyFromInclusive", segment.readyFromInclusive().toString()); value.put("readyUntilExclusive", segment.readyUntilExclusive().toString()); ArrayNode ids = value.putArray("journeyIds"); segment.journeyIds().forEach(ids::add); } ObjectNode value = root.putObject("summary"); value.put("kind", "DEPART_BETWEEN"); value.put("earliestArrivalJourneyId", summary.earliestArrivalJourneyId()); value.put("latestDepartureJourneyId", summary.latestDepartureJourneyId()); ArrayNode ids = value.putArray("recommendedJourneyIds"); summary.recommendedJourneyIds().forEach(ids::add); }
	}
	private record ArriveBy(List<JourneyProfileCandidateProjectionV1.Candidate> candidates, JourneyProfileSummaryPolicyV1.ArriveBy summary) implements Projection {
		public JourneyRaptorPruningInventoryV1.AlgorithmSemanticIdentity algorithm() { return JourneyRaptorPruningInventoryV1.REVERSE_RANGE_RAPTOR; }
		public void write(ObjectNode root) { ObjectNode value = root.putObject("summary"); value.put("kind", "ARRIVE_BY"); value.put("arrivalDeadline", summary.arrivalDeadline().toString()); value.put("latestFeasibleDeparture", summary.latestFeasibleDeparture().toString()); value.put("primaryJourneyId", summary.primaryJourneyId()); ArrayNode ids = value.putArray("recommendedJourneyIds"); summary.recommendedJourneyIds().forEach(ids::add); }
	}
	private record LastConnection(List<JourneyProfileCandidateProjectionV1.Candidate> candidates, JourneyProfileSummaryPolicyV1.LastConnection summary) implements Projection {
		public JourneyRaptorPruningInventoryV1.AlgorithmSemanticIdentity algorithm() { return JourneyRaptorPruningInventoryV1.REVERSE_RANGE_RAPTOR; }
		public void write(ObjectNode root) { ObjectNode value = root.putObject("summary"); value.put("kind", "LAST_CONNECTION"); value.put("latestFeasibleDeparture", summary.latestFeasibleDeparture().toString()); value.put("lastConnectionJourneyId", summary.lastConnectionJourneyId()); ArrayNode safer = value.putArray("saferAlternativeJourneyIds"); summary.saferAlternativeJourneyIds().forEach(safer::add); ArrayNode ids = value.putArray("recommendedJourneyIds"); summary.recommendedJourneyIds().forEach(ids::add); }
	}

	static final class MappingException extends IllegalArgumentException {
		private final JourneyProfileExecutionResult.Reason reason;
		MappingException(JourneyProfileExecutionResult.Reason reason) { super("invalid Journey profile response"); this.reason = reason; }
		JourneyProfileExecutionResult.Reason reason() { return reason; }
	}
	private static MappingException invalid() { return new MappingException(JourneyProfileExecutionResult.Reason.RAPTOR_FAILED); }
	private static MappingException capacity() { return new MappingException(JourneyProfileExecutionResult.Reason.RAPTOR_FRONTIER_CAPACITY_EXCEEDED); }
	private static MappingException noService() { return new MappingException(JourneyProfileExecutionResult.Reason.NO_SERVICE_IN_DEPARTURE_WINDOW); }
	private static String requireText(String value) { if (value == null || value.isBlank()) throw invalid(); return value; }
}

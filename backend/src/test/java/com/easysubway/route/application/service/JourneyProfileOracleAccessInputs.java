package com.easysubway.route.application.service;

import com.easysubway.journey.application.JourneyRequest.ConstraintMode;
import com.easysubway.journey.application.JourneyRequest.MobilityProfile;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Test-only raw route access normalizer. Missing evidence is not an oracle alternative. */
final class JourneyProfileOracleAccessInputs {
	private JourneyProfileOracleAccessInputs() { }

	static List<JourneyProfileExactOracle.Access> normalize(
		LoadRouteTimetablePort.RouteAccessData data, MobilityProfile profile, ConstraintMode constraint,
		int walkingSpeedMetersPerHour, int maximumAccesses
	) {
		Objects.requireNonNull(data, "data");
		Objects.requireNonNull(profile, "profile");
		Objects.requireNonNull(constraint, "constraint");
		if (walkingSpeedMetersPerHour <= 0 || maximumAccesses <= 0) throw unavailable("explicit bounds are required");
		Map<String, LoadRouteTimetablePort.PathwayNode> nodes = unique(data.pathwayNodes(), LoadRouteTimetablePort.PathwayNode::id, "node");
		Map<String, LoadRouteTimetablePort.PathwayEdge> edges = unique(data.pathwayEdges(), LoadRouteTimetablePort.PathwayEdge::id, "edge");
		Map<String, LoadRouteTimetablePort.RouteEdgeEvidence> evidence = unique(data.routeEdgeEvidence(), LoadRouteTimetablePort.RouteEdgeEvidence::id, "evidence");
		Map<String, LoadRouteTimetablePort.TransferRule> rules = unique(data.transferRules(), LoadRouteTimetablePort.TransferRule::id, "transfer rule");
		List<JourneyProfileExactOracle.Access> result = new ArrayList<>();
		Set<String> evidenceTuples = new HashSet<>();
		for (LoadRouteTimetablePort.RouteEdgeEvidence item : evidence.values()) {
			if ("RIDE".equals(item.edgeType())) continue;
			String tuple = encode(text(item.edgeType(), "evidence type"), text(item.stationId(), "evidence station"),
				text(item.lineId(), "evidence line"), text(item.edgeId(), "evidence edge"));
			if (!evidenceTuples.add(tuple)) throw unavailable("ambiguous evidence tuple");
			if ("ENTRY".equals(item.edgeType()) || "EXIT".equals(item.edgeType())) requireCapacity(result, maximumAccesses);
			if ("ENTRY".equals(item.edgeType())) result.add(entryOrExit(item, edge(edges, item.edgeId()), nodes, profile, constraint, true));
			else if ("EXIT".equals(item.edgeType())) result.add(entryOrExit(item, edge(edges, item.edgeId()), nodes, profile, constraint, false));
			else if (!"TRANSFER".equals(item.edgeType())) throw unavailable("unsupported evidence type");
		}
		for (LoadRouteTimetablePort.TransferRule rule : rules.values()) {
			if (!"IN_STATION".equals(rule.transferType())) throw unavailable("unsupported transfer rule");
			if (rule.minTransferSeconds() < 0) throw unavailable("negative transfer minimum");
			text(rule.verificationStatus(), "transfer verification");
			if (!Objects.equals(rule.fromStationId(), rule.toStationId())) throw unavailable("non-station transfer");
			String normalEdgeId = rule.pathwayEdgeId();
			String strictEdgeId = rule.strictStepFreePathwayEdgeId();
			if (normalEdgeId == null && strictEdgeId == null) throw unavailable("transfer pathway is required");
			if (normalEdgeId != null) {
				requireCapacity(result, maximumAccesses);
				addTransfer(result, rule, edge(edges, text(normalEdgeId, "normal pathway")), evidence, nodes, profile, constraint,
					normalEdgeId.equals(strictEdgeId), walkingSpeedMetersPerHour);
			}
			if (strictEdgeId != null && !strictEdgeId.equals(normalEdgeId)) {
				requireCapacity(result, maximumAccesses);
				addTransfer(result, rule, edge(edges, strictEdgeId), evidence, nodes, profile, constraint, true, walkingSpeedMetersPerHour);
			}
		}
		return List.copyOf(result);
	}

	private static void requireCapacity(List<?> values, int maximum) {
		if (values.size() >= maximum) throw unavailable("maximum accesses exceeded");
	}

	private static JourneyProfileExactOracle.Access entryOrExit(
		LoadRouteTimetablePort.RouteEdgeEvidence evidence, LoadRouteTimetablePort.PathwayEdge edge,
		Map<String, LoadRouteTimetablePort.PathwayNode> nodes, MobilityProfile profile, ConstraintMode constraint, boolean entry
	) {
		Direction direction = entry ? directionTo(edge, nodes, evidence.stationId(), evidence.lineId())
			: directionFrom(edge, nodes, evidence.stationId(), evidence.lineId());
		int seconds = entryExitSeconds(edge.durationSeconds(), profile);
		boolean strict = strictEligible(edge, evidence, constraint) && !edge.includesStairs();
		boolean allowed = allowed(edge, constraint, strict);
		var from = node(nodes, direction == Direction.FORWARD ? edge.fromNodeId() : edge.toNodeId());
		var to = node(nodes, direction == Direction.FORWARD ? edge.toNodeId() : edge.fromNodeId());
		return access(entry ? JourneyProfileExactOracle.AccessKind.ENTRY : JourneyProfileExactOracle.AccessKind.EXIT,
			evidence.id(), "", edge, direction, from.stationId(), from.lineId(),
			to.stationId(), to.lineId(), seconds, edge.distanceMeters(), edge.includesStairs() ? 1 : 0,
			verified(edge, evidence), allowed);
	}

	private static void addTransfer(
		List<JourneyProfileExactOracle.Access> result, LoadRouteTimetablePort.TransferRule rule,
		LoadRouteTimetablePort.PathwayEdge edge, Map<String, LoadRouteTimetablePort.RouteEdgeEvidence> evidence,
		Map<String, LoadRouteTimetablePort.PathwayNode> nodes, MobilityProfile profile, ConstraintMode constraint,
		boolean strictPath, int speed
	) {
		LoadRouteTimetablePort.RouteEdgeEvidence destination = exactlyOne(evidence.values(), item -> "TRANSFER".equals(item.edgeType())
			&& edge.id().equals(item.edgeId()) && rule.toStationId().equals(item.stationId()) && rule.toLineId().equals(item.lineId()), "transfer evidence");
		Direction direction = directionBetween(edge, nodes, rule.fromStationId(), rule.fromLineId(), rule.toStationId(), rule.toLineId());
		boolean strict = strictPath && edge.id().equals(rule.strictStepFreePathwayEdgeId()) && strictEligible(edge, destination, constraint)
			&& !edge.includesStairs();
		result.add(access(JourneyProfileExactOracle.AccessKind.TRANSFER, destination.id(), rule.id(), edge, direction,
			rule.fromStationId(), rule.fromLineId(), rule.toStationId(), rule.toLineId(), transferSeconds(edge, speed, profile),
			edge.distanceMeters(), edge.includesStairs() ? 1 : 0,
			verified(edge, destination) && "VERIFIED".equals(rule.verificationStatus()), allowed(edge, constraint, strict)));
	}

	private static boolean allowed(LoadRouteTimetablePort.PathwayEdge edge, ConstraintMode constraint, boolean strict) {
		String status = text(edge.accessibilityStatus(), "accessibility status");
		if ("UNAVAILABLE".equals(status) || "UNDER_MAINTENANCE".equals(status)) return false;
		if (!"AVAILABLE".equals(status)) throw unavailable("unknown accessibility status");
		return constraint != ConstraintMode.REQUIRE_STEP_FREE || strict;
	}
	private static boolean strictEligible(LoadRouteTimetablePort.PathwayEdge edge, LoadRouteTimetablePort.RouteEdgeEvidence evidence, ConstraintMode constraint) {
		return constraint == ConstraintMode.REQUIRE_STEP_FREE && "AVAILABLE".equals(edge.accessibilityStatus())
			&& edge.reliabilityScore() >= 80 && evidence.strictRouteEligible() && trusted(edge.provenanceKind()) && trusted(evidence.provenanceKind());
	}
	private static boolean trusted(String value) { return Set.of("OFFICIAL_SOURCE", "OPERATOR_CONFIRMED", "FIELD_VERIFIED").contains(text(value, "provenance kind")); }
	private static boolean verified(LoadRouteTimetablePort.PathwayEdge edge, LoadRouteTimetablePort.RouteEdgeEvidence evidence) {
		return "VERIFIED".equals(text(edge.verificationStatus(), "edge verification")) && "VERIFIED".equals(text(evidence.verificationStatus(), "evidence verification"));
	}
	private static int entryExitSeconds(int baseline, MobilityProfile profile) {
		if (baseline < 0) throw unavailable("negative baseline");
		int percent = switch (profile) { case STANDARD, STEP_FREE -> 100; case SLOW -> 135; case NO_STAIRS -> 120; };
		long scaled = ceil(Math.multiplyExact((long) baseline, percent), 100);
		if (profile == MobilityProfile.STEP_FREE) scaled = Math.addExact(scaled, 60L);
		return Math.toIntExact(scaled);
	}
	private static int transferSeconds(LoadRouteTimetablePort.PathwayEdge edge, int speed, MobilityProfile profile) {
		if (edge.distanceMeters() <= 0) throw unavailable("transfer distance is required");
		long seconds = ceil(Math.multiplyExact((long) edge.distanceMeters(), 3600L), speed);
		if (profile == MobilityProfile.STEP_FREE) seconds = Math.addExact(seconds, 60L);
		return Math.toIntExact(seconds);
	}
	private static long ceil(long numerator, long denominator) { return Math.floorDiv(Math.addExact(numerator, denominator - 1), denominator); }

	private static JourneyProfileExactOracle.Access access(JourneyProfileExactOracle.AccessKind kind, String evidenceId, String ruleId,
		LoadRouteTimetablePort.PathwayEdge edge, Direction direction, String fromStation, String fromLine, String toStation, String toLine,
		int seconds, int distance, int stairs, boolean verified, boolean allowed) {
		return new JourneyProfileExactOracle.Access(encode(kind.name(), evidenceId, ruleId, edge.id(), direction.name()), kind,
			fromStation, fromLine, toStation, toLine, seconds, distance, stairs, verified, allowed);
	}
	private static Direction directionTo(LoadRouteTimetablePort.PathwayEdge edge, Map<String, LoadRouteTimetablePort.PathwayNode> nodes, String station, String line) {
		if (matches(node(nodes, edge.toNodeId()), station, line) && outer(node(nodes, edge.fromNodeId()), station, line)) return Direction.FORWARD;
		if (edge.bidirectional() && matches(node(nodes, edge.fromNodeId()), station, line) && outer(node(nodes, edge.toNodeId()), station, line)) return Direction.REVERSE;
		throw unavailable("entry direction");
	}
	private static Direction directionFrom(LoadRouteTimetablePort.PathwayEdge edge, Map<String, LoadRouteTimetablePort.PathwayNode> nodes, String station, String line) {
		if (matches(node(nodes, edge.fromNodeId()), station, line) && outer(node(nodes, edge.toNodeId()), station, line)) return Direction.FORWARD;
		if (edge.bidirectional() && matches(node(nodes, edge.toNodeId()), station, line) && outer(node(nodes, edge.fromNodeId()), station, line)) return Direction.REVERSE;
		throw unavailable("exit direction");
	}
	private static Direction directionBetween(LoadRouteTimetablePort.PathwayEdge edge, Map<String, LoadRouteTimetablePort.PathwayNode> nodes,
		String fromStation, String fromLine, String toStation, String toLine) {
		if (matches(node(nodes, edge.fromNodeId()), fromStation, fromLine) && matches(node(nodes, edge.toNodeId()), toStation, toLine)) return Direction.FORWARD;
		if (edge.bidirectional() && matches(node(nodes, edge.toNodeId()), fromStation, fromLine) && matches(node(nodes, edge.fromNodeId()), toStation, toLine)) return Direction.REVERSE;
		throw unavailable("transfer direction");
	}
	private static boolean matches(LoadRouteTimetablePort.PathwayNode node, String station, String line) { return station.equals(node.stationId()) && Objects.equals(line, node.lineId()); }
	private static boolean outer(LoadRouteTimetablePort.PathwayNode node, String station, String line) {
		return station.equals(node.stationId()) && (node.lineId() == null || line.equals(node.lineId()));
	}
	private static LoadRouteTimetablePort.PathwayNode node(Map<String, LoadRouteTimetablePort.PathwayNode> nodes, String id) { return require(nodes.get(id), "pathway node"); }
	private static LoadRouteTimetablePort.PathwayEdge edge(Map<String, LoadRouteTimetablePort.PathwayEdge> edges, String id) { return require(edges.get(id), "pathway edge"); }
	private static <T> T require(T value, String label) { if (value == null) throw unavailable("missing " + label); return value; }
	private static String text(String value, String label) { if (value == null || value.isBlank()) throw unavailable("missing " + label); return value; }
	private static <T> Map<String, T> unique(List<T> values, java.util.function.Function<T, String> id, String label) {
		Map<String, T> result = new LinkedHashMap<>(); for (T value : values) { String key = id.apply(value); if (key == null || key.isBlank() || result.putIfAbsent(key, value) != null) throw unavailable("duplicate " + label); } return result;
	}
	private static <T> T exactlyOne(Iterable<T> values, java.util.function.Predicate<T> predicate, String label) { T found = null; for (T value : values) if (predicate.test(value)) { if (found != null) throw unavailable("ambiguous " + label); found = value; } return require(found, label); }
	private static String encode(String... values) { StringBuilder result = new StringBuilder(); for (String value : values) { if (value == null) throw unavailable("missing identity"); result.append(value.length()).append(':').append(value); } return result.toString(); }
	private enum Direction { FORWARD, REVERSE }
	static final class InputUnavailable extends RuntimeException { InputUnavailable(String message) { super(message); } }
	private static InputUnavailable unavailable(String message) { return new InputUnavailable(message); }
}

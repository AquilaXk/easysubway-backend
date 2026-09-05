package com.easysubway.route.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.easysubway.journey.application.JourneyRequest.ConstraintMode;
import com.easysubway.journey.application.JourneyRequest.MobilityProfile;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import java.util.List;
import org.junit.jupiter.api.Test;

class JourneyProfileOracleAccessInputsTest {
	@Test
	void retainsEveryPhysicalEntryAlternative() {
		var data = new LoadRouteTimetablePort.RouteAccessData(
			List.of(node("outside", "station-a", null), node("platform", "station-a", "line-a")),
			List.of(edge("entry-a", "outside", "platform", 100, 30, false), edge("entry-b", "outside", "platform", 120, 40, false)),
			List.of(), List.of(evidence("e-a", "station-a", "line-a", "entry-a", "ENTRY"), evidence("e-b", "station-a", "line-a", "entry-b", "ENTRY")));
		var accesses = normalize(data, MobilityProfile.STANDARD, ConstraintMode.NONE);
		assertEquals(2, accesses.size());
		assertTrue(accesses.stream().anyMatch(access -> access.id().contains("entry-a")));
		assertTrue(accesses.stream().anyMatch(access -> access.id().contains("entry-b")));
	}

	@Test
	void retainsNormalAndStrictTransferAlternativesWithDistinctStrictEligibility() {
		var data = transferData();
		var accesses = normalize(data, MobilityProfile.STEP_FREE, ConstraintMode.REQUIRE_STEP_FREE);
		assertEquals(2, accesses.size());
		assertFalse(accesses.stream().filter(access -> access.id().contains("normal")).findFirst().orElseThrow().allowed());
		assertTrue(accesses.stream().filter(access -> access.id().contains("strict")).findFirst().orElseThrow().allowed());
	}

	@Test
	void missingPathwayWrongDirectionAndDuplicateJoinFailExplicitly() {
		var missing = new LoadRouteTimetablePort.RouteAccessData(List.of(), List.of(),
			List.of(new LoadRouteTimetablePort.TransferRule("rule", "a", "la", "b", "lb", "IN_STATION", 0, "missing", "missing", "VERIFIED")), List.of());
		assertThrows(JourneyProfileOracleAccessInputs.InputUnavailable.class,
			() -> normalize(missing, MobilityProfile.STANDARD, ConstraintMode.NONE));
		var wrongDirection = new LoadRouteTimetablePort.RouteAccessData(
			List.of(node("a", "station", "la"), node("b", "station", "lb")), List.of(edge("one-way", "b", "a", 1, 1, false)),
			List.of(new LoadRouteTimetablePort.TransferRule("rule", "station", "la", "station", "lb", "IN_STATION", 0, "one-way", "one-way", "VERIFIED")),
			List.of(evidence("transfer", "station", "lb", "one-way", "TRANSFER")));
		assertEquals("transfer direction", assertThrows(JourneyProfileOracleAccessInputs.InputUnavailable.class,
			() -> normalize(wrongDirection, MobilityProfile.STANDARD, ConstraintMode.NONE)).getMessage());
		var duplicate = new LoadRouteTimetablePort.RouteAccessData(List.of(node("a", "a", "la")),
			List.of(edge("duplicate", "a", "a", 1, 1, false), edge("duplicate", "a", "a", 1, 1, false)), List.of(), List.of());
		assertThrows(JourneyProfileOracleAccessInputs.InputUnavailable.class,
			() -> normalize(duplicate, MobilityProfile.STANDARD, ConstraintMode.NONE));
	}

	@Test
	void usesAdoptedDurationArithmeticAndKeepsOffNetworkEndpointsNull() {
		var data = new LoadRouteTimetablePort.RouteAccessData(
			List.of(node("outside", "station-a", null), node("platform-a", "station-a", "line-a"), node("platform-b", "station-b", "line-b"), node("exit", "station-b", null)),
			List.of(edge("entry", "outside", "platform-a", 100, 10, false), edge("exit", "platform-b", "exit", 100, 10, false)), List.of(),
			List.of(evidence("entry-e", "station-a", "line-a", "entry", "ENTRY"), evidence("exit-e", "station-b", "line-b", "exit", "EXIT")));
		var accesses = normalize(data, MobilityProfile.SLOW, ConstraintMode.NONE);
		var entry = accesses.stream().filter(access -> access.kind() == JourneyProfileExactOracle.AccessKind.ENTRY).findFirst().orElseThrow();
		var exit = accesses.stream().filter(access -> access.kind() == JourneyProfileExactOracle.AccessKind.EXIT).findFirst().orElseThrow();
		assertEquals(135, entry.durationSeconds()); assertEquals(135, exit.durationSeconds());
		assertNull(entry.fromLineId()); assertNull(exit.toLineId());
		var transfer = normalize(transferData(), MobilityProfile.STEP_FREE, ConstraintMode.REQUIRE_STEP_FREE).stream()
			.filter(access -> access.id().contains("strict")).findFirst().orElseThrow();
		assertEquals(160, transfer.durationSeconds());
	}

	private static List<JourneyProfileExactOracle.Access> normalize(LoadRouteTimetablePort.RouteAccessData data, MobilityProfile profile, ConstraintMode mode) {
		return JourneyProfileOracleAccessInputs.normalize(data, profile, mode, 3600, 20);
	}
	private static LoadRouteTimetablePort.RouteAccessData transferData() {
		return new LoadRouteTimetablePort.RouteAccessData(List.of(node("a", "station", "la"), node("b", "station", "lb")),
			List.of(edge("normal", "a", "b", 1, 100, false), edge("strict", "a", "b", 1, 100, false)),
			List.of(new LoadRouteTimetablePort.TransferRule("rule", "station", "la", "station", "lb", "IN_STATION", 0, "normal", "strict", "VERIFIED")),
			List.of(evidence("normal-e", "station", "lb", "normal", "TRANSFER"), evidence("strict-e", "station", "lb", "strict", "TRANSFER")));
	}

	@Test
	void keepsSameLineOuterEndpointsAndRejectsDuplicateEvidenceOrCapacity() {
		var item = evidence("entry-e", "s", "l", "entry", "ENTRY");
		var data = new LoadRouteTimetablePort.RouteAccessData(
			List.of(node("outside", "s", "l"), node("platform", "s", "l")),
			List.of(edge("entry", "outside", "platform", 1, 1, false)), List.of(), List.of(item));
		assertEquals("l", normalize(data, MobilityProfile.STANDARD, ConstraintMode.NONE).getFirst().fromLineId());
		var duplicate = new LoadRouteTimetablePort.RouteAccessData(data.pathwayNodes(), data.pathwayEdges(), List.of(),
			List.of(item, evidence("another-id", "s", "l", "entry", "ENTRY")));
		assertThrows(JourneyProfileOracleAccessInputs.InputUnavailable.class,
			() -> normalize(duplicate, MobilityProfile.STANDARD, ConstraintMode.NONE));
		assertThrows(JourneyProfileOracleAccessInputs.InputUnavailable.class,
			() -> JourneyProfileOracleAccessInputs.normalize(transferData(), MobilityProfile.STANDARD, ConstraintMode.NONE, 3600, 1));
	}

	@Test
	void deduplicatesSharedStrictEdgeAndAppliesWaitToMobilityNotConstraint() {
		var source = transferData();
		var shared = new LoadRouteTimetablePort.RouteAccessData(source.pathwayNodes(), List.of(source.pathwayEdges().getFirst()),
			List.of(new LoadRouteTimetablePort.TransferRule("rule", "station", "la", "station", "lb", "IN_STATION", 0,
				"normal", "normal", "VERIFIED")), List.of(source.routeEdgeEvidence().getFirst()));
		var strict = normalize(shared, MobilityProfile.NO_STAIRS, ConstraintMode.REQUIRE_STEP_FREE);
		assertEquals(1, strict.size());
		assertTrue(strict.getFirst().allowed());
		assertEquals(100, strict.getFirst().durationSeconds());
		assertEquals(160, normalize(shared, MobilityProfile.STEP_FREE, ConstraintMode.NONE).getFirst().durationSeconds());
		var normalOnly = new LoadRouteTimetablePort.RouteAccessData(shared.pathwayNodes(), shared.pathwayEdges(),
			List.of(new LoadRouteTimetablePort.TransferRule("rule", "station", "la", "station", "lb", "IN_STATION", 0,
				"normal", null, "VERIFIED")), shared.routeEdgeEvidence());
		assertEquals(1, normalize(normalOnly, MobilityProfile.STANDARD, ConstraintMode.NONE).size());
		assertFalse(normalize(normalOnly, MobilityProfile.NO_STAIRS, ConstraintMode.REQUIRE_STEP_FREE).getFirst().allowed());
	}
	private static LoadRouteTimetablePort.PathwayNode node(String id, String station, String line) { return new LoadRouteTimetablePort.PathwayNode(id, station, line, "PLATFORM"); }
	private static LoadRouteTimetablePort.PathwayEdge edge(String id, String from, String to, int seconds, int distance, boolean bidirectional) {
		return new LoadRouteTimetablePort.PathwayEdge(id, from, to, seconds, distance, bidirectional, false, 90, "AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED");
	}
	private static LoadRouteTimetablePort.RouteEdgeEvidence evidence(String id, String station, String line, String edge, String type) {
		return new LoadRouteTimetablePort.RouteEdgeEvidence(id, station, line, edge, type, "OFFICIAL_SOURCE", "VERIFIED", true, null);
	}
}

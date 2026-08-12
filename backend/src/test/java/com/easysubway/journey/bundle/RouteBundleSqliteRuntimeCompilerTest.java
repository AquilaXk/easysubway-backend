package com.easysubway.journey.bundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.journey.application.ActiveJourneySnapshotPort.ActiveJourneySnapshot;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.route.application.service.JourneyRaptorAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.luben.zstd.Zstd;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RouteBundleSqliteRuntimeCompilerTest {

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final String SHA = "a".repeat(64);
	private static final String STATION_SET_SHA = "b".repeat(64);
	private static final String BUNDLE_ID = "capital-20260812";
	private static final Instant DEPARTURE = Instant.parse("2026-08-12T00:50:00Z");

	@TempDir
	Path temp;

	@Test
	void compilesExactFourProducerPayloadsIntoOneWarmJourneyRuntime() throws Exception {
		var runtime = new RouteBundleSqliteRuntimeCompiler().compile(input(payloads()));

		assertThat(runtime.routeBundleSha256()).isEqualTo(SHA);
		assertThat(runtime.generation()).isEqualTo(7);

		var request = new JourneyRequest(
			"01HZY3Q4J5K6M7N8P9Q0R1S2T3",
			"station-a",
			"station-b",
			new JourneyRequest.Departure.Scheduled(DEPARTURE),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.MobilityProfile.STANDARD,
			JourneyRequest.ConstraintMode.NONE,
			0,
			1,
			() -> false);
		var snapshot = new ActiveJourneySnapshot(
			"active:7", BUNDLE_ID, SHA, "timetable", "accessibility", 7, runtime,
			DEPARTURE.plusSeconds(3600), true);

		var planned = new JourneyRaptorAdapter().plan(request, snapshot, DEPARTURE, null);

		assertThat(planned.queryId()).isEqualTo(request.requestId());
		assertThat(planned.candidates()).singleElement().satisfies(candidate -> {
			assertThat(candidate.legs()).hasSize(3);
			assertThat(candidate.transferCount()).isZero();
			assertThat(candidate.accessibility().stairFree()).isTrue();
		});
	}

	@Test
	void rejectsIncompleteCorruptOrIdentityMismatchedPayloads() throws Exception {
		var compiler = new RouteBundleSqliteRuntimeCompiler();
		var valid = payloads();

		var missing = new LinkedHashMap<>(valid);
		missing.remove(RouteBundleSqliteRuntimeCompiler.FARE_PATH);
		assertThatThrownBy(() -> compiler.compile(input(missing)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("payload inventory");

		var corrupt = new LinkedHashMap<>(valid);
		corrupt.put(RouteBundleSqliteRuntimeCompiler.TOPOLOGY_PATH, new byte[] {1, 2, 3});
		assertThatThrownBy(() -> compiler.compile(input(corrupt)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("zstd");

		var nullPayload = new LinkedHashMap<>(valid);
		nullPayload.put(RouteBundleSqliteRuntimeCompiler.TOPOLOGY_PATH, null);
		assertThatThrownBy(() -> input(nullPayload))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("payload bytes");

		assertThatThrownBy(() -> new RouteBundleSqliteRuntimeCompiler(1).compile(input(valid)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("total decompression limit");

		var wrongIdentity = payloads(identity -> identity.replace(BUNDLE_ID, "other-bundle"));
		assertThatThrownBy(() -> compiler.compile(input(wrongIdentity)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("component identity");
	}

	@Test
	void rejectsTopologyAccessibilityMutationOutsideTheAdmittedPayloadDigests() throws Exception {
		var admitted = payloads();
		var mutated = payloads(value -> value, "UNAVAILABLE");

		assertThatThrownBy(() -> new RouteBundleSqliteRuntimeCompiler().compile(
			input(mutated, payloadSha256s(admitted))))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("admitted payload digest");
	}

	@Test
	void rejectsAccessibilityEvidenceThatDoesNotCoverTheExactTopology() throws Exception {
		var payloads = payloads();
		var accessibility = sqlite("accessibility-drift", connection -> {
			common(connection, identitySql());
			execute(connection, "CREATE TABLE route_accessibility_edge_evidence (evaluation_digest TEXT NOT NULL PRIMARY KEY, materialization_digest TEXT NOT NULL, canonical_json TEXT NOT NULL)");
			var edges = topologyEdges();
			edges.remove(edges.size() - 1);
			var evaluation = evaluation(edges);
			insert(connection, "INSERT INTO route_accessibility_edge_evidence VALUES(?,?,?)",
				evaluation.path("evaluationDigest").textValue(), "c".repeat(64), canonical(evaluation));
		});
		payloads.put(RouteBundleSqliteRuntimeCompiler.ACCESSIBILITY_PATH, Zstd.compress(accessibility, 10));

		assertThatThrownBy(() -> new RouteBundleSqliteRuntimeCompiler().compile(input(payloads)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("accessibility evidence");
	}

	@Test
	void rejectsNonPassAccessibilityForAJourneyProjectedEdge() throws Exception {
		var payloads = payloads();
		var accessibility = sqlite("accessibility-blocked", connection -> {
			common(connection, identitySql());
			execute(connection, "CREATE TABLE route_accessibility_edge_evidence (evaluation_digest TEXT NOT NULL PRIMARY KEY, materialization_digest TEXT NOT NULL, canonical_json TEXT NOT NULL)");
			var evaluation = evaluation(topologyEdges(), Map.of("entry-a", "BLOCKED"));
			insert(connection, "INSERT INTO route_accessibility_edge_evidence VALUES(?,?,?)",
				evaluation.path("evaluationDigest").textValue(), "c".repeat(64), canonical(evaluation));
		});
		payloads.put(RouteBundleSqliteRuntimeCompiler.ACCESSIBILITY_PATH, Zstd.compress(accessibility, 10));

		assertThatThrownBy(() -> new RouteBundleSqliteRuntimeCompiler().compile(input(payloads)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("not PASS");
	}

	private RouteBundleSqliteRuntimeCompiler.Input input(Map<String, byte[]> payloads) {
		return input(payloads, payloadSha256s(payloads));
	}

	private RouteBundleSqliteRuntimeCompiler.Input input(
		Map<String, byte[]> payloads,
		Map<String, String> admittedPayloadSha256s
	) {
		return new RouteBundleSqliteRuntimeCompiler.Input(
			SHA, 7, BUNDLE_ID, 11, STATION_SET_SHA, admittedPayloadSha256s, payloads);
	}

	private Map<String, byte[]> payloads() throws Exception {
		return payloads(value -> value);
	}

	private Map<String, byte[]> payloads(java.util.function.UnaryOperator<String> identityTransform) throws Exception {
		return payloads(identityTransform, "AVAILABLE");
	}

	private Map<String, byte[]> payloads(
		java.util.function.UnaryOperator<String> identityTransform,
		String accessibilityStatus
	) throws Exception {
		var topology = sqlite("topology", connection -> {
			common(connection, identityTransform.apply(identitySql()));
			execute(connection, """
				CREATE TABLE network_edges (
				 id TEXT PRIMARY KEY, from_node_id TEXT NOT NULL, to_node_id TEXT NOT NULL,
				 duration_seconds INTEGER NOT NULL, distance_meters INTEGER NOT NULL,
				 edge_type TEXT NOT NULL, service_pattern TEXT NOT NULL, service_class TEXT NOT NULL,
				 includes_stairs INTEGER NOT NULL, stair_access_state TEXT NOT NULL,
				 accessibility_status TEXT NOT NULL, reliability_score INTEGER NOT NULL,
				 source_id TEXT NOT NULL, source_snapshot_id TEXT NOT NULL,
				 provider_record_hash TEXT NOT NULL, provenance_kind TEXT NOT NULL,
				 verification_status TEXT NOT NULL, facility_id TEXT,
				 last_verified_at INTEGER, evidence_hash TEXT NOT NULL)
				""");
			for (var edge : topologyEdges()) {
				insert(connection, "INSERT INTO network_edges VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
					edge.id(), edge.from(), edge.to(), edge.duration(), edge.distance(), edge.type(), edge.pattern(),
					edge.serviceClass(), 0, "VERIFIED_PRESENT", accessibilityStatus, 100, "official", "snapshot",
					"d".repeat(64), "OFFICIAL_SOURCE", "VERIFIED", null, 1_786_485_600_000L,
					"e".repeat(64));
			}
		});
		var timetable = sqlite("timetable", connection -> {
			common(connection, identityTransform.apply(identitySql()));
			execute(connection, """
				CREATE TABLE service_calendars (service_id TEXT PRIMARY KEY, monday INTEGER NOT NULL,
				 tuesday INTEGER NOT NULL, wednesday INTEGER NOT NULL, thursday INTEGER NOT NULL,
				 friday INTEGER NOT NULL, saturday INTEGER NOT NULL, sunday INTEGER NOT NULL,
				 start_date TEXT NOT NULL, end_date TEXT NOT NULL, timezone TEXT NOT NULL);
				CREATE TABLE service_calendar_dates (service_id TEXT NOT NULL, date TEXT NOT NULL,
				 exception_type INTEGER NOT NULL, PRIMARY KEY(service_id,date));
				CREATE TABLE transit_routes (id TEXT PRIMARY KEY, line_id TEXT NOT NULL,
				 route_short_name TEXT NOT NULL, route_long_name TEXT NOT NULL,
				 direction_name TEXT NOT NULL, timezone TEXT NOT NULL);
				CREATE TABLE transit_trips (id TEXT PRIMARY KEY, route_id TEXT NOT NULL,
				 service_id TEXT NOT NULL, trip_headsign TEXT NOT NULL, direction_id TEXT NOT NULL,
				 service_pattern TEXT NOT NULL, service_class TEXT NOT NULL,
				 service_day_start_seconds INTEGER NOT NULL);
				CREATE TABLE transit_stop_times (trip_id TEXT NOT NULL, stop_sequence INTEGER NOT NULL,
				 station_id TEXT NOT NULL, line_id TEXT NOT NULL, arrival_seconds INTEGER NOT NULL,
				 departure_seconds INTEGER NOT NULL, pickup_type INTEGER NOT NULL,
				 drop_off_type INTEGER NOT NULL, PRIMARY KEY(trip_id,stop_sequence));
				CREATE TABLE transit_frequencies (trip_id TEXT NOT NULL, start_time_seconds INTEGER NOT NULL,
				 end_time_seconds INTEGER NOT NULL, headway_seconds INTEGER NOT NULL,
				 exact_times INTEGER NOT NULL, PRIMARY KEY(trip_id,start_time_seconds));
				CREATE TABLE transit_feed_info (id INTEGER PRIMARY KEY, feed_end_date TEXT NOT NULL)
				""");
			insert(connection, "INSERT INTO service_calendars VALUES(?,?,?,?,?,?,?,?,?,?,?)",
				"weekday", 1, 1, 1, 1, 1, 1, 1, "20260801", "20261231", "Asia/Seoul");
			insert(connection, "INSERT INTO transit_routes VALUES(?,?,?,?,?,?)",
				"route-1", "line-1", "1", "Line 1", "station-b", "Asia/Seoul");
			insert(connection, "INSERT INTO transit_trips VALUES(?,?,?,?,?,?,?,?)",
				"trip-1", "route-1", "weekday", "station-b", "0", "LOCAL", "SUBWAY", 0);
			insert(connection, "INSERT INTO transit_stop_times VALUES(?,?,?,?,?,?,?,?)",
				"trip-1", 1, "station-a", "line-1", 36000, 36000, 0, 0);
			insert(connection, "INSERT INTO transit_stop_times VALUES(?,?,?,?,?,?,?,?)",
				"trip-1", 2, "station-b", "line-1", 36600, 36600, 0, 0);
			insert(connection, "INSERT INTO transit_feed_info VALUES(?,?)", 1, "20261231");
		});
		var accessibility = sqlite("accessibility", connection -> {
			common(connection, identityTransform.apply(identitySql()));
			execute(connection, "CREATE TABLE route_accessibility_edge_evidence (evaluation_digest TEXT NOT NULL PRIMARY KEY, materialization_digest TEXT NOT NULL, canonical_json TEXT NOT NULL)");
			var evaluation = evaluation(topologyEdges());
			insert(connection, "INSERT INTO route_accessibility_edge_evidence VALUES(?,?,?)",
				evaluation.path("evaluationDigest").textValue(), "c".repeat(64), canonical(evaluation));
		});
		var fare = sqlite("fare", connection -> {
			common(connection, identityTransform.apply(identitySql()));
			execute(connection, """
				CREATE TABLE official_od_fare_quotes (origin_station_id TEXT NOT NULL,
				 destination_station_id TEXT NOT NULL, source_id TEXT NOT NULL, snapshot_id TEXT NOT NULL,
				 mapping_ledger_hash TEXT NOT NULL, gnrl_card_fare INTEGER NOT NULL,
				 gnrl_cash_fare INTEGER NOT NULL, yung_card_fare INTEGER NOT NULL,
				 yung_cash_fare INTEGER NOT NULL, child_card_fare INTEGER NOT NULL,
				 child_cash_fare INTEGER NOT NULL, PRIMARY KEY(origin_station_id,destination_station_id))
				""");
			insert(connection, "INSERT INTO official_od_fare_quotes VALUES(?,?,?,?,?,?,?,?,?,?,?)",
				"station-a", "station-b", "official", "snapshot", "f".repeat(64), 1400, 1500, 800, 900, 500, 600);
		});
		var result = new LinkedHashMap<String, byte[]>();
		result.put(RouteBundleSqliteRuntimeCompiler.TOPOLOGY_PATH, Zstd.compress(topology, 10));
		result.put(RouteBundleSqliteRuntimeCompiler.TIMETABLE_PATH, Zstd.compress(timetable, 10));
		result.put(RouteBundleSqliteRuntimeCompiler.ACCESSIBILITY_PATH, Zstd.compress(accessibility, 10));
		result.put(RouteBundleSqliteRuntimeCompiler.FARE_PATH, Zstd.compress(fare, 10));
		return result;
	}

	private static Map<String, String> payloadSha256s(Map<String, byte[]> payloads) {
		var result = new LinkedHashMap<String, String>();
		payloads.forEach((path, bytes) -> result.put(path, bytes == null ? SHA : sha256Unchecked(bytes)));
		return result;
	}

	private static String sha256Unchecked(byte[] bytes) {
		try {
			return sha256(bytes);
		} catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}

	private byte[] sqlite(String name, SqliteWriter writer) throws Exception {
		var file = Files.createTempFile(temp, name + "-", ".sqlite");
		try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file)) {
			writer.write(connection);
		}
		return Files.readAllBytes(file);
	}

	private static void common(Connection connection, String identitySql) throws Exception {
		execute(connection, "PRAGMA user_version=19; " + identitySql + """
			; CREATE TABLE stations (id TEXT PRIMARY KEY)
			; CREATE TABLE lines (id TEXT PRIMARY KEY)
			; CREATE TABLE station_lines (station_id TEXT NOT NULL, line_id TEXT NOT NULL,
			  line_sequence INTEGER NOT NULL, PRIMARY KEY(station_id,line_id))
			""");
		insert(connection, "INSERT INTO stations VALUES(?)", "station-a");
		insert(connection, "INSERT INTO stations VALUES(?)", "station-b");
		insert(connection, "INSERT INTO lines VALUES(?)", "line-1");
		insert(connection, "INSERT INTO station_lines VALUES(?,?,?)", "station-a", "line-1", 1);
		insert(connection, "INSERT INTO station_lines VALUES(?,?,?)", "station-b", "line-1", 2);
	}

	private static String identitySql() {
		return "CREATE TABLE artifact_component_identity (bundleId TEXT NOT NULL, releaseSequence INTEGER NOT NULL, stationSetSha256 TEXT NOT NULL, serviceTimezone TEXT NOT NULL);"
			+ "INSERT INTO artifact_component_identity VALUES('" + BUNDLE_ID + "',11,'" + STATION_SET_SHA + "','Asia/Seoul')";
	}

	private static List<Edge> topologyEdges() {
		return new ArrayList<>(List.of(
			new Edge("entry-a", "station-a", "station-a:line-1:platform-a", 120, 60, "ENTRY", "", "SUBWAY"),
			new Edge("ride-a-b", "station-a:line-1:platform-a", "station-b:line-1:platform-b", 600, 1000, "RIDE", "LOCAL", "SUBWAY"),
			new Edge("exit-b", "station-b:line-1:platform-b", "station-b", 60, 40, "EXIT", "", "SUBWAY")));
	}

	private static ObjectNode evaluation(List<Edge> edges) throws Exception {
		return evaluation(edges, Map.of());
	}

	private static ObjectNode evaluation(List<Edge> edges, Map<String, String> states) throws Exception {
		var results = JSON.createArrayNode();
		var stateCounts = new LinkedHashMap<String, Integer>();
		for (var edge : edges) {
			String state = states.getOrDefault(edge.id(), "PASS");
			stateCounts.merge(state, 1, Integer::sum);
			var withoutEvidence = JSON.createObjectNode();
			withoutEvidence.put("edgeId", edge.id());
			withoutEvidence.put("edgeType", edge.type());
			withoutEvidence.set("from", endpoint(edge.from()));
			withoutEvidence.set("to", endpoint(edge.to()));
			withoutEvidence.put("servicePattern", edge.pattern());
			withoutEvidence.put("serviceClass", edge.serviceClass());
			withoutEvidence.set("requiredDomains", JSON.createArrayNode());
			withoutEvidence.put("state", state);
			withoutEvidence.put("reason", "verified fixture");
			withoutEvidence.put("rawEdgeSha256", edgeSha(edge));
			withoutEvidence.put("materializationDigest", "c".repeat(64));
			withoutEvidence.set("materializationCells", JSON.createArrayNode());
			withoutEvidence.put("topologySha256", "d".repeat(64));
			withoutEvidence.put("policyVersion", "route-edge-evaluation-v1");
			withoutEvidence.put("evaluatorVersion", "1");
			withoutEvidence.put("evaluationAt", "2026-08-12T00:00:00.000Z");
			var result = withoutEvidence.deepCopy();
			result.put("evidenceSha256", sha256(canonical(withoutEvidence).getBytes(StandardCharsets.UTF_8)));
			results.add(result);
		}
		var payload = JSON.createObjectNode();
		payload.set("candidate", JSON.createObjectNode().put("candidateId", BUNDLE_ID));
		payload.put("evaluationAt", "2026-08-12T00:00:00.000Z");
		payload.set("denominator", JSON.createObjectNode().put("edgeCount", edges.size()).put("digest", "e".repeat(64)));
		payload.set("results", results);
		var stateSummary = JSON.createObjectNode();
		stateCounts.forEach(stateSummary::put);
		payload.set("stateSummary", stateSummary);
		payload.put("eligible", true);
		var result = payload.deepCopy();
		result.put("evaluationDigest", sha256(canonical(payload).getBytes(StandardCharsets.UTF_8)));
		return result;
	}

	private static ObjectNode endpoint(String value) {
		var parts = value.split(":", -1);
		var endpoint = JSON.createObjectNode().put("stationId", parts[0]);
		if (parts.length == 1) {
			endpoint.putNull("lineId").putNull("operatorId").putNull("lineSequence");
		} else {
			endpoint.put("lineId", parts[1]).put("operatorId", "operator-1").put("lineSequence",
				"station-a".equals(parts[0]) ? 1 : 2);
		}
		return endpoint;
	}

	private static String edgeSha(Edge edge) throws Exception {
		var raw = JSON.createObjectNode();
		raw.put("edgeId", edge.id());
		raw.put("edgeType", edge.type());
		raw.put("fromNodeId", edge.from());
		raw.put("toNodeId", edge.to());
		raw.put("durationSeconds", edge.duration());
		raw.put("distanceMeters", edge.distance());
		raw.put("servicePattern", edge.pattern());
		raw.put("serviceClass", edge.serviceClass());
		return sha256(canonical(raw).getBytes(StandardCharsets.UTF_8));
	}

	private static String canonical(JsonNode node) throws Exception {
		return JSON.writeValueAsString(sorted(node));
	}

	private static JsonNode sorted(JsonNode node) {
		if (node.isObject()) {
			var result = JSON.createObjectNode();
			var fields = new ArrayList<String>();
			node.fieldNames().forEachRemaining(fields::add);
			fields.sort(Comparator.naturalOrder());
			for (var field : fields) result.set(field, sorted(node.get(field)));
			return result;
		}
		if (node.isArray()) {
			ArrayNode result = JSON.createArrayNode();
			node.forEach(value -> result.add(sorted(value)));
			return result;
		}
		return node.deepCopy();
	}

	private static String sha256(byte[] bytes) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}

	private static void execute(Connection connection, String sql) throws Exception {
		try (var statement = connection.createStatement()) {
			statement.executeUpdate(sql);
		}
	}

	private static void insert(Connection connection, String sql, Object... values) throws Exception {
		try (var statement = connection.prepareStatement(sql)) {
			for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
			statement.executeUpdate();
		}
	}

	@FunctionalInterface
	private interface SqliteWriter {
		void write(Connection connection) throws Exception;
	}

	private record Edge(
		String id, String from, String to, int duration, int distance, String type, String pattern,
		String serviceClass) {
	}
}

package com.easysubway.journey.bundle;

import com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayEdge;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayNode;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteAccessData;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteEdgeEvidence;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.ServiceCalendar;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.ServiceCalendarDate;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitFrequency;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitRoute;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitStopTime;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitTrip;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransferRule;
import com.easysubway.route.application.service.RaptorRouteBundleRuntimeView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.luben.zstd.ZstdInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Compiles already-admitted Data component bytes into one Journey RAPTOR runtime. */
public final class RouteBundleSqliteRuntimeCompiler {

	static final String TOPOLOGY_PATH = "payload/topology.sqlite.zst";
	static final String TIMETABLE_PATH = "payload/timetable.sqlite.zst";
	static final String ACCESSIBILITY_PATH = "payload/accessibility.sqlite.zst";
	static final String FARE_PATH = "payload/fare.sqlite.zst";
	private static final Set<String> PAYLOAD_PATHS = Set.of(
		TOPOLOGY_PATH, TIMETABLE_PATH, ACCESSIBILITY_PATH, FARE_PATH);
	private static final long MAX_COMPONENT_BYTES = 512L * 1024L * 1024L;
	private static final int SQLITE_USER_VERSION = 19;
	private static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");
	private static final ObjectMapper JSON = new ObjectMapper();
	private static final DateTimeFormatter SERVICE_DATE = DateTimeFormatter.BASIC_ISO_DATE;

	public RaptorRouteBundleRuntimeView compile(Input input) {
		Objects.requireNonNull(input, "input");
		Map<String, byte[]> payloads = input.compressedPayloads();
		if (!payloads.keySet().equals(PAYLOAD_PATHS)) {
			throw new IllegalArgumentException("route-bundle payload inventory is invalid");
		}
		Path directory = null;
		var components = new ArrayList<Component>();
		try {
			directory = Files.createTempDirectory("journey-route-bundle-");
			for (String payloadPath : List.of(TOPOLOGY_PATH, TIMETABLE_PATH, ACCESSIBILITY_PATH, FARE_PATH)) {
				Path sqlite = directory.resolve(payloadPath.substring("payload/".length()).replace(".zst", ""));
				decompress(payloads.get(payloadPath), sqlite);
				components.add(open(payloadPath, sqlite, input));
			}
			Map<String, Component> byPath = new HashMap<>();
			components.forEach(component -> byPath.put(component.payloadPath(), component));
			requireEqualReferences(components);
			var topology = loadTopology(byPath.get(TOPOLOGY_PATH).connection());
			var evaluations = validateAccessibility(
				byPath.get(ACCESSIBILITY_PATH).connection(), input.bundleId(), topology);
			validateFare(byPath.get(FARE_PATH).connection());
			RouteTimetable timetable = loadTimetable(
				byPath.get(TIMETABLE_PATH).connection(), topology, evaluations);
			return RaptorRouteBundleRuntimeView.compile(
				input.routeBundleSha256(), input.generation(), timetable);
		} catch (IOException | SQLException exception) {
			throw new IllegalArgumentException("route-bundle SQLite runtime compilation failed", exception);
		} finally {
			for (var component : components) component.closeQuietly();
			if (directory != null) deleteDirectory(directory);
		}
	}

	/** Image preflight entry point: proves that the pinned native library loads without /tmp extraction. */
	public static void main(String[] arguments) throws SQLException {
		if (arguments.length != 0) throw new IllegalArgumentException("SQLite runtime probe takes no arguments");
		try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:");
			var statement = connection.createStatement();
			var rows = statement.executeQuery("SELECT sqlite_version()")) {
			if (!rows.next() || !"3.53.2".equals(rows.getString(1)) || rows.next()) {
				throw new IllegalStateException("SQLite runtime version mismatch");
			}
		}
	}

	private static Component open(String payloadPath, Path sqlite, Input input) throws SQLException {
		Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sqlite.toAbsolutePath());
		try {
			execute(connection, "PRAGMA query_only=ON");
			requireSqliteIntegrity(connection);
			requireColumns(connection, "artifact_component_identity",
				List.of("bundleId", "releaseSequence", "stationSetSha256", "serviceTimezone"));
			requireColumns(connection, "stations", List.of("id"));
			requireColumns(connection, "lines", List.of("id"));
			requireColumns(connection, "station_lines", List.of("station_id", "line_id", "line_sequence"));
			requireComponentIdentity(connection, input);
			return new Component(payloadPath, connection, references(connection));
		} catch (RuntimeException | SQLException exception) {
			connection.close();
			throw exception;
		}
	}

	private static void requireSqliteIntegrity(Connection connection) throws SQLException {
		if (querySingleInt(connection, "PRAGMA user_version") != SQLITE_USER_VERSION) {
			throw new IllegalArgumentException("SQLite user_version is not 19");
		}
		try (var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA integrity_check")) {
			if (!rows.next() || !"ok".equals(rows.getString(1)) || rows.next()) {
				throw new IllegalArgumentException("SQLite integrity check failed");
			}
		}
		try (var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA foreign_key_check")) {
			if (rows.next()) throw new IllegalArgumentException("SQLite foreign key check failed");
		}
	}

	private static void requireComponentIdentity(Connection connection, Input input) throws SQLException {
		try (var statement = connection.createStatement();
			var rows = statement.executeQuery("SELECT bundleId, releaseSequence, stationSetSha256, serviceTimezone FROM artifact_component_identity")) {
			if (!rows.next()
				|| !input.bundleId().equals(rows.getString(1))
				|| input.releaseSequence() != rows.getLong(2)
				|| !input.stationSetSha256().equals(rows.getString(3))
				|| !"Asia/Seoul".equals(rows.getString(4))
				|| rows.next()) {
				throw new IllegalArgumentException("route-bundle component identity mismatch");
			}
		}
	}

	private static References references(Connection connection) throws SQLException {
		return new References(
			queryStrings(connection, "SELECT id FROM stations ORDER BY id COLLATE BINARY", 1),
			queryStrings(connection, "SELECT id FROM lines ORDER BY id COLLATE BINARY", 1),
			queryStrings(connection,
				"SELECT station_id, line_id, line_sequence FROM station_lines ORDER BY station_id COLLATE BINARY, line_id COLLATE BINARY",
				3));
	}

	private static void requireEqualReferences(List<Component> components) {
		References expected = components.getFirst().references();
		if (expected.stations().isEmpty() || expected.lines().isEmpty() || expected.stationLines().isEmpty()
			|| components.stream().anyMatch(component -> !expected.equals(component.references()))) {
			throw new IllegalArgumentException("route-bundle component reference rows mismatch");
		}
	}

	private static Map<String, TopologyEdge> loadTopology(Connection connection) throws SQLException {
		requireColumns(connection, "network_edges", List.of(
			"id", "from_node_id", "to_node_id", "duration_seconds", "distance_meters", "edge_type",
			"service_pattern", "service_class", "includes_stairs", "stair_access_state",
			"accessibility_status", "reliability_score", "source_id", "source_snapshot_id",
			"provider_record_hash", "provenance_kind", "verification_status", "facility_id",
			"last_verified_at", "evidence_hash"));
		var edges = new LinkedHashMap<String, TopologyEdge>();
		try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
			SELECT id, from_node_id, to_node_id, duration_seconds, distance_meters, edge_type,
			 service_pattern, service_class, includes_stairs, accessibility_status, reliability_score,
			 provenance_kind, verification_status
			FROM network_edges ORDER BY id COLLATE BINARY
			""")) {
			while (rows.next()) {
				var edge = new TopologyEdge(
					requireText(rows.getString(1), "network edge id"),
					requireText(rows.getString(2), "network edge from"),
					requireText(rows.getString(3), "network edge to"),
					rows.getInt(4), rows.getInt(5), requireText(rows.getString(6), "network edge type"),
					rows.getString(7), requireText(rows.getString(8), "network edge service class"),
					rows.getBoolean(9), requireText(rows.getString(10), "network edge accessibility status"),
					rows.getInt(11), requireText(rows.getString(12), "network edge provenance"),
					requireText(rows.getString(13), "network edge verification"));
				if (edge.durationSeconds() < 0 || edge.distanceMeters() < 0
					|| edge.reliabilityScore() < 0 || edge.reliabilityScore() > 100
					|| edges.put(edge.id(), edge) != null) {
					throw new IllegalArgumentException("topology network edge is invalid");
				}
			}
		}
		if (edges.isEmpty()) throw new IllegalArgumentException("topology network edges are empty");
		return Map.copyOf(edges);
	}

	private static Map<String, Evaluation> validateAccessibility(
		Connection connection, String bundleId, Map<String, TopologyEdge> topology) throws SQLException {
		requireColumns(connection, "route_accessibility_edge_evidence",
			List.of("evaluation_digest", "materialization_digest", "canonical_json"));
		try (var statement = connection.createStatement(); var rows = statement.executeQuery(
			"SELECT evaluation_digest, materialization_digest, canonical_json FROM route_accessibility_edge_evidence")) {
			if (!rows.next()) throw new IllegalArgumentException("accessibility evidence is missing");
			String evaluationDigest = requireSha256(rows.getString(1), "evaluation digest");
			String materializationDigest = requireSha256(rows.getString(2), "materialization digest");
			String rawJson = requireText(rows.getString(3), "accessibility evidence JSON");
			if (rows.next()) throw new IllegalArgumentException("accessibility evidence must contain one row");
			try {
				JsonNode root = JSON.readTree(rawJson);
				if (!rawJson.equals(canonical(root)) || !root.isObject()
					|| !evaluationDigest.equals(root.path("evaluationDigest").textValue())
					|| !bundleId.equals(root.path("candidate").path("candidateId").textValue())
					|| !root.path("eligible").isBoolean() || !root.path("eligible").booleanValue()) {
					throw new IllegalArgumentException("accessibility evidence identity is invalid");
				}
				ObjectNode unsigned = ((ObjectNode) root).deepCopy();
				unsigned.remove("evaluationDigest");
				if (!evaluationDigest.equals(sha256(canonical(unsigned).getBytes(StandardCharsets.UTF_8)))) {
					throw new IllegalArgumentException("accessibility evidence digest mismatch");
				}
				JsonNode results = root.path("results");
				if (!results.isArray() || results.size() != topology.size()
					|| root.path("denominator").path("edgeCount").intValue() != topology.size()) {
					throw new IllegalArgumentException("accessibility evidence does not cover topology");
				}
				var evaluations = new HashMap<String, Evaluation>();
				for (JsonNode result : results) {
					String edgeId = requireText(result.path("edgeId").textValue(), "evaluated edge id");
					TopologyEdge edge = topology.get(edgeId);
					String state = requireText(result.path("state").textValue(), "evaluated edge state");
					if (edge == null || evaluations.containsKey(edgeId)
						|| !edge.type().equals(result.path("edgeType").textValue())
						|| !Set.of("PASS", "BLOCKED", "NOT_APPLICABLE").contains(state)
						|| !materializationDigest.equals(result.path("materializationDigest").textValue())
						|| !edge.rawSha256().equals(result.path("rawEdgeSha256").textValue())) {
						throw new IllegalArgumentException("accessibility evidence result mismatch");
					}
					ObjectNode unsignedResult = ((ObjectNode) result).deepCopy();
					String resultDigest = unsignedResult.path("evidenceSha256").textValue();
					unsignedResult.remove("evidenceSha256");
					if (!requireSha256(resultDigest, "edge evidence digest")
						.equals(sha256(canonical(unsignedResult).getBytes(StandardCharsets.UTF_8)))) {
						throw new IllegalArgumentException("accessibility edge evidence digest mismatch");
					}
					evaluations.put(edgeId, new Evaluation(state, result.path("reason").textValue()));
				}
				if (!evaluations.keySet().equals(topology.keySet())) {
					throw new IllegalArgumentException("accessibility evidence does not cover topology");
				}
				return Map.copyOf(evaluations);
			} catch (IOException exception) {
				throw new IllegalArgumentException("accessibility evidence JSON is invalid", exception);
			}
		}
	}

	private static void validateFare(Connection connection) throws SQLException {
		requireColumns(connection, "official_od_fare_quotes", List.of(
			"origin_station_id", "destination_station_id", "source_id", "snapshot_id", "mapping_ledger_hash",
			"gnrl_card_fare", "gnrl_cash_fare", "yung_card_fare", "yung_cash_fare",
			"child_card_fare", "child_cash_fare"));
		try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
			SELECT origin_station_id, destination_station_id, source_id, snapshot_id, mapping_ledger_hash,
			 gnrl_card_fare, gnrl_cash_fare, yung_card_fare, yung_cash_fare, child_card_fare, child_cash_fare
			FROM official_od_fare_quotes
			""")) {
			while (rows.next()) {
				requireText(rows.getString(1), "fare origin station");
				requireText(rows.getString(2), "fare destination station");
				requireText(rows.getString(3), "fare source");
				requireText(rows.getString(4), "fare snapshot");
				requireSha256(rows.getString(5), "fare mapping ledger");
				for (int column = 6; column <= 11; column++) {
					if (rows.getInt(column) < 0) throw new IllegalArgumentException("fare value is invalid");
				}
			}
		}
	}

	private static RouteTimetable loadTimetable(
		Connection connection, Map<String, TopologyEdge> topology, Map<String, Evaluation> evaluations)
		throws SQLException {
		requireTimetableColumns(connection);
		var calendars = new ArrayList<ServiceCalendar>();
		try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
			SELECT service_id, monday, tuesday, wednesday, thursday, friday, saturday, sunday,
			 start_date, end_date, timezone FROM service_calendars ORDER BY service_id COLLATE BINARY
			""")) {
			while (rows.next()) calendars.add(new ServiceCalendar(
				rows.getString(1), rows.getBoolean(2), rows.getBoolean(3), rows.getBoolean(4), rows.getBoolean(5),
				rows.getBoolean(6), rows.getBoolean(7), rows.getBoolean(8), serviceDate(rows.getString(9)),
				serviceDate(rows.getString(10)), rows.getString(11)));
		}
		var calendarDates = new ArrayList<ServiceCalendarDate>();
		try (var statement = connection.createStatement(); var rows = statement.executeQuery(
			"SELECT service_id, date, exception_type FROM service_calendar_dates ORDER BY service_id COLLATE BINARY, date COLLATE BINARY")) {
			while (rows.next()) calendarDates.add(new ServiceCalendarDate(
				rows.getString(1), serviceDate(rows.getString(2)), rows.getInt(3)));
		}
		var routes = new ArrayList<TransitRoute>();
		try (var statement = connection.createStatement(); var rows = statement.executeQuery(
			"SELECT id, line_id, route_short_name, route_long_name, direction_name, timezone FROM transit_routes ORDER BY id COLLATE BINARY")) {
			while (rows.next()) routes.add(new TransitRoute(
				rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getString(5), rows.getString(6)));
		}
		var trips = new ArrayList<TransitTrip>();
		try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
			SELECT id, route_id, service_id, trip_headsign, direction_id, service_class, service_pattern,
			 service_day_start_seconds FROM transit_trips ORDER BY id COLLATE BINARY
			""")) {
			while (rows.next()) trips.add(new TransitTrip(
				rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getString(5),
				rows.getString(6), rows.getString(7), null, rows.getInt(8)));
		}
		var stopTimes = new ArrayList<TransitStopTime>();
		try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
			SELECT trip_id, stop_sequence, station_id, line_id, arrival_seconds, departure_seconds,
			 pickup_type, drop_off_type FROM transit_stop_times ORDER BY trip_id COLLATE BINARY, stop_sequence
			""")) {
			while (rows.next()) stopTimes.add(new TransitStopTime(
				rows.getString(1), rows.getInt(2), rows.getString(3), rows.getString(4), rows.getInt(5),
				rows.getInt(6), rows.getInt(7), rows.getInt(8)));
		}
		var frequencies = new ArrayList<TransitFrequency>();
		try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
			SELECT trip_id, start_time_seconds, end_time_seconds, headway_seconds, exact_times
			FROM transit_frequencies ORDER BY trip_id COLLATE BINARY, start_time_seconds
			""")) {
			while (rows.next()) frequencies.add(new TransitFrequency(
				rows.getString(1), rows.getInt(2), rows.getInt(3), rows.getInt(4), rows.getBoolean(5)));
		}
		LocalDate feedEndDate = null;
		try (var statement = connection.createStatement();
			var rows = statement.executeQuery("SELECT feed_end_date FROM transit_feed_info WHERE id=1")) {
			if (rows.next()) feedEndDate = serviceDate(rows.getString(1));
			if (rows.next()) throw new IllegalArgumentException("transit feed info must contain one row");
		}
		if (calendars.isEmpty() || routes.isEmpty() || trips.isEmpty() || stopTimes.isEmpty()) {
			throw new IllegalArgumentException("route-bundle timetable is empty");
		}
		return new RouteTimetable(
			calendars, calendarDates, routes, trips, stopTimes, frequencies, List.of(), feedEndDate,
			projectAccess(topology, evaluations));
	}

	private static RouteAccessData projectAccess(
		Map<String, TopologyEdge> topology, Map<String, Evaluation> evaluations) {
		var nodes = new LinkedHashMap<String, PathwayNode>();
		var edges = new ArrayList<PathwayEdge>();
		var rules = new ArrayList<TransferRule>();
		var evidence = new ArrayList<RouteEdgeEvidence>();
		for (var edge : topology.values().stream().sorted(Comparator.comparing(TopologyEdge::id)).toList()) {
			if (!Set.of("ENTRY", "EXIT", "IN_STATION_TRANSFER").contains(edge.type())) continue;
			Evaluation evaluation = evaluations.get(edge.id());
			if (!"PASS".equals(evaluation.state())) {
				throw new IllegalArgumentException("projected accessibility edge is not PASS");
			}
			Endpoint from = endpoint(edge.fromNodeId());
			Endpoint to = endpoint(edge.toNodeId());
			requireEndpointShape(edge.type(), from, to);
			nodes.putIfAbsent(edge.fromNodeId(), new PathwayNode(edge.fromNodeId(), from.stationId(), from.lineId(), "ROUTE_ENDPOINT"));
			nodes.putIfAbsent(edge.toNodeId(), new PathwayNode(edge.toNodeId(), to.stationId(), to.lineId(), "ROUTE_ENDPOINT"));
			edges.add(new PathwayEdge(
				edge.id(), edge.fromNodeId(), edge.toNodeId(), edge.durationSeconds(), edge.distanceMeters(), false,
				edge.includesStairs(), edge.reliabilityScore(), edge.accessibilityStatus(), edge.provenanceKind(),
				edge.verificationStatus(), edge.id()));
			String evidenceType;
			String stationId;
			String lineId;
			if ("ENTRY".equals(edge.type())) {
				evidenceType = "ENTRY";
				stationId = to.stationId();
				lineId = to.lineId();
			} else if ("EXIT".equals(edge.type())) {
				evidenceType = "EXIT";
				stationId = from.stationId();
				lineId = from.lineId();
			} else {
				evidenceType = "TRANSFER";
				stationId = to.stationId();
				lineId = to.lineId();
				String strictEdge = edge.includesStairs() ? null : edge.id();
				rules.add(new TransferRule(
					edge.id(), from.stationId(), from.lineId(), to.stationId(), to.lineId(), "IN_STATION",
					edge.durationSeconds(), edge.id(), strictEdge, "VERIFIED"));
			}
			evidence.add(new RouteEdgeEvidence(
				edge.id(), stationId, lineId, edge.id(), evidenceType, edge.provenanceKind(), "VERIFIED", true,
				"PASS".equals(evaluation.state()) ? null : evaluation.reason()));
		}
		return new RouteAccessData(List.copyOf(nodes.values()), edges, rules, evidence);
	}

	private static void requireEndpointShape(String type, Endpoint from, Endpoint to) {
		boolean valid = switch (type) {
			case "ENTRY" -> from.lineId() == null && to.lineId() != null && from.stationId().equals(to.stationId());
			case "EXIT" -> from.lineId() != null && to.lineId() == null && from.stationId().equals(to.stationId());
			case "IN_STATION_TRANSFER" -> from.lineId() != null && to.lineId() != null
				&& from.stationId().equals(to.stationId()) && !from.lineId().equals(to.lineId());
			default -> false;
		};
		if (!valid) throw new IllegalArgumentException("projected topology endpoint is invalid");
	}

	private static Endpoint endpoint(String value) {
		String[] parts = value.split(":", -1);
		if (parts.length < 1 || Arrays.stream(parts).anyMatch(String::isBlank)) {
			throw new IllegalArgumentException("topology endpoint is invalid");
		}
		return new Endpoint(parts[0], parts.length >= 2 ? parts[1] : null);
	}

	private static void requireTimetableColumns(Connection connection) throws SQLException {
		requireColumns(connection, "service_calendars", List.of(
			"service_id", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
			"start_date", "end_date", "timezone"));
		requireColumns(connection, "service_calendar_dates", List.of("service_id", "date", "exception_type"));
		requireColumns(connection, "transit_routes", List.of(
			"id", "line_id", "route_short_name", "route_long_name", "direction_name", "timezone"));
		requireColumns(connection, "transit_trips", List.of(
			"id", "route_id", "service_id", "trip_headsign", "direction_id", "service_pattern",
			"service_class", "service_day_start_seconds"));
		requireColumns(connection, "transit_stop_times", List.of(
			"trip_id", "stop_sequence", "station_id", "line_id", "arrival_seconds", "departure_seconds",
			"pickup_type", "drop_off_type"));
		requireColumns(connection, "transit_frequencies", List.of(
			"trip_id", "start_time_seconds", "end_time_seconds", "headway_seconds", "exact_times"));
		requireColumns(connection, "transit_feed_info", List.of("id", "feed_end_date"));
	}

	private static void requireColumns(Connection connection, String table, List<String> expected) throws SQLException {
		var columns = new ArrayList<String>();
		try (var statement = connection.createStatement();
			var rows = statement.executeQuery("PRAGMA table_info('" + table + "')")) {
			while (rows.next()) columns.add(rows.getString("name"));
		}
		if (!columns.equals(expected)) throw new IllegalArgumentException("SQLite table schema mismatch: " + table);
	}

	private static List<String> queryStrings(Connection connection, String sql, int columns) throws SQLException {
		var values = new ArrayList<String>();
		try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
			while (rows.next()) {
				var row = new StringBuilder();
				for (int column = 1; column <= columns; column++) {
					if (column > 1) row.append('\u0000');
					row.append(rows.getString(column));
				}
				values.add(row.toString());
			}
		}
		return List.copyOf(values);
	}

	private static int querySingleInt(Connection connection, String sql) throws SQLException {
		try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
			if (!rows.next()) throw new IllegalArgumentException("SQLite scalar query is empty");
			int value = rows.getInt(1);
			if (rows.next()) throw new IllegalArgumentException("SQLite scalar query has extra rows");
			return value;
		}
	}

	private static void execute(Connection connection, String sql) throws SQLException {
		try (var statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}

	private static LocalDate serviceDate(String value) {
		try {
			return LocalDate.parse(value, SERVICE_DATE);
		} catch (RuntimeException exception) {
			throw new IllegalArgumentException("timetable service date is invalid", exception);
		}
	}

	private static void decompress(byte[] compressed, Path output) throws IOException {
		if (compressed == null || compressed.length == 0) {
			throw new IllegalArgumentException("route-bundle zstd payload is empty");
		}
		try (var source = new ZstdInputStream(new ByteArrayInputStream(compressed));
			OutputStream target = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
			byte[] buffer = new byte[64 * 1024];
			long written = 0;
			for (int read; (read = source.read(buffer)) >= 0;) {
				if (read == 0) continue;
				written = Math.addExact(written, read);
				if (written > MAX_COMPONENT_BYTES) {
					throw new IllegalArgumentException("route-bundle zstd payload exceeds the decompression limit");
				}
				target.write(buffer, 0, read);
			}
			if (written == 0) throw new IllegalArgumentException("route-bundle zstd payload is empty");
		} catch (IllegalArgumentException exception) {
			throw exception;
		} catch (IOException exception) {
			throw new IllegalArgumentException("route-bundle zstd payload is invalid", exception);
		}
	}

	private static String canonical(JsonNode node) throws IOException {
		return JSON.writeValueAsString(sorted(node));
	}

	private static JsonNode sorted(JsonNode node) {
		if (node.isObject()) {
			ObjectNode result = JSON.createObjectNode();
			var names = new ArrayList<String>();
			node.fieldNames().forEachRemaining(names::add);
			names.sort(Comparator.naturalOrder());
			for (var name : names) result.set(name, sorted(node.get(name)));
			return result;
		}
		if (node.isArray()) {
			ArrayNode result = JSON.createArrayNode();
			node.forEach(value -> result.add(sorted(value)));
			return result;
		}
		return node.deepCopy();
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static String requireSha256(String value, String field) {
		if (value == null || !SHA256.matcher(value).matches()) {
			throw new IllegalArgumentException(field + " must be a lowercase SHA-256");
		}
		return value;
	}

	private static String requireText(String value, String field) {
		if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
		return value;
	}

	private static void deleteDirectory(Path directory) {
		try (var paths = Files.walk(directory)) {
			paths.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException ignored) {
					// A failed cleanup cannot turn a verified runtime into a different runtime.
				}
			});
		} catch (IOException ignored) {
			// Best-effort cleanup of task-owned temporary files.
		}
	}

	public record Input(
		String routeBundleSha256,
		long generation,
		String bundleId,
		long releaseSequence,
		String stationSetSha256,
		Map<String, byte[]> compressedPayloads) {

		public Input {
			routeBundleSha256 = requireSha256(routeBundleSha256, "routeBundleSha256");
			if (generation < 1) throw new IllegalArgumentException("generation must be positive");
			bundleId = requireText(bundleId, "bundleId");
			if (releaseSequence < 1 || releaseSequence > 9_007_199_254_740_991L) {
				throw new IllegalArgumentException("releaseSequence is invalid");
			}
			stationSetSha256 = requireSha256(stationSetSha256, "stationSetSha256");
			Objects.requireNonNull(compressedPayloads, "compressedPayloads");
			var copied = new LinkedHashMap<String, byte[]>();
			compressedPayloads.forEach((path, bytes) -> copied.put(
				requireText(path, "payload path"), bytes == null ? null : bytes.clone()));
			compressedPayloads = Map.copyOf(copied);
		}

		@Override
		public Map<String, byte[]> compressedPayloads() {
			var copied = new LinkedHashMap<String, byte[]>();
			compressedPayloads.forEach((path, bytes) -> copied.put(path, bytes == null ? null : bytes.clone()));
			return Map.copyOf(copied);
		}
	}

	private record Component(String payloadPath, Connection connection, References references) {
		private void closeQuietly() {
			try {
				connection.close();
			} catch (SQLException ignored) {
				// Cleanup only.
			}
		}
	}

	private record References(List<String> stations, List<String> lines, List<String> stationLines) {
	}

	private record Endpoint(String stationId, String lineId) {
	}

	private record Evaluation(String state, String reason) {
	}

	private record TopologyEdge(
		String id,
		String fromNodeId,
		String toNodeId,
		int durationSeconds,
		int distanceMeters,
		String type,
		String servicePattern,
		String serviceClass,
		boolean includesStairs,
		String accessibilityStatus,
		int reliabilityScore,
		String provenanceKind,
		String verificationStatus) {

		private String rawSha256() {
			ObjectNode raw = JSON.createObjectNode();
			raw.put("edgeId", id);
			raw.put("edgeType", type);
			raw.put("fromNodeId", fromNodeId);
			raw.put("toNodeId", toNodeId);
			raw.put("durationSeconds", durationSeconds);
			raw.put("distanceMeters", distanceMeters);
			raw.put("servicePattern", servicePattern);
			raw.put("serviceClass", serviceClass);
			try {
				return sha256(canonical(raw).getBytes(StandardCharsets.UTF_8));
			} catch (IOException exception) {
				throw new IllegalStateException("network edge canonicalization failed", exception);
			}
		}
	}
}

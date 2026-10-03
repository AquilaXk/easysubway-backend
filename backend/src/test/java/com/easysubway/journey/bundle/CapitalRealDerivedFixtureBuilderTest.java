package com.easysubway.journey.bundle;

import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.ServiceCalendar;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.ServiceCalendarDate;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitStopTime;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitTrip;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * #461: 실제 서버 경로 번들에서 수도권 평일 축소 fixture({@code route/real-derived/capital-weekday-v1.json.gz})를 만든다.
 *
 * <p>CI에서는 돌지 않는다. 원본 번들은 data 레포 Datapack candidate 산출물의 {@code server-route-bundle} 디렉터리다.
 * 실행: {@code EASYSUBWAY_CAPITAL_FIXTURE_SOURCE=<server-route-bundle 디렉터리>
 * EASYSUBWAY_CAPITAL_FIXTURE_SOURCE_RUN=<data 레포 Datapack candidate workflow run id> ./gradlew test --tests
 * 'com.easysubway.journey.bundle.CapitalRealDerivedFixtureBuilderTest'}. 다시 만들면 {@code CapitalRealDerivedFixture}의
 * 기대 provenance와 fixture sha256 상수도 함께 바꿔야 로더가 읽는다. 운영과 같은
 * {@link RouteBundleSqliteRuntimeCompiler}로 읽은 시간표에서 수도권 24개 노선 중 기준일에 운행하는 열차와 그 역의
 * 검증된 환승 근거만 남긴다. 원본 번들 manifest의 bundleId·releaseSequence·payload digest를 fixture에 기록한다.</p>
 */
@EnabledIfEnvironmentVariable(named = "EASYSUBWAY_CAPITAL_FIXTURE_SOURCE", matches = ".+")
class CapitalRealDerivedFixtureBuilderTest {

	static final LocalDate SERVICE_DATE = LocalDate.of(2026, 10, 6);
	static final Path OUTPUT = Path.of("src/test/resources/route/real-derived/capital-weekday-v1.json.gz");
	/** 수도권 노선 24개(노선 ID → 이름). 이름은 data 레포 전국 canonical pack의 nameKo다. */
	static final Map<String, String> CAPITAL_LINES = orderedLines();

	@Test
	void buildsTheCapitalWeekdayFixture() throws Exception {
		Path root = Path.of(System.getenv("EASYSUBWAY_CAPITAL_FIXTURE_SOURCE"));
		Map<String, byte[]> payloads = new LinkedHashMap<>();
		Map<String, String> digests = new LinkedHashMap<>();
		for (String name : List.of("payload/accessibility.sqlite.zst", "payload/fare.sqlite.zst",
			"payload/timetable.sqlite.zst", "payload/topology.sqlite.zst")) {
			byte[] bytes = Files.readAllBytes(root.resolve(name));
			payloads.put(name, bytes);
			digests.put(name, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
		}
		ObjectMapper json = new ObjectMapper();
		var manifest = json.readTree(root.resolve("manifest.json").toFile());
		var input = new RouteBundleSqliteRuntimeCompiler.Input("f".repeat(64), 1, manifest.get("bundleId").asText(),
			manifest.get("releaseSequence").asLong(), manifest.get("stationSetSha256").asText(), digests, payloads);
		RouteTimetable source = new RouteBundleSqliteRuntimeCompiler().readTimetable(input);

		Set<String> activeServices = new LinkedHashSet<>();
		for (ServiceCalendar calendar : source.serviceCalendars()) {
			if (active(calendar, source.serviceCalendarDates(), SERVICE_DATE)) activeServices.add(calendar.serviceId());
		}
		Map<String, String> routeLines = new LinkedHashMap<>();
		source.transitRoutes().forEach(route -> routeLines.put(route.id(), route.lineId()));
		List<TransitTrip> trips = source.transitTrips().stream()
			.filter(trip -> activeServices.contains(trip.serviceId()) && CAPITAL_LINES.containsKey(routeLines.get(trip.routeId())))
			.sorted(Comparator.comparing(TransitTrip::id)).toList();
		Set<String> tripIds = new LinkedHashSet<>();
		trips.forEach(trip -> tripIds.add(trip.id()));
		Map<String, List<TransitStopTime>> stopsByTrip = new LinkedHashMap<>();
		source.transitStopTimes().stream().filter(stop -> tripIds.contains(stop.tripId()))
			.sorted(Comparator.comparing(TransitStopTime::tripId).thenComparingInt(TransitStopTime::stopSequence))
			.forEach(stop -> stopsByTrip.computeIfAbsent(stop.tripId(), ignored -> new ArrayList<>()).add(stop));
		Set<String> stations = new java.util.TreeSet<>();
		stopsByTrip.values().forEach(stops -> stops.forEach(stop -> stations.add(stop.stationId())));
		Set<String> usedServices = new java.util.TreeSet<>();
		trips.forEach(trip -> usedServices.add(trip.serviceId()));
		Set<String> usedRoutes = new java.util.TreeSet<>();
		trips.forEach(trip -> usedRoutes.add(trip.routeId()));

		ObjectNode root2 = json.createObjectNode();
		root2.put("schemaVersion", 1);
		ObjectNode provenance = root2.putObject("provenance");
		String sourceRun = System.getenv("EASYSUBWAY_CAPITAL_FIXTURE_SOURCE_RUN");
		if (sourceRun == null || !sourceRun.matches("[0-9]+")) {
			throw new IllegalStateException("EASYSUBWAY_CAPITAL_FIXTURE_SOURCE_RUN must be the data workflow run id");
		}
		ObjectNode sourceArtifact = provenance.putObject("sourceArtifact");
		sourceArtifact.put("repository", "AquilaXk/easysubway-data");
		sourceArtifact.put("workflowRunId", Long.parseLong(sourceRun));
		sourceArtifact.put("artifactName", "easysubway-datapack-candidate-" + sourceRun);
		sourceArtifact.put("path", "server-route-bundle");
		provenance.put("bundleId", manifest.get("bundleId").asText());
		provenance.put("releaseSequence", manifest.get("releaseSequence").asLong());
		provenance.put("activeFrom", manifest.get("activeFrom").asText());
		provenance.put("freshUntil", manifest.get("freshUntil").asText());
		provenance.put("timetableSha256", manifest.get("timetableSha256").asText());
		provenance.put("topologySha256", manifest.get("topologySha256").asText());
		provenance.put("accessibilitySha256", manifest.get("accessibilitySha256").asText());
		provenance.put("serviceDate", SERVICE_DATE.toString());
		provenance.put("selection", "capital lines (24) x services active on serviceDate; verified transfer evidence of kept stations");
		ObjectNode lines = provenance.putObject("lines");
		CAPITAL_LINES.forEach(lines::put);

		ArrayNode calendars = root2.putArray("serviceCalendars");
		for (ServiceCalendar calendar : source.serviceCalendars()) {
			if (!usedServices.contains(calendar.serviceId())) continue;
			calendars.addArray().add(calendar.serviceId()).add(calendar.monday()).add(calendar.tuesday()).add(calendar.wednesday())
				.add(calendar.thursday()).add(calendar.friday()).add(calendar.saturday()).add(calendar.sunday())
				.add(calendar.startDate().toString()).add(calendar.endDate().toString()).add(calendar.timezone());
		}
		ArrayNode exceptions = root2.putArray("serviceCalendarDates");
		for (ServiceCalendarDate date : source.serviceCalendarDates()) {
			if (usedServices.contains(date.serviceId())) exceptions.addArray().add(date.serviceId()).add(date.date().toString()).add(date.exceptionType());
		}
		ArrayNode routes = root2.putArray("transitRoutes");
		source.transitRoutes().stream().filter(route -> usedRoutes.contains(route.id())).forEach(route -> routes.addArray()
			.add(route.id()).add(route.lineId()).add(route.routeShortName()).add(route.routeLongName()).add(route.directionName())
			.add(route.timezone()));
		List<String> stationList = List.copyOf(stations);
		Map<String, Integer> stationIndex = new LinkedHashMap<>();
		for (int index = 0; index < stationList.size(); index += 1) stationIndex.put(stationList.get(index), index);
		ArrayNode stationNode = root2.putArray("stations");
		stationList.forEach(stationNode::add);

		// 같은 정차 순서·노선·승하차 규칙은 패턴 하나로, 첫 출발 기준 상대 시각 배열은 시각 프로필 하나로 묶는다.
		Map<String, Integer> patterns = new LinkedHashMap<>();
		Map<String, Integer> profiles = new LinkedHashMap<>();
		ArrayNode patternNode = root2.putArray("patterns");
		ArrayNode profileNode = root2.putArray("timeProfiles");
		ArrayNode tripNode = root2.putArray("trips");
		for (TransitTrip trip : trips) {
			List<TransitStopTime> stops = stopsByTrip.get(trip.id());
			StringBuilder patternKey = new StringBuilder();
			ArrayNode pattern = json.createArrayNode();
			for (TransitStopTime stop : stops) {
				int code = stationIndex.get(stop.stationId()) * 4 + stop.pickupType() % 2 * 2 + stop.dropOffType() % 2;
				if (stop.pickupType() > 1 || stop.dropOffType() > 1) throw new IllegalStateException("unsupported pickup code");
				patternKey.append(code).append(',').append(stop.lineId()).append(';');
				pattern.add(code).add(stop.lineId());
			}
			int patternIndex = patterns.computeIfAbsent(patternKey.toString(), key -> {
				patternNode.add(pattern);
				return patterns.size();
			});
			int first = stops.getFirst().departureSeconds();
			StringBuilder profileKey = new StringBuilder();
			ArrayNode profile = json.createArrayNode();
			for (TransitStopTime stop : stops) {
				profile.add(stop.arrivalSeconds() - first).add(stop.departureSeconds() - first);
				profileKey.append(stop.arrivalSeconds() - first).append(',').append(stop.departureSeconds() - first).append(';');
			}
			int profileIndex = profiles.computeIfAbsent(profileKey.toString(), key -> {
				profileNode.add(profile);
				return profiles.size();
			});
			tripNode.addArray().add(trip.id()).add(trip.routeId()).add(trip.serviceId()).add(trip.tripHeadsign())
				.add(trip.directionId()).add(trip.serviceClass()).add(trip.servicePattern()).add(trip.trainNo())
				.add(trip.serviceDayStartSeconds()).add(patternIndex).add(profileIndex).add(first)
				.add(stops.getFirst().stopSequence());
		}
		ArrayNode frequencies = root2.putArray("transitFrequencies");
		source.transitFrequencies().stream().filter(item -> tripIds.contains(item.tripId())).forEach(item -> frequencies.addArray()
			.add(item.tripId()).add(item.startTimeSeconds()).add(item.endTimeSeconds()).add(item.headwaySeconds()).add(item.exactTimes()));

		var access = source.routeAccessData();
		ObjectNode accessNode = root2.putObject("access");
		Set<String> keptNodes = new LinkedHashSet<>();
		ArrayNode nodes = accessNode.putArray("pathwayNodes");
		access.pathwayNodes().stream().filter(node -> stations.contains(node.stationId())
			&& (node.lineId() == null || CAPITAL_LINES.containsKey(node.lineId()))).forEach(node -> {
				keptNodes.add(node.id());
				nodes.addArray().add(node.id()).add(node.stationId()).add(node.lineId()).add(node.nodeType());
			});
		Set<String> keptEdges = new LinkedHashSet<>();
		ArrayNode edges = accessNode.putArray("pathwayEdges");
		access.pathwayEdges().stream().filter(edge -> keptNodes.contains(edge.fromNodeId()) && keptNodes.contains(edge.toNodeId()))
			.forEach(edge -> {
				keptEdges.add(edge.id());
				edges.addArray().add(edge.id()).add(edge.fromNodeId()).add(edge.toNodeId()).add(edge.durationSeconds())
					.add(edge.distanceMeters()).add(edge.bidirectional()).add(edge.includesStairs()).add(edge.reliabilityScore())
					.add(edge.accessibilityStatus()).add(edge.provenanceKind()).add(edge.verificationStatus())
					.add(edge.legacyInternalRouteEdgeId());
			});
		ArrayNode rules = accessNode.putArray("transferRules");
		access.transferRules().stream().filter(rule -> stations.contains(rule.fromStationId()) && stations.contains(rule.toStationId())
			&& CAPITAL_LINES.containsKey(rule.fromLineId()) && CAPITAL_LINES.containsKey(rule.toLineId())).forEach(rule -> rules.addArray()
				.add(rule.id()).add(rule.fromStationId()).add(rule.fromLineId()).add(rule.toStationId()).add(rule.toLineId())
				.add(rule.transferType()).add(rule.minTransferSeconds()).add(rule.pathwayEdgeId())
				.add(rule.strictStepFreePathwayEdgeId()).add(rule.verificationStatus()));
		ArrayNode evidence = accessNode.putArray("routeEdgeEvidence");
		access.routeEdgeEvidence().stream().filter(item -> keptEdges.contains(item.edgeId())).forEach(item -> evidence.addArray()
			.add(item.id()).add(item.stationId()).add(item.lineId()).add(item.edgeId()).add(item.edgeType()).add(item.provenanceKind())
			.add(item.verificationStatus()).add(item.strictRouteEligible()).add(item.blockerReason()));
		root2.put("feedEndDate", source.feedEndDate() == null ? null : source.feedEndDate().toString());

		Files.createDirectories(OUTPUT.getParent());
		try (OutputStream output = new GZIPOutputStream(Files.newOutputStream(OUTPUT))) {
			output.write(json.writeValueAsBytes(root2));
		}
		System.out.println("capital fixture: trips=" + trips.size() + " stations=" + stations.size() + " patterns="
			+ patterns.size() + " profiles=" + profiles.size() + " rules=" + rules.size() + " bytes=" + Files.size(OUTPUT));
	}

	private static boolean active(ServiceCalendar calendar, List<ServiceCalendarDate> exceptions, LocalDate date) {
		for (ServiceCalendarDate exception : exceptions) {
			if (exception.serviceId().equals(calendar.serviceId()) && exception.date().equals(date)) {
				return exception.exceptionType() == 1;
			}
		}
		if (date.isBefore(calendar.startDate()) || date.isAfter(calendar.endDate())) return false;
		return switch (date.getDayOfWeek()) {
			case MONDAY -> calendar.monday(); case TUESDAY -> calendar.tuesday(); case WEDNESDAY -> calendar.wednesday();
			case THURSDAY -> calendar.thursday(); case FRIDAY -> calendar.friday(); case SATURDAY -> calendar.saturday();
			case SUNDAY -> calendar.sunday();
		};
	}

	private static Map<String, String> orderedLines() {
		Map<String, String> lines = new LinkedHashMap<>();
		lines.put("line-472a81add377", "수도권 1호선");
		lines.put("seoul-2", "수도권 2호선");
		lines.put("line-41a8c75ec9d8", "수도권 3호선");
		lines.put("seoul-4", "수도권 4호선");
		lines.put("line-80fc4d5350d4", "수도권 5호선");
		lines.put("line-3f41718e0833", "수도권 6호선");
		lines.put("line-15b3b8a93259", "수도권 7호선");
		lines.put("line-2b2d9eaa53d0", "수도권 8호선");
		lines.put("line-f0e747248a31", "수도권 9호선");
		lines.put("line-8604048b6430", "수도권 GTX-A");
		lines.put("line-e4939a4b4713", "수도권 경강");
		lines.put("line-6e39be0cb6e2", "수도권 경의중앙");
		lines.put("line-54a7b980b7c3", "수도권 경춘");
		lines.put("line-e9e9a5b520a4", "수도권 공항");
		lines.put("line-5500c1600f71", "수도권 김포골드라인");
		lines.put("line-051552e50435", "수도권 서해선");
		lines.put("line-558d0bd8312d", "수도권 수인분당");
		lines.put("line-aefa08ccc0a9", "수도권 신림선");
		lines.put("shinbundang", "수도권 신분당");
		lines.put("line-828f04afc588", "수도권 에버라인");
		lines.put("line-30886152e4f8", "수도권 우이신설");
		lines.put("line-62096860ab09", "수도권 의정부");
		lines.put("line-98718184f016", "인천 1호선");
		lines.put("line-42b5805f3b5a", "인천 2호선");
		return java.util.Collections.unmodifiableMap(lines);
	}
}

package com.easysubway.journey.bundle;

import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.ServiceCalendar;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.ServiceCalendarDate;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitRoute;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitStopTime;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitTrip;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * #476 F5: 실제 서버 경로 번들(seq126)에서 역 시간표 실데이터 fixture
 * ({@code journey/real-derived/station-timetable-seq126-v1.json.gz})를 만든다.
 *
 * <p>CI에서는 돌지 않는다. 실행: {@code EASYSUBWAY_STATION_TIMETABLE_FIXTURE_SOURCE=<server-route-bundle 디렉터리>
 * EASYSUBWAY_STATION_TIMETABLE_FIXTURE_SOURCE_RUN=<data 레포 Datapack candidate workflow run id> ./gradlew test --tests
 * 'com.easysubway.journey.bundle.StationTimetableRealDerivedFixtureBuilderTest'}. 다시 만들면
 * {@code StationTimetableRealDerivedFixture}의 출처·sha256 상수도 함께 바꿔야 로더가 읽는다.</p>
 *
 * <p>운영과 같은 {@link RouteBundleSqliteRuntimeCompiler}로 읽은 시간표에서 대상 역·노선을 지나는 평일 열차만 남기고,
 * 열차마다 대상 역 행, 그다음 정차 행, 마지막(종착) 행만 원래 stop_sequence 그대로 발췌한다. 다음 정차역 묶음과 종착역은
 * 이 세 행만으로 정해지므로 판정 근거가 원본과 같다.</p>
 */
@EnabledIfEnvironmentVariable(named = "EASYSUBWAY_STATION_TIMETABLE_FIXTURE_SOURCE", matches = ".+")
class StationTimetableRealDerivedFixtureBuilderTest {

	static final Path OUTPUT = Path.of("src/test/resources/journey/real-derived/station-timetable-seq126-v1.json.gz");
	static final String SERVICE_ID = "kric-capital-weekday";
	/** 강남 2호선(양방향 종착이 같은 순환선)과 1호선 신도림(같은 초·같은 다음 역, 종착이 다른 열차). */
	static final List<List<String>> TARGETS = List.of(
		List.of("station-gangnam", "seoul-2"),
		List.of("station-6a5e08288b46", "line-472a81add377"));

	@Test
	void buildsTheStationTimetableFixture() throws Exception {
		Path root = Path.of(System.getenv("EASYSUBWAY_STATION_TIMETABLE_FIXTURE_SOURCE"));
		String sourceRun = System.getenv("EASYSUBWAY_STATION_TIMETABLE_FIXTURE_SOURCE_RUN");
		if (sourceRun == null || !sourceRun.matches("[0-9]+")) {
			throw new IllegalStateException("EASYSUBWAY_STATION_TIMETABLE_FIXTURE_SOURCE_RUN must be the data workflow run id");
		}
		Map<String, byte[]> payloads = new LinkedHashMap<>();
		Map<String, String> digests = new LinkedHashMap<>();
		for (String name : List.of("payload/accessibility.sqlite.zst", "payload/fare.sqlite.zst",
			"payload/timetable.sqlite.zst", "payload/topology.sqlite.zst")) {
			byte[] bytes = Files.readAllBytes(root.resolve(name));
			payloads.put(name, bytes);
			digests.put(name, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
		}
		ObjectMapper json = new ObjectMapper();
		JsonNode manifest = json.readTree(root.resolve("manifest.json").toFile());
		RouteTimetable source = new RouteBundleSqliteRuntimeCompiler().readTimetable(new RouteBundleSqliteRuntimeCompiler.Input(
			"f".repeat(64), 1, manifest.get("bundleId").asText(), manifest.get("releaseSequence").asLong(),
			manifest.get("stationSetSha256").asText(), digests, payloads));

		Map<String, TransitTrip> tripsById = new LinkedHashMap<>();
		source.transitTrips().forEach(trip -> tripsById.put(trip.id(), trip));
		Map<String, List<TransitStopTime>> stopsByTrip = new LinkedHashMap<>();
		source.transitStopTimes().forEach(stop -> stopsByTrip.computeIfAbsent(stop.tripId(), ignored -> new ArrayList<>()).add(stop));
		Map<String, TransitStopTime> kept = new java.util.TreeMap<>();
		Set<String> usedTrips = new TreeSet<>();
		for (TransitStopTime stop : source.transitStopTimes()) {
			if (!TARGETS.contains(List.of(stop.stationId(), stop.lineId()))) continue;
			TransitTrip trip = tripsById.get(stop.tripId());
			if (trip == null || !SERVICE_ID.equals(trip.serviceId())) continue;
			List<TransitStopTime> stops = stopsByTrip.get(stop.tripId());
			TransitStopTime next = stops.stream().filter(candidate -> candidate.stopSequence() > stop.stopSequence())
				.min(Comparator.comparingInt(TransitStopTime::stopSequence)).orElse(null);
			TransitStopTime terminal = stops.stream().max(Comparator.comparingInt(TransitStopTime::stopSequence)).orElseThrow();
			for (TransitStopTime row : next == null ? List.of(stop, terminal) : List.of(stop, next, terminal)) {
				kept.put(row.tripId() + "\u0000" + String.format("%06d", row.stopSequence()), row);
			}
			usedTrips.add(stop.tripId());
		}
		Set<String> usedRoutes = new TreeSet<>();
		usedTrips.forEach(id -> usedRoutes.add(tripsById.get(id).routeId()));

		ObjectNode output = json.createObjectNode();
		output.put("schemaVersion", 1);
		ObjectNode provenance = output.putObject("provenance");
		ObjectNode sourceArtifact = provenance.putObject("sourceArtifact");
		sourceArtifact.put("repository", "AquilaXk/easysubway-data");
		sourceArtifact.put("workflowRunId", Long.parseLong(sourceRun));
		sourceArtifact.put("artifactName", "easysubway-datapack-candidate-" + sourceRun);
		sourceArtifact.put("path", "server-route-bundle");
		provenance.put("bundleId", manifest.get("bundleId").asText());
		provenance.put("releaseSequence", manifest.get("releaseSequence").asLong());
		provenance.put("freshUntil", manifest.get("freshUntil").asText());
		provenance.put("timetableSha256", manifest.get("timetableSha256").asText());
		provenance.put("timetablePayloadSha256", digests.get("payload/timetable.sqlite.zst"));
		provenance.put("serviceId", SERVICE_ID);
		provenance.put("selection", "weekday trips at the target station-lines; per trip the target row, the next stop row and the terminal row");
		ArrayNode targets = output.putArray("canonicalStationLines");
		TARGETS.forEach(target -> targets.addArray().add(target.get(0)).add(target.get(1)));
		ArrayNode calendars = output.putArray("serviceCalendars");
		for (ServiceCalendar calendar : source.serviceCalendars()) {
			if (!SERVICE_ID.equals(calendar.serviceId())) continue;
			calendars.addArray().add(calendar.serviceId()).add(calendar.monday()).add(calendar.tuesday()).add(calendar.wednesday())
				.add(calendar.thursday()).add(calendar.friday()).add(calendar.saturday()).add(calendar.sunday())
				.add(calendar.startDate().toString()).add(calendar.endDate().toString()).add(calendar.timezone());
		}
		ArrayNode exceptions = output.putArray("serviceCalendarDates");
		for (ServiceCalendarDate date : source.serviceCalendarDates()) {
			if (SERVICE_ID.equals(date.serviceId())) exceptions.addArray().add(date.serviceId()).add(date.date().toString()).add(date.exceptionType());
		}
		ArrayNode routes = output.putArray("transitRoutes");
		for (TransitRoute route : source.transitRoutes()) {
			if (!usedRoutes.contains(route.id())) continue;
			routes.addArray().add(route.id()).add(route.lineId()).add(route.routeShortName()).add(route.routeLongName())
				.add(route.directionName()).add(route.timezone());
		}
		ArrayNode trips = output.putArray("transitTrips");
		for (String id : usedTrips) {
			TransitTrip trip = tripsById.get(id);
			trips.addArray().add(trip.id()).add(trip.routeId()).add(trip.serviceId()).add(trip.tripHeadsign()).add(trip.directionId())
				.add(trip.serviceClass()).add(trip.servicePattern()).add(trip.trainNo()).add(trip.serviceDayStartSeconds());
		}
		ArrayNode stopTimes = output.putArray("transitStopTimes");
		for (TransitStopTime stop : kept.values()) {
			stopTimes.addArray().add(stop.tripId()).add(stop.stopSequence()).add(stop.stationId()).add(stop.lineId())
				.add(stop.arrivalSeconds()).add(stop.departureSeconds()).add(stop.pickupType()).add(stop.dropOffType());
		}
		output.put("feedEndDate", source.feedEndDate() == null ? null : source.feedEndDate().toString());

		Files.createDirectories(OUTPUT.getParent());
		try (OutputStream file = Files.newOutputStream(OUTPUT); var gzip = new GZIPOutputStream(file)) {
			gzip.write(json.writeValueAsBytes(output));
		}
		System.out.printf("fixture trips=%d stopTimes=%d sha256=%s%n", usedTrips.size(), kept.size(),
			HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(OUTPUT))));
	}
}

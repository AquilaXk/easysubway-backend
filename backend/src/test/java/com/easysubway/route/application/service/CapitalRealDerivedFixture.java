package com.easysubway.route.application.service;

import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.GZIPInputStream;

/**
 * #461: 실제 서버 경로 번들에서 자른 수도권 평일 축소 fixture.
 *
 * <p>원본은 data 레포 Datapack candidate 산출물의 전국 서버 경로 번들이다(출처 digest는 fixture의 provenance).
 * 수도권 24개 노선 중 {@link #SERVICE_DATE}에 운행하는 열차 9,691편, 656역, 검증된 환승 297건이다.
 * 다시 만드는 방법은 {@code CapitalRealDerivedFixtureBuilderTest}에 있다.</p>
 *
 * <p>로더는 fixture 파일의 sha256과 provenance(원본 번들 ID·release·payload digest·기준일·출처 data workflow run)를 아래
 * 상수와 대조하고 다르면 읽지 않는다. fixture를 다시 만들면 이 상수를 함께 바꿔야 한다.</p>
 */
final class CapitalRealDerivedFixture {

	static final String RESOURCE = "route/real-derived/capital-weekday-v1.json.gz";
	static final LocalDate SERVICE_DATE = LocalDate.of(2026, 10, 6);

	static final String GANGNAM = "station-gangnam";
	static final String SEOUL = "station-2af75c3d707b";
	static final String JAMSIL = "station-79a1cc0b6193";
	static final String HONGIK_UNIV = "station-0f50a307ac33";
	static final String SADANG = "station-sadang";
	static final String SINDORIM = "station-6a5e08288b46";
	static final String SUWON = "station-86fdc698524b";
	static final String GIMPO_AIRPORT = "station-1f38f0831cb1";
	static final String WANGSIMNI = "station-e5cf592cf355";
	static final String EXPRESS_BUS_TERMINAL = "station-18a5f1c674d8";
	static final String JONGNO_3GA = "station-1c24eb757f3c";
	static final String PANGYO = "station-5ba75cc204b9";
	static final String BUPYEONG = "station-3eb92ae69e48";
	static final String NOWON = "station-239ba3bb73a6";
	static final String YEOUIDO = "station-b2cbb36a5868";
	static final String KONKUK_UNIV = "station-e773d203ef9f";

	/** fixture 파일(gzip) 바이트의 sha256. */
	static final String FIXTURE_SHA256 = "b90ce040036aa0a36018ed9bb849024a6a6d0f0094f3b432dcfb6a3b5eec7517";
	/** 원본: data 레포 Datapack candidate workflow run의 server-route-bundle 산출물. */
	static final long SOURCE_WORKFLOW_RUN_ID = 37_088_712_672L;
	static final String SOURCE_BUNDLE_ID = "nationwide-route-bundle-1";
	static final long SOURCE_RELEASE_SEQUENCE = 125L;
	static final String SOURCE_TIMETABLE_SHA256 = "c4d9accbf455235d5f12dc3d67307bf515da2602bee07f33f16fcea8c5b5330c";
	static final String SOURCE_TOPOLOGY_SHA256 = "25e77986199fafd1f6522e37880c2a184ea9ab5e3ee29bd8c14748b9b3d4b188";
	static final String SOURCE_ACCESSIBILITY_SHA256 = "e1d0c531e038d2d21c03414b66c03f1c83f72bc08d84aa87c9a22301e383dbf9";

	private static RouteTimetable cached;

	private CapitalRealDerivedFixture() {
	}

	static synchronized RouteTimetable load() {
		if (cached == null) cached = read();
		return cached;
	}

	static List<String> stations() {
		List<String> stations = new ArrayList<>();
		load().transitStopTimes().forEach(stop -> {
			if (!stations.contains(stop.stationId())) stations.add(stop.stationId());
		});
		stations.sort(String::compareTo);
		return List.copyOf(stations);
	}

	private static RouteTimetable read() {
		try (InputStream raw = Objects.requireNonNull(
			CapitalRealDerivedFixture.class.getClassLoader().getResourceAsStream(RESOURCE), RESOURCE)) {
			byte[] bytes = raw.readAllBytes();
			String digest = java.util.HexFormat.of().formatHex(
				java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
			if (!FIXTURE_SHA256.equals(digest)) {
				throw new IllegalStateException("capital fixture digest changed: " + digest);
			}
			try (InputStream stream = new GZIPInputStream(new java.io.ByteArrayInputStream(bytes))) {
				JsonNode root = new ObjectMapper().readTree(stream);
				requireProvenance(root.path("provenance"));
				return decode(root);
			}
		} catch (IOException exception) {
			throw new UncheckedIOException(exception);
		} catch (java.security.NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static void requireProvenance(JsonNode provenance) {
		JsonNode source = provenance.path("sourceArtifact");
		boolean matches = SOURCE_BUNDLE_ID.equals(provenance.path("bundleId").asText())
			&& provenance.path("releaseSequence").asLong() == SOURCE_RELEASE_SEQUENCE
			&& SOURCE_TIMETABLE_SHA256.equals(provenance.path("timetableSha256").asText())
			&& SOURCE_TOPOLOGY_SHA256.equals(provenance.path("topologySha256").asText())
			&& SOURCE_ACCESSIBILITY_SHA256.equals(provenance.path("accessibilitySha256").asText())
			&& SERVICE_DATE.toString().equals(provenance.path("serviceDate").asText())
			&& "AquilaXk/easysubway-data".equals(source.path("repository").asText())
			&& source.path("workflowRunId").asLong() == SOURCE_WORKFLOW_RUN_ID;
		if (!matches) throw new IllegalStateException("capital fixture provenance does not match the pinned source");
	}

	private static RouteTimetable decode(JsonNode root) {
		if (root.path("schemaVersion").asInt() != 1) throw new IllegalStateException("unsupported fixture schema");
		List<LoadRouteTimetablePort.ServiceCalendar> calendars = new ArrayList<>();
		for (JsonNode row : root.path("serviceCalendars")) {
			calendars.add(new LoadRouteTimetablePort.ServiceCalendar(row.get(0).asText(), row.get(1).asBoolean(),
				row.get(2).asBoolean(), row.get(3).asBoolean(), row.get(4).asBoolean(), row.get(5).asBoolean(),
				row.get(6).asBoolean(), row.get(7).asBoolean(), LocalDate.parse(row.get(8).asText()),
				LocalDate.parse(row.get(9).asText()), row.get(10).asText()));
		}
		List<LoadRouteTimetablePort.ServiceCalendarDate> exceptions = new ArrayList<>();
		for (JsonNode row : root.path("serviceCalendarDates")) {
			exceptions.add(new LoadRouteTimetablePort.ServiceCalendarDate(row.get(0).asText(),
				LocalDate.parse(row.get(1).asText()), row.get(2).asInt()));
		}
		List<LoadRouteTimetablePort.TransitRoute> routes = new ArrayList<>();
		for (JsonNode row : root.path("transitRoutes")) {
			routes.add(new LoadRouteTimetablePort.TransitRoute(row.get(0).asText(), row.get(1).asText(), text(row.get(2)),
				text(row.get(3)), text(row.get(4)), row.get(5).asText()));
		}
		List<String> stations = new ArrayList<>();
		root.path("stations").forEach(node -> stations.add(node.asText()));
		List<LoadRouteTimetablePort.TransitTrip> trips = new ArrayList<>();
		List<LoadRouteTimetablePort.TransitStopTime> stopTimes = new ArrayList<>();
		JsonNode patterns = root.path("patterns");
		JsonNode profiles = root.path("timeProfiles");
		for (JsonNode row : root.path("trips")) {
			String tripId = row.get(0).asText();
			trips.add(new LoadRouteTimetablePort.TransitTrip(tripId, row.get(1).asText(), row.get(2).asText(), text(row.get(3)),
				text(row.get(4)), row.get(5).asText(), row.get(6).asText(), text(row.get(7)), row.get(8).asInt()));
			JsonNode pattern = patterns.get(row.get(9).asInt());
			JsonNode profile = profiles.get(row.get(10).asInt());
			int first = row.get(11).asInt();
			int sequence = row.get(12).asInt();
			for (int position = 0; position < pattern.size() / 2; position += 1) {
				int code = pattern.get(position * 2).asInt();
				stopTimes.add(new LoadRouteTimetablePort.TransitStopTime(tripId, sequence + position, stations.get(code / 4),
					pattern.get(position * 2 + 1).asText(), first + profile.get(position * 2).asInt(),
					first + profile.get(position * 2 + 1).asInt(), code / 2 % 2, code % 2));
			}
		}
		List<LoadRouteTimetablePort.TransitFrequency> frequencies = new ArrayList<>();
		for (JsonNode row : root.path("transitFrequencies")) {
			frequencies.add(new LoadRouteTimetablePort.TransitFrequency(row.get(0).asText(), row.get(1).asInt(),
				row.get(2).asInt(), row.get(3).asInt(), row.get(4).asBoolean()));
		}
		JsonNode access = root.path("access");
		List<LoadRouteTimetablePort.PathwayNode> nodes = new ArrayList<>();
		for (JsonNode row : access.path("pathwayNodes")) {
			nodes.add(new LoadRouteTimetablePort.PathwayNode(row.get(0).asText(), row.get(1).asText(), text(row.get(2)),
				row.get(3).asText()));
		}
		List<LoadRouteTimetablePort.PathwayEdge> edges = new ArrayList<>();
		for (JsonNode row : access.path("pathwayEdges")) {
			edges.add(new LoadRouteTimetablePort.PathwayEdge(row.get(0).asText(), row.get(1).asText(), row.get(2).asText(),
				row.get(3).asInt(), row.get(4).asInt(), row.get(5).asBoolean(), row.get(6).asBoolean(), row.get(7).asInt(),
				// #469 F1: fixture는 계단 상태 열이 없다. 원본 번들(release 125)의 network_edges.stair_access_state는 모든
				// 환승 동선이 UNKNOWN이었다(seq126도 309개 전부 UNKNOWN).
				row.get(8).asText(), row.get(9).asText(), row.get(10).asText(), text(row.get(11)), "UNKNOWN"));
		}
		List<LoadRouteTimetablePort.TransferRule> rules = new ArrayList<>();
		for (JsonNode row : access.path("transferRules")) {
			rules.add(new LoadRouteTimetablePort.TransferRule(row.get(0).asText(), row.get(1).asText(), row.get(2).asText(),
				row.get(3).asText(), row.get(4).asText(), row.get(5).asText(), row.get(6).asInt(), text(row.get(7)),
				text(row.get(8)), row.get(9).asText()));
		}
		List<LoadRouteTimetablePort.RouteEdgeEvidence> evidence = new ArrayList<>();
		for (JsonNode row : access.path("routeEdgeEvidence")) {
			evidence.add(new LoadRouteTimetablePort.RouteEdgeEvidence(row.get(0).asText(), row.get(1).asText(),
				text(row.get(2)), row.get(3).asText(), row.get(4).asText(), row.get(5).asText(), row.get(6).asText(),
				row.get(7).asBoolean(), text(row.get(8))));
		}
		String feedEndDate = text(root.get("feedEndDate"));
		return new RouteTimetable(calendars, exceptions, routes, trips, stopTimes, frequencies, List.of(),
			feedEndDate == null ? null : LocalDate.parse(feedEndDate),
			new LoadRouteTimetablePort.RouteAccessData(nodes, edges, rules, evidence));
	}

	private static String text(JsonNode node) {
		return node == null || node.isNull() ? null : node.asText();
	}
}

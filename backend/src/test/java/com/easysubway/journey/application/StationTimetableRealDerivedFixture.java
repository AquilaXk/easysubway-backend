package com.easysubway.journey.application;

import com.easysubway.journey.application.StationTimetableSnapshotPort.StationLine;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteAccessData;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.ServiceCalendar;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.ServiceCalendarDate;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitRoute;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitStopTime;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitTrip;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * #476 F5: 실제 서버 경로 번들(seq126)에서 발췌한 역 시간표 fixture.
 *
 * <p>원본은 data 레포 Datapack candidate 산출물의 전국 서버 경로 번들이다. 강남 2호선과 1호선 신도림을 지나는 평일 열차에서
 * 열차마다 대상 역 행, 다음 정차 행, 종착 행을 원래 값 그대로 남겼다. 다시 만드는 방법은
 * {@code StationTimetableRealDerivedFixtureBuilderTest}에 있다. 로더는 fixture 바이트 sha256과 출처(번들 ID·release·시간표
 * payload digest·data workflow run)를 아래 상수와 대조하고 다르면 읽지 않는다.</p>
 */
final class StationTimetableRealDerivedFixture {

	static final String RESOURCE = "journey/real-derived/station-timetable-seq126-v1.json.gz";
	static final String FIXTURE_SHA256 = "9e95f01c5bc6a0546a143cca7a620331df92f21079fb8f62f3359c525a408765";
	static final String SOURCE_BUNDLE_ID = "nationwide-route-bundle-1";
	static final long SOURCE_RELEASE_SEQUENCE = 126L;
	static final String SOURCE_TIMETABLE_SHA256 = "6c9c04e505a2fa90afbd146ac9ab8440fddf1dae848fc9f48f386c383d92fed3";
	static final long SOURCE_WORKFLOW_RUN_ID = 37_109_648_483L;

	static final String GANGNAM = "station-gangnam";
	static final String LINE_2 = "seoul-2";
	static final String YEOKSAM = "station-6cb6f7dc212c";
	static final String GYODAE = "station-7dfc6ea6a83c";
	static final String SEONGSU = "station-seongsu";
	static final String SINDORIM = "station-6a5e08288b46";
	static final String LINE_1 = "line-472a81add377";
	static final String GURO = "station-28102e7fc597";
	static final String GWANGMYEONG = "station-b9590e0769c3";
	static final String INCHEON = "station-be476fd82950";

	private StationTimetableRealDerivedFixture() {
	}

	record Fixture(RouteTimetable timetable, Set<StationLine> canonicalStationLines) {
	}

	static Fixture load() {
		try (InputStream raw = Objects.requireNonNull(
			StationTimetableRealDerivedFixture.class.getClassLoader().getResourceAsStream(RESOURCE), RESOURCE)) {
			byte[] bytes = raw.readAllBytes();
			String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
			if (!FIXTURE_SHA256.equals(digest)) throw new IllegalStateException("station timetable fixture digest changed: " + digest);
			try (InputStream stream = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
				JsonNode root = new ObjectMapper().readTree(stream);
				requireProvenance(root.path("provenance"));
				return decode(root);
			}
		} catch (IOException exception) {
			throw new UncheckedIOException(exception);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static void requireProvenance(JsonNode provenance) {
		JsonNode source = provenance.path("sourceArtifact");
		boolean matches = SOURCE_BUNDLE_ID.equals(provenance.path("bundleId").asText())
			&& provenance.path("releaseSequence").asLong() == SOURCE_RELEASE_SEQUENCE
			&& SOURCE_TIMETABLE_SHA256.equals(provenance.path("timetableSha256").asText())
			&& SOURCE_TIMETABLE_SHA256.equals(provenance.path("timetablePayloadSha256").asText())
			&& "AquilaXk/easysubway-data".equals(source.path("repository").asText())
			&& source.path("workflowRunId").asLong() == SOURCE_WORKFLOW_RUN_ID;
		if (!matches) throw new IllegalStateException("station timetable fixture provenance does not match the pinned source");
	}

	private static Fixture decode(JsonNode root) {
		if (root.path("schemaVersion").asInt() != 1) throw new IllegalStateException("unsupported fixture schema");
		Set<StationLine> stationLines = new LinkedHashSet<>();
		root.path("canonicalStationLines").forEach(row -> stationLines.add(new StationLine(row.get(0).asText(), row.get(1).asText())));
		List<ServiceCalendar> calendars = new ArrayList<>();
		for (JsonNode row : root.path("serviceCalendars")) {
			calendars.add(new ServiceCalendar(row.get(0).asText(), row.get(1).asBoolean(), row.get(2).asBoolean(),
				row.get(3).asBoolean(), row.get(4).asBoolean(), row.get(5).asBoolean(), row.get(6).asBoolean(),
				row.get(7).asBoolean(), LocalDate.parse(row.get(8).asText()), LocalDate.parse(row.get(9).asText()),
				row.get(10).asText()));
		}
		List<ServiceCalendarDate> exceptions = new ArrayList<>();
		for (JsonNode row : root.path("serviceCalendarDates")) {
			exceptions.add(new ServiceCalendarDate(row.get(0).asText(), LocalDate.parse(row.get(1).asText()), row.get(2).asInt()));
		}
		List<TransitRoute> routes = new ArrayList<>();
		for (JsonNode row : root.path("transitRoutes")) {
			routes.add(new TransitRoute(row.get(0).asText(), row.get(1).asText(), text(row.get(2)), text(row.get(3)),
				text(row.get(4)), row.get(5).asText()));
		}
		List<TransitTrip> trips = new ArrayList<>();
		for (JsonNode row : root.path("transitTrips")) {
			trips.add(new TransitTrip(row.get(0).asText(), row.get(1).asText(), row.get(2).asText(), text(row.get(3)),
				text(row.get(4)), row.get(5).asText(), row.get(6).asText(), text(row.get(7)), row.get(8).asInt()));
		}
		List<TransitStopTime> stopTimes = new ArrayList<>();
		for (JsonNode row : root.path("transitStopTimes")) {
			stopTimes.add(new TransitStopTime(row.get(0).asText(), row.get(1).asInt(), row.get(2).asText(), row.get(3).asText(),
				row.get(4).asInt(), row.get(5).asInt(), row.get(6).asInt(), row.get(7).asInt()));
		}
		String feedEndDate = text(root.get("feedEndDate"));
		return new Fixture(new RouteTimetable(calendars, exceptions, routes, trips, stopTimes, List.of(), List.of(),
			feedEndDate == null ? null : LocalDate.parse(feedEndDate), RouteAccessData.empty()), stationLines);
	}

	private static String text(JsonNode node) {
		return node == null || node.isNull() ? null : node.asText();
	}
}

package com.easysubway.transit.adapter.out.bundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.easysubway.journey.application.ActiveJourneySnapshotPort;
import com.easysubway.journey.application.FacilityAvailabilityView;
import com.easysubway.journey.application.FacilityStatusUnavailableException;
import com.easysubway.journey.application.JourneyAlternatives;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.journey.application.JourneyRequestMeasurement;
import com.easysubway.journey.bundle.ActiveRouteBundleSnapshot;
import com.easysubway.journey.bundle.LoadScenarioBundles;
import com.easysubway.journey.bundle.RouteBundleActivationRegistry;
import com.easysubway.journey.bundle.RouteBundleFacilityCatalog;
import com.easysubway.journey.bundle.RouteBundleRuntimeView;
import com.easysubway.journey.bundle.RouteBundleSqliteRuntimeCompiler;
import com.easysubway.route.application.service.JourneyRaptorAdapter;
import com.easysubway.route.application.service.RaptorRouteBundleRuntimeView;
import com.easysubway.route.application.service.RouteTimetableRaptorPlanner.ScanWorkspacePool;
import com.easysubway.transit.application.port.out.FacilityOperationalStatusStore;
import com.easysubway.transit.domain.FacilityOperationalState;
import com.easysubway.transit.domain.FacilityOperationalStatus;
import com.easysubway.transit.domain.FacilityStatusSource;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ActiveRouteBundleFacilityAvailabilityAdapterTest {

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final String SHA = "a".repeat(64);
	private static final String STATION_SET_SHA = "b".repeat(64);
	private static final String BUNDLE_ID = "capital-20260812";
	private static final String REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
	private static final Instant BUNDLE_NOW = Instant.parse("2026-08-12T00:50:00Z");
	private static final Instant DEPARTURE = Instant.parse("2026-08-12T00:55:00Z"); // 09:55 KST (35700s, >=180s slack before 10:00)
	private static final String FAST_ELEVATOR_ID = "smrt-elev:0201:1:1번";
	private static final String ALT_ELEVATOR_ID = "smrt-elev:0201:2:2번";
	private static final String ENTRY_ELEVATOR_ID = "smrt-elev:0101:1:entry";
	private static final String EXIT_ELEVATOR_ID = "smrt-elev:0301:1:exit";

	@TempDir
	Path temp;

	@Test
	void returnsUnavailableWhenActiveBundleIsAbsentOrExpiredOrLacksFacilityCatalog() {
		var statusStore = mock(FacilityOperationalStatusStore.class);
		when(statusStore.lastSuccessfulCollectionAt(FacilityOperationalStatusStore.SEOUL_METRO_ELEVATOR_FEED))
			.thenReturn(Optional.of(BUNDLE_NOW));
		when(statusStore.loadStatuses()).thenReturn(List.of());

		var emptyRegistry = new RouteBundleActivationRegistry(Clock.fixed(BUNDLE_NOW, ZoneOffset.UTC));
		assertThat(new ActiveRouteBundleFacilityAvailabilityAdapter(emptyRegistry, statusStore).currentView())
			.isEqualTo(FacilityAvailabilityView.unavailable());

		var clock = new MutableClock(BUNDLE_NOW);
		var expiredRegistry = activatedRegistry(clock, new PlainRuntimeView());
		// Plain runtimeView does not implement RouteBundleFacilityCatalog -> fail-closed
		assertThat(new ActiveRouteBundleFacilityAvailabilityAdapter(expiredRegistry, statusStore).currentView())
			.isEqualTo(FacilityAvailabilityView.unavailable());

		var validCatalogRegistry = activatedRegistry(clock, mock(com.easysubway.route.application.service.RaptorRouteBundleRuntimeView.class));
		clock.set(BUNDLE_NOW.plus(Duration.ofDays(31)));
		assertThat(new ActiveRouteBundleFacilityAvailabilityAdapter(validCatalogRegistry, statusStore).currentView())
			.isEqualTo(FacilityAvailabilityView.unavailable());
	}

	@Test
	void returnsUnavailableFailClosedWhenFeedHeartbeatIsMissingOrStoreThrows() throws Exception {
		RaptorRouteBundleRuntimeView runtime = compileRealBundle();
		var registry = activatedRegistry(new MutableClock(BUNDLE_NOW), runtime);

		var missingHeartbeat = mock(FacilityOperationalStatusStore.class);
		when(missingHeartbeat.lastSuccessfulCollectionAt(FacilityOperationalStatusStore.SEOUL_METRO_ELEVATOR_FEED))
			.thenReturn(Optional.empty());
		assertThat(new ActiveRouteBundleFacilityAvailabilityAdapter(registry, missingHeartbeat).currentView())
			.isEqualTo(FacilityAvailabilityView.unavailable());

		var throwingHeartbeat = mock(FacilityOperationalStatusStore.class);
		when(throwingHeartbeat.lastSuccessfulCollectionAt(FacilityOperationalStatusStore.SEOUL_METRO_ELEVATOR_FEED))
			.thenThrow(new IllegalStateException("db down"));
		assertThat(new ActiveRouteBundleFacilityAvailabilityAdapter(registry, throwingHeartbeat).currentView())
			.isEqualTo(FacilityAvailabilityView.unavailable());

		var throwingStatuses = mock(FacilityOperationalStatusStore.class);
		when(throwingStatuses.lastSuccessfulCollectionAt(FacilityOperationalStatusStore.SEOUL_METRO_ELEVATOR_FEED))
			.thenReturn(Optional.of(BUNDLE_NOW));
		when(throwingStatuses.loadStatuses()).thenThrow(new IllegalStateException("query timeout"));
		assertThat(new ActiveRouteBundleFacilityAvailabilityAdapter(registry, throwingStatuses).currentView())
			.isEqualTo(FacilityAvailabilityView.unavailable());
	}

	@Test
	void returnsEmptyWhenNoCompiledTransitionEdgeIsOutOfService() throws Exception {
		RaptorRouteBundleRuntimeView runtime = compileRealBundle();
		var registry = activatedRegistry(new MutableClock(BUNDLE_NOW), runtime);
		var statusStore = mock(FacilityOperationalStatusStore.class);
		Instant heartbeat = BUNDLE_NOW.minusSeconds(10);
		when(statusStore.lastSuccessfulCollectionAt(FacilityOperationalStatusStore.SEOUL_METRO_ELEVATOR_FEED))
			.thenReturn(Optional.of(heartbeat));
		// ENTRY/EXIT 간선 시설이 OUT_OF_SERVICE여도 전환 간선이 없으므로 empty로 처리된다.
		when(statusStore.loadStatuses()).thenReturn(List.of(
			new FacilityOperationalStatus(FAST_ELEVATOR_ID, FacilityOperationalState.OPERATING,
				FacilityStatusSource.SEOUL_METRO_FEED, "M", heartbeat, heartbeat),
			new FacilityOperationalStatus(ENTRY_ELEVATOR_ID, FacilityOperationalState.OUT_OF_SERVICE,
				FacilityStatusSource.SEOUL_METRO_FEED, "S", heartbeat, heartbeat),
			new FacilityOperationalStatus(EXIT_ELEVATOR_ID, FacilityOperationalState.OUT_OF_SERVICE,
				FacilityStatusSource.ADMIN_VERIFIED, null, heartbeat, heartbeat)));

		var view = new ActiveRouteBundleFacilityAvailabilityAdapter(registry, statusStore).currentView();

		assertThat(view).isEqualTo(FacilityAvailabilityView.empty(heartbeat));
	}

	@Test
	void blocksCompiledTransitionEdgesForSeoulMetroAndAdminVerifiedFacilitiesEndToEnd() throws Exception {
		RaptorRouteBundleRuntimeView runtime = compileRealBundle();
		var clock = new MutableClock(DEPARTURE);
		var registry = activatedRegistry(clock, runtime);
		var statusStore = mock(FacilityOperationalStatusStore.class);
		Instant heartbeat = DEPARTURE.minusSeconds(15);
		when(statusStore.lastSuccessfulCollectionAt(FacilityOperationalStatusStore.SEOUL_METRO_ELEVATOR_FEED))
			.thenReturn(Optional.of(heartbeat));

		var adapter = new ActiveRouteBundleFacilityAvailabilityAdapter(registry, statusStore);
		var raptor = new JourneyRaptorAdapter(ScanWorkspacePool.shared(), adapter, true, clock);
		var snapshot = snapshot(runtime);
		var stepFreeRequest = new JourneyRequest(
			REQUEST_ID, "station-a", "station-c",
			new JourneyRequest.Departure.Scheduled(DEPARTURE),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STEP_FREE,
			JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE,
			2, 1, () -> false);

		// 1) 정상 가동 중이면 빠른 엘리베이터 환승(60s + 180s slack -> 10:14)으로 10:15 열차 -> 10:25 도착
		when(statusStore.loadStatuses()).thenReturn(List.of(
			new FacilityOperationalStatus(FAST_ELEVATOR_ID, FacilityOperationalState.OPERATING,
				FacilityStatusSource.SEOUL_METRO_FEED, "M", heartbeat, heartbeat),
			new FacilityOperationalStatus(ALT_ELEVATOR_ID, FacilityOperationalState.OPERATING,
				FacilityStatusSource.SEOUL_METRO_FEED, "M", heartbeat, heartbeat)));
		var normalResult = raptor.plan(stepFreeRequest, snapshot, DEPARTURE, null, new JourneyRequestMeasurement(REQUEST_ID));
		assertThat(normalResult.stairFreeAlternative().facilityStatus()).isEqualTo(JourneyAlternatives.FacilityStatus.APPLIED);
		assertThat(normalResult.candidates()).singleElement()
			.satisfies(candidate -> assertThat(candidate.plannedArrivalTime())
				.isEqualTo(Instant.parse("2026-08-12T01:25:00Z")));

		// 2) 서울교통공사 피드 OUT_OF_SERVICE -> 빠른 엘리베이터 차단, 대체 엘리베이터(240s + 180s slack -> 10:17)로 10:20 열차 -> 10:30 도착
		when(statusStore.loadStatuses()).thenReturn(List.of(
			new FacilityOperationalStatus(FAST_ELEVATOR_ID, FacilityOperationalState.OUT_OF_SERVICE,
				FacilityStatusSource.SEOUL_METRO_FEED, "S", heartbeat, heartbeat),
			new FacilityOperationalStatus(ALT_ELEVATOR_ID, FacilityOperationalState.OPERATING,
				FacilityStatusSource.SEOUL_METRO_FEED, "M", heartbeat, heartbeat)));
		var detourResult = raptor.plan(stepFreeRequest, snapshot, DEPARTURE, null, new JourneyRequestMeasurement(REQUEST_ID));
		assertThat(detourResult.stairFreeAlternative().facilityStatus()).isEqualTo(JourneyAlternatives.FacilityStatus.APPLIED);
		assertThat(detourResult.candidates()).singleElement()
			.satisfies(candidate -> assertThat(candidate.plannedArrivalTime())
				.isEqualTo(Instant.parse("2026-08-12T01:30:00Z")));

		// 3) 관리자 확인(ADMIN_VERIFIED) OUT_OF_SERVICE까지 겹치면 무단차 경로 없음(REQUIRE_STEP_FREE 빈 후보, STANDARD는 NOT_FOUND + APPLIED)
		when(statusStore.loadStatuses()).thenReturn(List.of(
			new FacilityOperationalStatus(FAST_ELEVATOR_ID, FacilityOperationalState.OUT_OF_SERVICE,
				FacilityStatusSource.SEOUL_METRO_FEED, "S", heartbeat, heartbeat),
			new FacilityOperationalStatus(ALT_ELEVATOR_ID, FacilityOperationalState.OUT_OF_SERVICE,
				FacilityStatusSource.ADMIN_VERIFIED, null, heartbeat, heartbeat)));
		var noStepFreeResult = raptor.plan(stepFreeRequest, snapshot, DEPARTURE, null, new JourneyRequestMeasurement(REQUEST_ID));
		assertThat(noStepFreeResult.candidates()).isEmpty();

		var standardRequest = new JourneyRequest(
			REQUEST_ID, "station-a", "station-c",
			new JourneyRequest.Departure.Scheduled(DEPARTURE),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD,
			JourneyRequest.ConstraintMode.NONE,
			2, 1, () -> false);
		var standardResult = raptor.plan(standardRequest, snapshot, DEPARTURE, null, new JourneyRequestMeasurement(REQUEST_ID));
		assertThat(standardResult.candidates()).isNotEmpty();
		assertThat(standardResult.stairFreeAlternative())
			.isEqualTo(new JourneyAlternatives.StairFreeAlternative(
				JourneyAlternatives.StairFreeStatus.NOT_FOUND,
				JourneyAlternatives.FacilityStatus.APPLIED));
	}

	@Test
	void throwsFacilityStatusUnavailableWhenFeedHeartbeatExceedsFiveMinutesUnderRequiredMode() throws Exception {
		RaptorRouteBundleRuntimeView runtime = compileRealBundle();
		var clock = new MutableClock(DEPARTURE);
		var registry = activatedRegistry(clock, runtime);
		var statusStore = mock(FacilityOperationalStatusStore.class);
		Instant staleHeartbeat = DEPARTURE.minusSeconds(301);
		when(statusStore.lastSuccessfulCollectionAt(FacilityOperationalStatusStore.SEOUL_METRO_ELEVATOR_FEED))
			.thenReturn(Optional.of(staleHeartbeat));
		when(statusStore.loadStatuses()).thenReturn(List.of(
			new FacilityOperationalStatus(FAST_ELEVATOR_ID, FacilityOperationalState.OUT_OF_SERVICE,
				FacilityStatusSource.ADMIN_VERIFIED, null, DEPARTURE.minusSeconds(10), DEPARTURE.minusSeconds(10))));

		var adapter = new ActiveRouteBundleFacilityAvailabilityAdapter(registry, statusStore);
		var view = adapter.currentView();
		assertThat(view.observedAt()).isEqualTo(staleHeartbeat);
		assertThat(view.blockedPathwayEdgeIds()).containsExactly("transfer-fast-elev");

		var raptor = new JourneyRaptorAdapter(ScanWorkspacePool.shared(), adapter, true, clock);
		var stepFreeRequest = new JourneyRequest(
			REQUEST_ID, "station-a", "station-c",
			new JourneyRequest.Departure.Scheduled(DEPARTURE),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STEP_FREE,
			JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE,
			2, 1, () -> false);

		assertThatThrownBy(() -> raptor.plan(
			stepFreeRequest, snapshot(runtime), DEPARTURE, null, new JourneyRequestMeasurement(REQUEST_ID)))
			.isInstanceOf(FacilityStatusUnavailableException.class)
			.hasMessageContaining("FACILITY_STATUS_UNAVAILABLE");
	}

	@Test
	void coversUnexpectedRegistryFailureNullStatusListAndCatalogDefaultContracts() throws Exception {
		var throwingRegistry = mock(RouteBundleActivationRegistry.class);
		when(throwingRegistry.activeSnapshot()).thenThrow(new IllegalStateException("registry broken"));
		var okStore = mock(FacilityOperationalStatusStore.class);
		assertThat(new ActiveRouteBundleFacilityAvailabilityAdapter(throwingRegistry, okStore).currentView())
			.isEqualTo(FacilityAvailabilityView.unavailable());

		var runtime = compileRealBundle();
		var registry = activatedRegistry(new MutableClock(BUNDLE_NOW), runtime);
		var nullStatusesStore = mock(FacilityOperationalStatusStore.class);
		when(nullStatusesStore.lastSuccessfulCollectionAt(FacilityOperationalStatusStore.SEOUL_METRO_ELEVATOR_FEED))
			.thenReturn(Optional.of(BUNDLE_NOW));
		when(nullStatusesStore.loadStatuses()).thenReturn(null);
		assertThat(new ActiveRouteBundleFacilityAvailabilityAdapter(registry, nullStatusesStore).currentView())
			.isEqualTo(FacilityAvailabilityView.unavailable());

		RouteBundleFacilityCatalog minimalCatalog = List::of;
		assertThat(minimalCatalog.transitionEdgeIdsForFacility(FAST_ELEVATOR_ID)).isEmpty();
		assertThat(minimalCatalog.canonicalStationIds()).isEmpty();
		assertThat(minimalCatalog.bundleFacilities()).isEmpty();
		assertThat(minimalCatalog.bundleStationExits()).isEmpty();

		var defaultedFacility = new RouteBundleFacilityCatalog.BundleFacility(
			"fac-1", "st-1", " ", "ELEVATOR", "EL1", null, null, null, null, " ", null, 0L, 1);
		assertThat(defaultedFacility.exitId()).isNull();
		assertThat(defaultedFacility.floorFrom()).isEmpty();
		assertThat(defaultedFacility.floorTo()).isEmpty();
		assertThat(defaultedFacility.description()).isEmpty();
		assertThat(defaultedFacility.status()).isEqualTo("UNKNOWN");
		assertThat(defaultedFacility.operationalStatus()).isEqualTo("UNKNOWN");
		assertThat(defaultedFacility.provenanceKind()).isEqualTo("UNKNOWN");

		var blankFieldFacility = new RouteBundleFacilityCatalog.BundleFacility(
			"fac-2", "st-2", "", "RAMP", "R1", "B1", "1F", "desc", " ", "", "", 10L, 3);
		assertThat(blankFieldFacility.exitId()).isNull();
		assertThat(blankFieldFacility.status()).isEqualTo("UNKNOWN");
		assertThat(blankFieldFacility.operationalStatus()).isEqualTo("UNKNOWN");
		assertThat(blankFieldFacility.provenanceKind()).isEqualTo("UNKNOWN");

		var populatedFacility = new RouteBundleFacilityCatalog.BundleFacility(
			"fac-3", "st-3", "exit-1", "ELEVATOR", "EL3", "B2", "B1", "d", "AVAILABLE", "OPERATING", "OFFICIAL_SOURCE", 20L, 3);
		assertThat(populatedFacility.exitId()).isEqualTo("exit-1");

		var defaultedExit = new RouteBundleFacilityCatalog.BundleStationExit(
			"exit-1", "st-1", "1번", null, null, null, false, null);
		assertThat(defaultedExit.description()).isEmpty();
		assertThat(defaultedExit.dataSourceType()).isEqualTo("OFFICIAL_FILE");

		var blankSourceExit = new RouteBundleFacilityCatalog.BundleStationExit(
			"exit-2", "st-1", "2번", "서쪽", 37.5, 127.0, true, " ");
		assertThat(blankSourceExit.dataSourceType()).isEqualTo("OFFICIAL_FILE");
	}

	private static RouteBundleActivationRegistry activatedRegistry(Clock clock, RouteBundleRuntimeView runtime) {
		var registry = new RouteBundleActivationRegistry(clock);
		registry.stage(LoadScenarioBundles.candidate("a", runtime, clock.instant()), 0);
		registry.activate(LoadScenarioBundles.manifestSha256("a"), 0);
		return registry;
	}

	private static ActiveJourneySnapshotPort.ActiveJourneySnapshot snapshot(RaptorRouteBundleRuntimeView runtime) {
		return new ActiveJourneySnapshotPort.ActiveJourneySnapshot(
			"snapshot-1", BUNDLE_ID, LoadScenarioBundles.manifestSha256("a"),
			"timetable-1", "accessibility-1", 1L, runtime, BUNDLE_NOW.plus(Duration.ofDays(30)), true,
			ActiveJourneySnapshotPort.ActiveServingEvidence.unobservable(),
			ActiveJourneySnapshotPort.SnapshotBoundaryReceipt.unobservable());
	}

	private RaptorRouteBundleRuntimeView compileRealBundle() throws Exception {
		List<BundleEdge> edges = List.of(
			new BundleEdge("entry-a", "station-a", "station-a:line-1:platform-a", 60, 60, "ENTRY", "", "SUBWAY",
				0, "STEP_FREE", ENTRY_ELEVATOR_ID),
			new BundleEdge("ride-a-b", "station-a:line-1:platform-a", "station-b:line-1:platform-b", 600, 1000,
				"RIDE", "LOCAL", "SUBWAY", 0, "STEP_FREE", null),
			new BundleEdge("transfer-fast-elev", "station-b:line-1:platform-b", "station-b:line-2:platform-b",
				60, 60, "IN_STATION_TRANSFER", "", "SUBWAY", 0, "STEP_FREE", FAST_ELEVATOR_ID),
			new BundleEdge("transfer-alt-elev", "station-b:line-1:platform-b", "station-b:line-2:platform-b",
				240, 300, "IN_STATION_TRANSFER", "", "SUBWAY", 0, "STEP_FREE", ALT_ELEVATOR_ID),
			new BundleEdge("transfer-stairs", "station-b:line-1:platform-b", "station-b:line-2:platform-b",
				180, 225, "IN_STATION_TRANSFER", "", "SUBWAY", 1, "STAIR_ONLY", null),
			new BundleEdge("ride-b-c", "station-b:line-2:platform-b", "station-c:line-2:platform-c", 600, 1000,
				"RIDE", "LOCAL", "SUBWAY", 0, "STEP_FREE", null),
			new BundleEdge("exit-c", "station-c:line-2:platform-c", "station-c", 60, 60, "EXIT", "", "SUBWAY",
				0, "STEP_FREE", EXIT_ELEVATOR_ID));

		byte[] topology = sqlite("topology", connection -> {
			common(connection);
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
			for (var edge : edges) {
				insert(connection, "INSERT INTO network_edges VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
					edge.id(), edge.from(), edge.to(), edge.duration(), edge.distance(), edge.type(), edge.pattern(),
					edge.serviceClass(), edge.includesStairs(), edge.stairAccessState(), "AVAILABLE", 100,
					"official", "snapshot", "d".repeat(64), "OFFICIAL_SOURCE", "VERIFIED",
					edge.facilityId(), 1_786_485_600_000L, "e".repeat(64));
			}
		});

		byte[] timetable = sqlite("timetable", connection -> {
			common(connection);
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
				"daily", 1, 1, 1, 1, 1, 1, 1, "20260801", "20261231", "Asia/Seoul");
			insert(connection, "INSERT INTO transit_routes VALUES(?,?,?,?,?,?)",
				"route-1", "line-1", "1", "Line 1", "station-b", "Asia/Seoul");
			insert(connection, "INSERT INTO transit_routes VALUES(?,?,?,?,?,?)",
				"route-2", "line-2", "2", "Line 2", "station-c", "Asia/Seoul");
			insert(connection, "INSERT INTO transit_trips VALUES(?,?,?,?,?,?,?,?)",
				"trip-1", "route-1", "daily", "station-b", "0", "LOCAL", "SUBWAY", 0);
			insert(connection, "INSERT INTO transit_trips VALUES(?,?,?,?,?,?,?,?)",
				"trip-2-fast", "route-2", "daily", "station-c", "0", "LOCAL", "SUBWAY", 0);
			insert(connection, "INSERT INTO transit_trips VALUES(?,?,?,?,?,?,?,?)",
				"trip-2-slow", "route-2", "daily", "station-c", "0", "LOCAL", "SUBWAY", 0);
			// trip-1: 10:00 station-a -> 10:10 station-b
			insert(connection, "INSERT INTO transit_stop_times VALUES(?,?,?,?,?,?,?,?)",
				"trip-1", 1, "station-a", "line-1", 36_000, 36_000, 0, 0);
			insert(connection, "INSERT INTO transit_stop_times VALUES(?,?,?,?,?,?,?,?)",
				"trip-1", 2, "station-b", "line-1", 36_600, 36_600, 0, 0);
			// trip-2-fast: 10:15 station-b -> 10:25 station-c (catchable via 60s transfer-fast-elev + 180s slack)
			insert(connection, "INSERT INTO transit_stop_times VALUES(?,?,?,?,?,?,?,?)",
				"trip-2-fast", 1, "station-b", "line-2", 36_900, 36_900, 0, 0);
			insert(connection, "INSERT INTO transit_stop_times VALUES(?,?,?,?,?,?,?,?)",
				"trip-2-fast", 2, "station-c", "line-2", 37_500, 37_500, 0, 0);
			// trip-2-slow: 10:20 station-b -> 10:30 station-c (catchable via 240s transfer-alt-elev + 180s slack or stairs)
			insert(connection, "INSERT INTO transit_stop_times VALUES(?,?,?,?,?,?,?,?)",
				"trip-2-slow", 1, "station-b", "line-2", 37_200, 37_200, 0, 0);
			insert(connection, "INSERT INTO transit_stop_times VALUES(?,?,?,?,?,?,?,?)",
				"trip-2-slow", 2, "station-c", "line-2", 37_800, 37_800, 0, 0);
			insert(connection, "INSERT INTO transit_feed_info VALUES(?,?)", 1, "20261231");
		});

		byte[] accessibility = sqlite("accessibility", connection -> {
			common(connection);
			execute(connection,
				"CREATE TABLE facilities (id TEXT NOT NULL PRIMARY KEY, station_id TEXT NOT NULL, type TEXT NOT NULL, name TEXT NOT NULL)");
			insert(connection, "INSERT INTO facilities VALUES(?,?,?,?)", FAST_ELEVATOR_ID, "station-b", "ELEVATOR", "빠른 엘리베이터");
			insert(connection, "INSERT INTO facilities VALUES(?,?,?,?)", ALT_ELEVATOR_ID, "station-b", "ELEVATOR", "대체 엘리베이터");
			insert(connection, "INSERT INTO facilities VALUES(?,?,?,?)", ENTRY_ELEVATOR_ID, "station-a", "ELEVATOR", "진입 엘리베이터");
			insert(connection, "INSERT INTO facilities VALUES(?,?,?,?)", EXIT_ELEVATOR_ID, "station-c", "ELEVATOR", "출구 엘리베이터");
			execute(connection,
				"CREATE TABLE route_accessibility_edge_evidence (evaluation_digest TEXT NOT NULL PRIMARY KEY, materialization_digest TEXT NOT NULL, canonical_json TEXT NOT NULL)");
			var evaluation = evaluation(edges, Map.of());
			insert(connection, "INSERT INTO route_accessibility_edge_evidence VALUES(?,?,?)",
				evaluation.path("evaluationDigest").textValue(), "c".repeat(64), canonical(evaluation));
		});

		byte[] fare = sqlite("fare", connection -> {
			common(connection);
			execute(connection, """
				CREATE TABLE official_od_fare_quotes (origin_station_id TEXT NOT NULL,
				 destination_station_id TEXT NOT NULL, source_id TEXT NOT NULL, snapshot_id TEXT NOT NULL,
				 mapping_ledger_hash TEXT NOT NULL, gnrl_card_fare INTEGER NOT NULL,
				 gnrl_cash_fare INTEGER NOT NULL, yung_card_fare INTEGER NOT NULL,
				 yung_cash_fare INTEGER NOT NULL, child_card_fare INTEGER NOT NULL,
				 child_cash_fare INTEGER NOT NULL, PRIMARY KEY(origin_station_id,destination_station_id))
				""");
			insert(connection, "INSERT INTO official_od_fare_quotes VALUES(?,?,?,?,?,?,?,?,?,?,?)",
				"station-a", "station-c", "official", "snapshot", "f".repeat(64), 1400, 1500, 800, 900, 500, 600);
		});

		var payloads = new LinkedHashMap<String, byte[]>();
		payloads.put("payload/topology.sqlite.zst", Zstd.compress(topology, 10));
		payloads.put("payload/timetable.sqlite.zst", Zstd.compress(timetable, 10));
		payloads.put("payload/accessibility.sqlite.zst", Zstd.compress(accessibility, 10));
		payloads.put("payload/fare.sqlite.zst", Zstd.compress(fare, 10));

		var sha256s = new LinkedHashMap<String, String>();
		for (var entry : payloads.entrySet()) {
			sha256s.put(entry.getKey(), sha256(entry.getValue()));
		}

		return new RouteBundleSqliteRuntimeCompiler().compile(new RouteBundleSqliteRuntimeCompiler.Input(
			LoadScenarioBundles.manifestSha256("a"), 1L, BUNDLE_ID, 11L, STATION_SET_SHA, sha256s, payloads));
	}

	private byte[] sqlite(String name, SqliteWriter writer) throws Exception {
		Path file = Files.createTempFile(temp, name + "-", ".sqlite");
		try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file)) {
			writer.write(connection);
		}
		return Files.readAllBytes(file);
	}

	private static void common(Connection connection) throws Exception {
		execute(connection, "PRAGMA user_version=19; "
			+ "CREATE TABLE artifact_component_identity (bundleId TEXT NOT NULL, releaseSequence INTEGER NOT NULL, stationSetSha256 TEXT NOT NULL, serviceTimezone TEXT NOT NULL);"
			+ "INSERT INTO artifact_component_identity VALUES('" + BUNDLE_ID + "',11,'" + STATION_SET_SHA + "','Asia/Seoul')"
			+ "; CREATE TABLE stations (id TEXT PRIMARY KEY)"
			+ "; CREATE TABLE lines (id TEXT PRIMARY KEY)"
			+ "; CREATE TABLE station_lines (station_id TEXT NOT NULL, line_id TEXT NOT NULL, line_sequence INTEGER NOT NULL, PRIMARY KEY(station_id,line_id))");
		insert(connection, "INSERT INTO stations VALUES(?)", "station-a");
		insert(connection, "INSERT INTO stations VALUES(?)", "station-b");
		insert(connection, "INSERT INTO stations VALUES(?)", "station-c");
		insert(connection, "INSERT INTO lines VALUES(?)", "line-1");
		insert(connection, "INSERT INTO lines VALUES(?)", "line-2");
		insert(connection, "INSERT INTO station_lines VALUES(?,?,?)", "station-a", "line-1", 1);
		insert(connection, "INSERT INTO station_lines VALUES(?,?,?)", "station-b", "line-1", 2);
		insert(connection, "INSERT INTO station_lines VALUES(?,?,?)", "station-b", "line-2", 3);
		insert(connection, "INSERT INTO station_lines VALUES(?,?,?)", "station-c", "line-2", 4);
	}

	private static ObjectNode evaluation(List<BundleEdge> edges, Map<String, String> states) throws Exception {
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
			int sequence = switch (parts[0]) {
				case "station-a" -> 1;
				case "station-b" -> "line-1".equals(parts[1]) ? 2 : 3;
				default -> 4;
			};
			endpoint.put("lineId", parts[1]).put("operatorId", "operator-1").put("lineSequence", sequence);
		}
		return endpoint;
	}

	private static String edgeSha(BundleEdge edge) throws Exception {
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

	private record BundleEdge(
		String id, String from, String to, int duration, int distance, String type, String pattern,
		String serviceClass, int includesStairs, String stairAccessState, String facilityId) {
	}

	private record PlainRuntimeView() implements RouteBundleRuntimeView {
	}

	private static final class MutableClock extends Clock {
		private Instant instant;

		private MutableClock(Instant instant) {
			this.instant = instant;
		}

		void set(Instant instant) {
			this.instant = instant;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return instant;
		}
	}
}

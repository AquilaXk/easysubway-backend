package com.easysubway.journey.bundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.journey.application.ActiveJourneySnapshotPort.ActiveJourneySnapshot;
import com.easysubway.journey.application.FacilityStatusOverlayProvider;
import com.easysubway.journey.application.FacilityStatusUnavailableException;
import com.easysubway.journey.application.JourneyRaptorPort;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.journey.application.JourneyRequestMeasurement;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.GapGrade;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.HeightDiffGrade;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.PlatformGap;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.PlatformGapKey;
import com.easysubway.route.application.service.JourneyRaptorAdapter;
import com.easysubway.route.application.service.RaptorRouteBundleRuntimeView;
import com.easysubway.transit.adapter.out.persistence.JdbcFacilityOperationalStatusRepository;
import com.easysubway.transit.application.port.out.FacilityOperationalStatusStore;
import com.easysubway.transit.application.port.out.FacilityOperationalStatusStore.FeedObservation;
import com.easysubway.transit.domain.FacilityOperationalState;
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
import java.util.Set;
import java.util.UUID;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class RouteBundleSqliteRuntimeCompilerTest {

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final String SHA = "a".repeat(64);
	private static final String STATION_SET_SHA = "b".repeat(64);
	private static final String BUNDLE_ID = "capital-20260812";
	private static final Instant DEPARTURE = Instant.parse("2026-08-12T00:50:00Z");
	// data emit-artifact-components.mjs의 transition_facility_requirement DDL과 같다.
	private static final String REQUIREMENT_DDL = "CREATE TABLE transition_facility_requirement (transition_key TEXT NOT NULL,"
		+ " path_id TEXT NOT NULL, direction_next_station_id TEXT NOT NULL, group_kind TEXT NOT NULL"
		+ " CHECK(group_kind IN ('EXIT_ELEVATORS','PLATFORM_DIRECTION_ELEVATORS')), facility_id TEXT NOT NULL,"
		+ " PRIMARY KEY (transition_key, path_id, group_kind, facility_id))";
	private static final String EXIT_GROUP = TransitionFacilityRequirements.EXIT_ELEVATORS;
	private static final String DIRECTION_GROUP = TransitionFacilityRequirements.PLATFORM_DIRECTION_ELEVATORS;
	private static final String E1_EXIT = "smrt-elev:0201:1:9번 출입구";
	private static final String E2_EXIT = "smrt-elev:0201:1:10번 출입구";
	private static final String D1 = "smrt-elev:0201:1:가역 방면1-1";
	private static final String D2 = "smrt-elev:0201:1:가역 방면3-4";

	@TempDir
	Path temp;

	@Test
	void readsVerifiedTimetableAndDirectionalAccessBeforePlannerCompilation() throws Exception {
		var timetable = new RouteBundleSqliteRuntimeCompiler().readTimetable(input(payloads()));

		assertThat(timetable.transitStopTimes()).extracting(stop -> stop.stationId())
			.containsExactly("station-a", "station-b");
		assertThat(timetable.routeAccessData().pathwayEdges()).extracting(edge -> edge.id())
			.containsExactly("entry-a", "exit-b");
		assertThat(timetable.routeAccessData().pathwayEdges()).extracting(edge -> edge.durationSeconds())
			.containsExactly(120, 60);
		assertThat(timetable.routeAccessData().pathwayEdges()).extracting(edge -> edge.distanceMeters())
			.containsExactly(60, 40);
	}

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
			JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD,
			JourneyRequest.ConstraintMode.NONE,
			0,
			1,
			() -> false);
		var snapshot = new ActiveJourneySnapshot(
			"active:7", BUNDLE_ID, SHA, "timetable", "accessibility", 7, runtime,
			DEPARTURE.plusSeconds(3600), true,
			com.easysubway.journey.application.ActiveJourneySnapshotPort.ActiveServingEvidence.unobservable(),
			com.easysubway.journey.application.ActiveJourneySnapshotPort.SnapshotBoundaryReceipt.observed(0, 0));

		var planned = new JourneyRaptorAdapter().plan(
			request, snapshot, DEPARTURE, null, new JourneyRequestMeasurement(request.requestId()));

		assertThat(planned.queryId()).isEqualTo(request.requestId());
		assertThat(planned.candidates()).singleElement().satisfies(candidate -> {
			assertThat(candidate.legs()).hasSize(3);
			assertThat(candidate.transferCount()).isZero();
			assertThat(candidate.accessibility().stairFree()).isTrue();
		});
	}

	@Test
	void readsStationCarDoorHintsFromAccessibilityBundleWhenPresent() throws Exception {
		var payloads = payloads();
		var accessibility = sqlite("accessibility-with-hints", connection -> {
			common(connection, identitySql());
			facilities(connection);
			execute(connection, "CREATE TABLE route_accessibility_edge_evidence (evaluation_digest TEXT NOT NULL PRIMARY KEY, materialization_digest TEXT NOT NULL, canonical_json TEXT NOT NULL)");
			var evaluation = evaluation(topologyEdges(), Map.of());
			insert(connection, "INSERT INTO route_accessibility_edge_evidence VALUES(?,?,?)",
				evaluation.path("evaluationDigest").textValue(), "c".repeat(64), canonical(evaluation));
			execute(connection, """
				CREATE TABLE station_car_door_hints (
					id TEXT NOT NULL PRIMARY KEY,
					station_id TEXT NOT NULL,
					line_id TEXT NOT NULL,
					direction TEXT NOT NULL,
					target_facility_type TEXT NOT NULL,
					car_number INTEGER NOT NULL,
					door_number INTEGER NOT NULL,
					source_id TEXT NOT NULL DEFAULT '',
					source_snapshot_id TEXT NOT NULL DEFAULT '',
					provider_record_hash TEXT NOT NULL DEFAULT '',
					provenance_kind TEXT NOT NULL DEFAULT 'UNKNOWN',
					verification_status TEXT NOT NULL DEFAULT 'UNKNOWN',
					last_verified_at INTEGER NOT NULL DEFAULT 0,
					evidence_hash TEXT NOT NULL DEFAULT ''
				)
				""");
			insert(connection, "INSERT INTO station_car_door_hints (id, station_id, line_id, direction, target_facility_type, car_number, door_number) VALUES ('h1','station-b','line-1','UP','TRANSFER',3,2)");
		});
		payloads.put(RouteBundleSqliteRuntimeCompiler.ACCESSIBILITY_PATH, Zstd.compress(accessibility, 10));

		var timetable = new RouteBundleSqliteRuntimeCompiler().readTimetable(input(payloads));
		assertThat(timetable.routeAccessData().carDoorHints()).hasSize(1);
		var hint = timetable.routeAccessData().carDoorHints().getFirst();
		assertThat(hint.stationId()).isEqualTo("station-b");
		assertThat(hint.lineId()).isEqualTo("line-1");
		assertThat(hint.direction()).isEqualTo("UP");
		assertThat(hint.targetFacilityType()).isEqualTo("TRANSFER");
		assertThat(hint.carNumber()).isEqualTo(3);
		assertThat(hint.doorNumber()).isEqualTo(2);
	}

	@Test
	void exposesOnlySmrtElevatorFacilitiesFromTheAccessibilityComponentAsAReadOnlyCatalog() throws Exception {
		RouteBundleRuntimeView runtime = new RouteBundleSqliteRuntimeCompiler().compile(input(payloads()));

		assertThat(runtime).isInstanceOf(RouteBundleFacilityCatalog.class);
		assertThat(((RouteBundleFacilityCatalog) runtime).smrtElevatorFacilities()).containsExactly(
			new RouteBundleFacilityCatalog.Facility("smrt-elev:0150:1:나역 방면1-1", "가역 엘리베이터 나역 방면1-1"),
			new RouteBundleFacilityCatalog.Facility("smrt-elev:0201:2:9번 출입구", "나역 엘리베이터 9번 출입구"));
		assertThatThrownBy(() -> ((RouteBundleFacilityCatalog) runtime).smrtElevatorFacilities().clear())
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void rejectsAccessibilityComponentWithoutFacilitiesOrWithBlankFacilityName() throws Exception {
		var missing = payloads();
		missing.put(RouteBundleSqliteRuntimeCompiler.ACCESSIBILITY_PATH, Zstd.compress(sqlite("accessibility-no-facilities", connection -> {
			common(connection, identitySql());
			execute(connection, "CREATE TABLE route_accessibility_edge_evidence (evaluation_digest TEXT NOT NULL PRIMARY KEY, materialization_digest TEXT NOT NULL, canonical_json TEXT NOT NULL)");
			var evaluation = evaluation(topologyEdges());
			insert(connection, "INSERT INTO route_accessibility_edge_evidence VALUES(?,?,?)",
				evaluation.path("evaluationDigest").textValue(), "c".repeat(64), canonical(evaluation));
		}), 10));

		assertThatThrownBy(() -> new RouteBundleSqliteRuntimeCompiler().compile(input(missing)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("runtime compilation failed")
			.cause().hasMessageContaining("facilities");

		var blankName = payloads();
		blankName.put(RouteBundleSqliteRuntimeCompiler.ACCESSIBILITY_PATH, Zstd.compress(sqlite("accessibility-blank-name", connection -> {
			common(connection, identitySql());
			execute(connection, "CREATE TABLE facilities (id TEXT NOT NULL PRIMARY KEY, station_id TEXT NOT NULL, type TEXT NOT NULL, name TEXT NOT NULL)");
			insert(connection, "INSERT INTO facilities VALUES(?,?,?,?)", "smrt-elev:0201:2:9번 출입구", "station-b", "ELEVATOR", " ");
			execute(connection, "CREATE TABLE route_accessibility_edge_evidence (evaluation_digest TEXT NOT NULL PRIMARY KEY, materialization_digest TEXT NOT NULL, canonical_json TEXT NOT NULL)");
			var evaluation = evaluation(topologyEdges());
			insert(connection, "INSERT INTO route_accessibility_edge_evidence VALUES(?,?,?)",
				evaluation.path("evaluationDigest").textValue(), "c".repeat(64), canonical(evaluation));
		}), 10));

		assertThatThrownBy(() -> new RouteBundleSqliteRuntimeCompiler().compile(input(blankName)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("bundle facility name");
	}

	@Test
	void loadsTransitionFacilityRequirementsIntoTheRuntimeView() throws Exception {
		var runtime = new RouteBundleSqliteRuntimeCompiler().compile(input(payloadsWithRequirements(
			REQUIREMENT_DDL, exitBRequirementRows())));

		assertThat(runtime).isInstanceOf(TransitionFacilityRequirementSource.class);
		var requirements = runtime.transitionFacilityRequirements();
		assertThat(requirements.present()).isTrue();
		assertThat(requirements.transitionKeys()).containsExactly("exit-b");
		assertThat(requirements.requirementsFor("exit-b"))
			.extracting(row -> String.join("|", row.transitionKey(), row.pathId(), row.directionNextStationId(),
				row.groupKind(), row.facilityId()))
			.containsExactly(
				"exit-b|path-1|station-a|EXIT_ELEVATORS|" + E1_EXIT,
				"exit-b|path-1|station-a|PLATFORM_DIRECTION_ELEVATORS|" + D1,
				"exit-b|path-1|station-a|PLATFORM_DIRECTION_ELEVATORS|" + D2,
				"exit-b|path-2|station-a|EXIT_ELEVATORS|" + E2_EXIT,
				"exit-b|path-2|station-a|PLATFORM_DIRECTION_ELEVATORS|" + D1,
				"exit-b|path-2|station-a|PLATFORM_DIRECTION_ELEVATORS|" + D2);
		assertThat(requirements.requirementsFor("entry-a")).isEmpty();
	}

	@Test
	void bundleWithoutRequirementTableCarriesAnExplicitMissingMapping() throws Exception {
		var runtime = new RouteBundleSqliteRuntimeCompiler().compile(input(payloads()));

		assertThat(runtime.transitionFacilityRequirements().present()).isFalse();
		assertThat(runtime.transitionFacilityRequirements().transitionKeys()).isEmpty();
	}

	@Test
	void rejectsRequirementTablesThatBreakTheDataContract() throws Exception {
		var compiler = new RouteBundleSqliteRuntimeCompiler();
		var wrongColumns = payloadsWithRequirements(
			"CREATE TABLE transition_facility_requirement (transition_key TEXT NOT NULL, facility_id TEXT NOT NULL)",
			List.of());
		assertThatThrownBy(() -> compiler.compile(input(wrongColumns)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("SQLite table schema mismatch: transition_facility_requirement");

		assertThatThrownBy(() -> compiler.compile(input(payloadsWithRequirements(REQUIREMENT_DDL, List.of()))))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("transition_facility_requirement is empty");

		String uncheckedDdl = "CREATE TABLE transition_facility_requirement (transition_key TEXT, path_id TEXT,"
			+ " direction_next_station_id TEXT, group_kind TEXT, facility_id TEXT)";
		assertThatThrownBy(() -> compiler.compile(input(payloadsWithRequirements(uncheckedDdl, List.<Object[]>of(
			new Object[] {"exit-b", "path-1", "station-a", "ESCALATORS", E1_EXIT})))))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("group_kind");
		assertThatThrownBy(() -> compiler.compile(input(payloadsWithRequirements(uncheckedDdl, List.<Object[]>of(
			new Object[] {"exit-b", "path-1", "station-a", EXIT_GROUP, " "})))))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("facility_id");
		for (String transitionKey : List.of("edge-not-in-topology", "ride-a-b")) {
			assertThatThrownBy(() -> compiler.compile(input(payloadsWithRequirements(REQUIREMENT_DDL, List.<Object[]>of(
				new Object[] {transitionKey, "path-1", "station-a", EXIT_GROUP, E1_EXIT})))))
				.as(transitionKey)
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("not a station ENTRY/EXIT edge");
		}
	}

	/**
	 * 통합: 매핑을 담은 테스트 번들 + 운영 상태 테이블(H2, V76)에 대한 실제 수집 반영 → 공급자 갱신 → Journey 무단차 탐색.
	 * exit-b의 요구는 경로 2개(출입구 E1·E2 각각 + 방향 D1·D2 공통)다.
	 */
	@Test
	void stepFreeJourneyFollowsOperationalStatusThroughTheCompiledBundleMapping() throws Exception {
		var runtime = new RouteBundleSqliteRuntimeCompiler().compile(input(payloadsWithRequirements(
			REQUIREMENT_DDL, exitBRequirementRows())));
		var repository = facilityStatusRepository();
		var clock = new MutableClock(DEPARTURE);
		var provider = new FacilityStatusOverlayProvider(
			repository, runtime::transitionFacilityRequirements, clock, new SimpleMeterRegistry());
		var adapter = new JourneyRaptorAdapter(provider, true, clock);
		var stepFree = request(JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE);
		var snapshot = snapshot(runtime);

		provider.refresh();
		assertThatThrownBy(() -> plan(adapter, stepFree, snapshot))
			.isInstanceOf(FacilityStatusUnavailableException.class)
			.hasMessageContaining("FACILITY_STATUS_UNAVAILABLE");

		collect(repository, provider, clock, Set.of());
		var original = plan(adapter, stepFree, snapshot).candidates();
		assertThat(original).singleElement().satisfies(candidate ->
			assertThat(candidate.accessibility().stairFree()).isTrue());

		// 방향 엘리베이터 2대 중 1대 불가 → 통과.
		collect(repository, provider, clock, Set.of(D1));
		assertThat(plan(adapter, stepFree, snapshot).candidates()).isEqualTo(original);

		// 출입구 E1 불가 → path-1만 빠지고 path-2로 통과.
		collect(repository, provider, clock, Set.of(E1_EXIT));
		assertThat(plan(adapter, stepFree, snapshot).candidates()).isEqualTo(original);

		// 두 출입구 모두 불가 → exit-b가 막혀 무단차 경로가 없다(빈 결과로 명시).
		collect(repository, provider, clock, Set.of(E1_EXIT, E2_EXIT));
		assertThat(provider.currentView().blockedPathwayEdgeIds()).containsExactly("exit-b");
		assertThat(plan(adapter, stepFree, snapshot).candidates()).isEmpty();

		// 고장 해제 → 원래 경로.
		collect(repository, provider, clock, Set.of(E2_EXIT));
		assertThat(plan(adapter, stepFree, snapshot).candidates()).isEqualTo(original);

		// 원천 수집이 멈추면 캐시 갱신이 성공해도 5분을 넘긴 뒤 무단차 요청은 명시적 오류다.
		clock.advance(Duration.ofMinutes(5).plusSeconds(1));
		provider.refresh();
		assertThatThrownBy(() -> plan(adapter, stepFree, snapshot))
			.isInstanceOf(FacilityStatusUnavailableException.class);
	}

	@Test
	void bundleWithoutMappingFailsRequiredStepFreeJourneyButKeepsOrdinaryJourney() throws Exception {
		var runtime = new RouteBundleSqliteRuntimeCompiler().compile(input(payloads()));
		var repository = facilityStatusRepository();
		var clock = new MutableClock(DEPARTURE);
		var provider = new FacilityStatusOverlayProvider(
			repository, runtime::transitionFacilityRequirements, clock, new SimpleMeterRegistry());
		var adapter = new JourneyRaptorAdapter(provider, true, clock);
		collect(repository, provider, clock, Set.of(E1_EXIT));

		assertThatThrownBy(() -> plan(adapter, request(JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE), snapshot(runtime)))
			.isInstanceOf(FacilityStatusUnavailableException.class)
			.hasMessageContaining("FACILITY_STATUS_UNAVAILABLE");
		assertThat(plan(adapter, request(JourneyRequest.ConstraintMode.NONE), snapshot(runtime)).candidates())
			.hasSize(1);
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
			facilities(connection);
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
	void projectsBlockedAccessibilityEdgesWithoutFailingCompilation() throws Exception {
		var payloads = payloads();
		var accessibility = sqlite("accessibility-blocked", connection -> {
			common(connection, identitySql());
			facilities(connection);
			execute(connection, "CREATE TABLE route_accessibility_edge_evidence (evaluation_digest TEXT NOT NULL PRIMARY KEY, materialization_digest TEXT NOT NULL, canonical_json TEXT NOT NULL)");
			var evaluation = evaluation(topologyEdges(), Map.of("entry-a", "BLOCKED"));
			insert(connection, "INSERT INTO route_accessibility_edge_evidence VALUES(?,?,?)",
				evaluation.path("evaluationDigest").textValue(), "c".repeat(64), canonical(evaluation));
		});
		payloads.put(RouteBundleSqliteRuntimeCompiler.ACCESSIBILITY_PATH, Zstd.compress(accessibility, 10));

		var timetable = new RouteBundleSqliteRuntimeCompiler().readTimetable(input(payloads));
		var entryEvidence = timetable.routeAccessData().routeEdgeEvidence().stream()
			.filter(edge -> "entry-a".equals(edge.edgeId()))
			.findFirst()
			.orElseThrow();
		assertThat(entryEvidence.strictRouteEligible()).isFalse();
		assertThat(entryEvidence.blockerReason()).isEqualTo("verified fixture");

		var exitEvidence = timetable.routeAccessData().routeEdgeEvidence().stream()
			.filter(edge -> "exit-b".equals(edge.edgeId()))
			.findFirst()
			.orElseThrow();
		assertThat(exitEvidence.strictRouteEligible()).isTrue();
		assertThat(exitEvidence.blockerReason()).isNull();

		var runtime = new RouteBundleSqliteRuntimeCompiler().compile(input(payloads));
		assertThat(runtime.routeBundleSha256()).isEqualTo(SHA);
	}

	@Test
	void projectsTransferEdgesWithStrictAndBlockedStates() throws Exception {
		var transferEdges = List.of(
			new Edge("entry-a", "station-a", "station-a:line-1:platform-a", 120, 60, "ENTRY", "", "SUBWAY", 0),
			new Edge("ride-a-b", "station-a:line-1:platform-a", "station-b:line-1:platform-b", 600, 1000, "RIDE", "LOCAL", "SUBWAY", 0),
			new Edge("transfer-pass", "station-b:line-1:platform-b", "station-b:line-2:platform-b", 90, 50, "IN_STATION_TRANSFER", "", "SUBWAY", 0),
			new Edge("transfer-stairs", "station-b:line-1:platform-b", "station-b:line-2:platform-b", 90, 50, "IN_STATION_TRANSFER", "", "SUBWAY", 1),
			new Edge("transfer-blocked", "station-b:line-1:platform-b", "station-b:line-2:platform-b", 90, 50, "IN_STATION_TRANSFER", "", "SUBWAY", 0),
			new Edge("transfer-blocked-stairs", "station-b:line-1:platform-b", "station-b:line-2:platform-b", 90, 50, "IN_STATION_TRANSFER", "", "SUBWAY", 1),
			new Edge("exit-b", "station-b:line-2:platform-b", "station-b", 60, 40, "EXIT", "", "SUBWAY", 0));

		var states = Map.of(
			"transfer-blocked", "BLOCKED",
			"transfer-blocked-stairs", "BLOCKED");

		var payloads = payloads(transferEdges, value -> value, "AVAILABLE");
		var accessibility = sqlite("accessibility-transfers", connection -> {
			common(connection, identitySql());
			facilities(connection);
			execute(connection, "CREATE TABLE route_accessibility_edge_evidence (evaluation_digest TEXT NOT NULL PRIMARY KEY, materialization_digest TEXT NOT NULL, canonical_json TEXT NOT NULL)");
			var evaluation = evaluation(transferEdges, states);
			insert(connection, "INSERT INTO route_accessibility_edge_evidence VALUES(?,?,?)",
				evaluation.path("evaluationDigest").textValue(), "c".repeat(64), canonical(evaluation));
		});
		payloads.put(RouteBundleSqliteRuntimeCompiler.ACCESSIBILITY_PATH, Zstd.compress(accessibility, 10));

		var timetable = new RouteBundleSqliteRuntimeCompiler().readTimetable(input(payloads));
		var rules = timetable.routeAccessData().transferRules();

		var passRule = rules.stream().filter(r -> "transfer-pass".equals(r.id())).findFirst().orElseThrow();
		assertThat(passRule.strictStepFreePathwayEdgeId()).isEqualTo("transfer-pass");

		var stairsRule = rules.stream().filter(r -> "transfer-stairs".equals(r.id())).findFirst().orElseThrow();
		assertThat(stairsRule.strictStepFreePathwayEdgeId()).isNull();

		var blockedRule = rules.stream().filter(r -> "transfer-blocked".equals(r.id())).findFirst().orElseThrow();
		assertThat(blockedRule.strictStepFreePathwayEdgeId()).isNull();

		var blockedStairsRule = rules.stream().filter(r -> "transfer-blocked-stairs".equals(r.id())).findFirst().orElseThrow();
		assertThat(blockedStairsRule.strictStepFreePathwayEdgeId()).isNull();

		var runtime = new RouteBundleSqliteRuntimeCompiler().compile(input(payloads));
		assertThat(runtime.routeBundleSha256()).isEqualTo(SHA);
	}

	@Test
	void compilesRouteBundleWithDistinctDataCandidateIdInAccessibilityEvidence() throws Exception {
		var payloads = payloads();
		var accessibility = sqlite("accessibility-distinct-candidate", connection -> {
			common(connection, identitySql());
			facilities(connection);
			execute(connection, "CREATE TABLE route_accessibility_edge_evidence (evaluation_digest TEXT NOT NULL PRIMARY KEY, materialization_digest TEXT NOT NULL, canonical_json TEXT NOT NULL)");
			var evaluation = evaluation(topologyEdges(), Map.of(), "nationwide-candidate-20260909");
			insert(connection, "INSERT INTO route_accessibility_edge_evidence VALUES(?,?,?)",
				evaluation.path("evaluationDigest").textValue(), "c".repeat(64), canonical(evaluation));
		});
		payloads.put(RouteBundleSqliteRuntimeCompiler.ACCESSIBILITY_PATH, Zstd.compress(accessibility, 10));

		var runtime = new RouteBundleSqliteRuntimeCompiler().compile(input(payloads));
		assertThat(runtime.routeBundleSha256()).isEqualTo(SHA);
	}

	@Test
	void rejectsAccessibilityEvidenceWithBlankCandidateId() throws Exception {
		var payloads = payloads();
		var accessibility = sqlite("accessibility-blank-candidate", connection -> {
			common(connection, identitySql());
			facilities(connection);
			execute(connection, "CREATE TABLE route_accessibility_edge_evidence (evaluation_digest TEXT NOT NULL PRIMARY KEY, materialization_digest TEXT NOT NULL, canonical_json TEXT NOT NULL)");
			var evaluation = evaluation(topologyEdges(), Map.of(), "   ");
			insert(connection, "INSERT INTO route_accessibility_edge_evidence VALUES(?,?,?)",
				evaluation.path("evaluationDigest").textValue(), "c".repeat(64), canonical(evaluation));
		});
		payloads.put(RouteBundleSqliteRuntimeCompiler.ACCESSIBILITY_PATH, Zstd.compress(accessibility, 10));

		assertThatThrownBy(() -> new RouteBundleSqliteRuntimeCompiler().compile(input(payloads)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("accessibility evidence identity is invalid");
	}

	@Test
	void projectsUnknownTopologyAttributesIntoEvaluatedStates() throws Exception {
		var transferEdges = List.of(
			new Edge("entry-a", "station-a", "station-a:line-1:platform-a", 120, 60, "ENTRY", "", "SUBWAY", 0),
			new Edge("ride-a-b", "station-a:line-1:platform-a", "station-b:line-1:platform-b", 600, 1000, "RIDE", "LOCAL", "SUBWAY", 0),
			new Edge("transfer-pass", "station-b:line-1:platform-b", "station-b:line-2:platform-b", 90, 50, "IN_STATION_TRANSFER", "", "SUBWAY", 0),
			new Edge("transfer-out", "station-b:line-1:platform-b", "station-c:line-2:platform-c", 120, 80, "OUT_OF_STATION_TRANSFER", "", "SUBWAY", 0),
			new Edge("transfer-blocked", "station-b:line-1:platform-b", "station-b:line-2:platform-b", 90, 50, "IN_STATION_TRANSFER", "", "SUBWAY", 0),
			new Edge("exit-b", "station-b:line-2:platform-b", "station-b", 60, 40, "EXIT", "", "SUBWAY", 0));

		var states = Map.of("transfer-blocked", "BLOCKED");
		var payloads = payloads(transferEdges, value -> value, "UNKNOWN", "UNKNOWN", "UNKNOWN");
		var accessibility = sqlite("accessibility-unknown", connection -> {
			common(connection, identitySql());
			facilities(connection);
			execute(connection, "CREATE TABLE route_accessibility_edge_evidence (evaluation_digest TEXT NOT NULL PRIMARY KEY, materialization_digest TEXT NOT NULL, canonical_json TEXT NOT NULL)");
			var evaluation = evaluation(transferEdges, states);
			insert(connection, "INSERT INTO route_accessibility_edge_evidence VALUES(?,?,?)",
				evaluation.path("evaluationDigest").textValue(), "c".repeat(64), canonical(evaluation));
		});
		payloads.put(RouteBundleSqliteRuntimeCompiler.ACCESSIBILITY_PATH, Zstd.compress(accessibility, 10));

		var timetable = new RouteBundleSqliteRuntimeCompiler().readTimetable(input(payloads));
		var edges = timetable.routeAccessData().pathwayEdges();

		var entryA = edges.stream().filter(e -> "entry-a".equals(e.id())).findFirst().orElseThrow();
		assertThat(entryA.accessibilityStatus()).isEqualTo("AVAILABLE");
		assertThat(entryA.provenanceKind()).isEqualTo("OFFICIAL_SOURCE");
		assertThat(entryA.verificationStatus()).isEqualTo("VERIFIED");

		var transferBlocked = edges.stream().filter(e -> "transfer-blocked".equals(e.id())).findFirst().orElseThrow();
		assertThat(transferBlocked.accessibilityStatus()).isEqualTo("UNAVAILABLE");
		assertThat(transferBlocked.provenanceKind()).isEqualTo("OFFICIAL_SOURCE");
		assertThat(transferBlocked.verificationStatus()).isEqualTo("VERIFIED");

		var rules = timetable.routeAccessData().transferRules();
		var passRule = rules.stream().filter(r -> "transfer-pass".equals(r.id())).findFirst().orElseThrow();
		assertThat(passRule.verificationStatus()).isEqualTo("VERIFIED");

		var outRule = rules.stream().filter(r -> "transfer-out".equals(r.id())).findFirst().orElseThrow();
		assertThat(outRule.transferType()).isEqualTo("OUT_OF_STATION");

		var evidenceList = timetable.routeAccessData().routeEdgeEvidence();
		var exitEvidence = evidenceList.stream().filter(e -> "exit-b".equals(e.edgeId())).findFirst().orElseThrow();
		assertThat(exitEvidence.provenanceKind()).isEqualTo("OFFICIAL_SOURCE");
		assertThat(exitEvidence.verificationStatus()).isEqualTo("VERIFIED");
		assertThat(exitEvidence.strictRouteEligible()).isTrue();

		var blockedEvidence = evidenceList.stream().filter(e -> "transfer-blocked".equals(e.edgeId())).findFirst().orElseThrow();
		assertThat(blockedEvidence.provenanceKind()).isEqualTo("OFFICIAL_SOURCE");
		assertThat(blockedEvidence.verificationStatus()).isEqualTo("VERIFIED");
		assertThat(blockedEvidence.strictRouteEligible()).isFalse();

		var runtime = new RouteBundleSqliteRuntimeCompiler().compile(input(payloads));
		assertThat(runtime.routeBundleSha256()).isEqualTo(SHA);
	}

	@Test
	void compilesOfficialPlatformGapGradesInDeclaredOrder() throws Exception {
		var payloads = payloadsWithGaps(true,
			gap("g1", "station-a", "line-1", "UP", "본선 2-1", 2, 1, "NARROW", "LOW", 0),
			gap("g2", "station-a", "line-1", "UP", "본선 1-2", 1, 2, "NORMAL", "HIGH", 0),
			gap("g3", "station-a", "line-1", "UP", "본선 1-1", 1, 1, "WIDE", "LOW", 1),
			gap("g4", "station-a", "line-1", "UP", "본선 3-1", 3, 1, "NORMAL", "NORMAL", 0),
			gap("g5", "station-a", "line-1", "UP", "본선 1-3", 1, 3, "NORMAL", "HIGH", 0),
			gap("g6", "station-a", "line-1", "DOWN", "하선 오이도 방면", null, null, "WIDE", "HIGH", 1));

		var timetable = new RouteBundleSqliteRuntimeCompiler().readTimetable(input(payloads));

		var up = timetable.routeAccessData().platformGaps().get(new PlatformGapKey("station-a", "line-1", "UP"));
		// WIDE -> NORMAL -> NARROW, 같은 간격이면 HIGH -> NORMAL -> LOW, 그다음 platformPosition 오름차순
		assertThat(up).containsExactly(
			new PlatformGap("본선 1-1", 1, 1, GapGrade.WIDE, HeightDiffGrade.LOW, true),
			new PlatformGap("본선 1-2", 1, 2, GapGrade.NORMAL, HeightDiffGrade.HIGH, false),
			new PlatformGap("본선 1-3", 1, 3, GapGrade.NORMAL, HeightDiffGrade.HIGH, false),
			new PlatformGap("본선 3-1", 3, 1, GapGrade.NORMAL, HeightDiffGrade.NORMAL, false),
			new PlatformGap("본선 2-1", 2, 1, GapGrade.NARROW, HeightDiffGrade.LOW, false));
		var down = timetable.routeAccessData().platformGaps()
			.get(new PlatformGapKey("station-a", "line-1", "DOWN"));
		assertThat(down).containsExactly(
			new PlatformGap("하선 오이도 방면", null, null, GapGrade.WIDE, HeightDiffGrade.HIGH, true));
	}

	@Test
	void treatsMissingPlatformGapTableAsNoGaps() throws Exception {
		var timetable = new RouteBundleSqliteRuntimeCompiler().readTimetable(input(payloads()));

		assertThat(timetable.routeAccessData().platformGaps()).isEmpty();
	}

	@Test
	void treatsEmptyPlatformGapTableAsNoGaps() throws Exception {
		var timetable = new RouteBundleSqliteRuntimeCompiler().readTimetable(input(payloadsWithGaps(true)));

		assertThat(timetable.routeAccessData().platformGaps()).isEmpty();
	}

	@Test
	void rejectsUnknownGapGrade() throws Exception {
		var payloads = payloadsWithGaps(false,
			gap("g1", "station-a", "line-1", "UP", "1-1", 1, 1, "좁음", "LOW", 0));

		assertThatThrownBy(() -> new RouteBundleSqliteRuntimeCompiler().readTimetable(input(payloads)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("invalid platform gap gap_grade: 좁음");
	}

	@Test
	void rejectsUnknownHeightDiffGrade() throws Exception {
		var payloads = payloadsWithGaps(false,
			gap("g1", "station-a", "line-1", "UP", "1-1", 1, 1, "WIDE", "HUGE", 0));

		assertThatThrownBy(() -> new RouteBundleSqliteRuntimeCompiler().readTimetable(input(payloads)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("invalid platform gap height_diff_grade: HUGE");
	}

	@Test
	void rejectsInvalidCurvedFlag() throws Exception {
		var payloads = payloadsWithGaps(false,
			gap("g1", "station-a", "line-1", "UP", "1-1", 1, 1, "WIDE", "LOW", 2));

		assertThatThrownBy(() -> new RouteBundleSqliteRuntimeCompiler().readTimetable(input(payloads)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("invalid platform gap curved: 2");
	}

	@Test
	void rejectsPlatformGapDirectionOutsideUpDown() throws Exception {
		var payloads = payloadsWithGaps(false,
			gap("g1", "station-a", "line-1", "EAST", "1-1", 1, 1, "WIDE", "LOW", 0));

		assertThatThrownBy(() -> new RouteBundleSqliteRuntimeCompiler().readTimetable(input(payloads)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("invalid platform gap direction: EAST");
	}

	@Test
	void rejectsPlatformGapTableWithNumericMillimetreColumns() throws Exception {
		var payloads = payloads();
		var accessibility = sqlite("accessibility-mm-gaps", connection -> {
			commonAccessibility(connection);
			execute(connection, """
				CREATE TABLE station_platform_gaps (
					id TEXT PRIMARY KEY, station_id TEXT NOT NULL, line_id TEXT NOT NULL, direction TEXT,
					platform_position TEXT NOT NULL, car_number INTEGER, door_number INTEGER,
					gap_mm INTEGER NOT NULL, height_diff_mm INTEGER NOT NULL, source_snapshot_id TEXT NOT NULL)
				""");
		});
		payloads.put(RouteBundleSqliteRuntimeCompiler.ACCESSIBILITY_PATH, Zstd.compress(accessibility, 10));

		assertThatThrownBy(() -> new RouteBundleSqliteRuntimeCompiler().readTimetable(input(payloads)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("SQLite table schema mismatch: station_platform_gaps");
	}

	private record GapRow(String id, String stationId, String lineId, String direction, String position,
		Integer car, Integer door, String gapGrade, String heightDiffGrade, int curved) {
	}

	private static GapRow gap(String id, String stationId, String lineId, String direction, String position,
		Integer car, Integer door, String gapGrade, String heightDiffGrade, int curved) {
		return new GapRow(id, stationId, lineId, direction, position, car, door, gapGrade, heightDiffGrade, curved);
	}

	private void commonAccessibility(Connection connection) throws Exception {
		common(connection, identitySql());
		facilities(connection);
		execute(connection, "CREATE TABLE route_accessibility_edge_evidence (evaluation_digest TEXT NOT NULL PRIMARY KEY, materialization_digest TEXT NOT NULL, canonical_json TEXT NOT NULL)");
		var evaluation = evaluation(topologyEdges());
		insert(connection, "INSERT INTO route_accessibility_edge_evidence VALUES(?,?,?)",
			evaluation.path("evaluationDigest").textValue(), "c".repeat(64), canonical(evaluation));
	}

	/** checked=true는 data 계약의 CHECK 제약을 그대로 두고, false는 계약 위반 값을 넣기 위해 제약을 뺀다. */
	private Map<String, byte[]> payloadsWithGaps(boolean checked, GapRow... rows) throws Exception {
		var payloads = payloads();
		var accessibility = sqlite("accessibility-gaps", connection -> {
			commonAccessibility(connection);
			execute(connection, """
				CREATE TABLE station_platform_gaps (
					id TEXT PRIMARY KEY,
					station_id TEXT NOT NULL,
					line_id TEXT NOT NULL,
					direction TEXT %s,
					platform_position TEXT NOT NULL,
					car_number INTEGER,
					door_number INTEGER,
					gap_grade TEXT NOT NULL %s,
					height_diff_grade TEXT NOT NULL %s,
					curved INTEGER NOT NULL %s,
					source_snapshot_id TEXT NOT NULL
				)
				""".formatted(
				checked ? "CHECK (direction IN ('UP','DOWN'))" : "",
				checked ? "CHECK (gap_grade IN ('NARROW','NORMAL','WIDE'))" : "",
				checked ? "CHECK (height_diff_grade IN ('LOW','NORMAL','HIGH'))" : "",
				checked ? "CHECK (curved IN (0,1))" : ""));
			for (var row : rows) {
				insert(connection, "INSERT INTO station_platform_gaps VALUES(?,?,?,?,?,?,?,?,?,?,?)",
					row.id(), row.stationId(), row.lineId(), row.direction(), row.position(), row.car(),
					row.door(), row.gapGrade(), row.heightDiffGrade(), row.curved(), "snap-1");
			}
		});
		payloads.put(RouteBundleSqliteRuntimeCompiler.ACCESSIBILITY_PATH, Zstd.compress(accessibility, 10));
		return payloads;
	}

	private RouteBundleSqliteRuntimeCompiler.Input input(Map<String, byte[]> payloads) {
		return input(payloads, payloadSha256s(payloads));
	}

	private static List<Object[]> exitBRequirementRows() {
		return List.of(
			new Object[] {"exit-b", "path-1", "station-a", EXIT_GROUP, E1_EXIT},
			new Object[] {"exit-b", "path-1", "station-a", DIRECTION_GROUP, D1},
			new Object[] {"exit-b", "path-1", "station-a", DIRECTION_GROUP, D2},
			new Object[] {"exit-b", "path-2", "station-a", EXIT_GROUP, E2_EXIT},
			new Object[] {"exit-b", "path-2", "station-a", DIRECTION_GROUP, D1},
			new Object[] {"exit-b", "path-2", "station-a", DIRECTION_GROUP, D2});
	}

	private Map<String, byte[]> payloadsWithRequirements(String requirementDdl, List<Object[]> requirementRows)
		throws Exception {
		var payloads = payloads();
		payloads.put(RouteBundleSqliteRuntimeCompiler.ACCESSIBILITY_PATH, Zstd.compress(sqlite("accessibility-requirements", connection -> {
			common(connection, identitySql());
			facilities(connection);
			for (String facilityId : List.of(E1_EXIT, E2_EXIT, D1, D2)) {
				insert(connection, "INSERT INTO facilities VALUES(?,?,?,?)", facilityId, "station-b", "ELEVATOR",
					"나역 엘리베이터 " + facilityId.substring(facilityId.lastIndexOf(':') + 1));
			}
			execute(connection, "CREATE TABLE route_accessibility_edge_evidence (evaluation_digest TEXT NOT NULL PRIMARY KEY, materialization_digest TEXT NOT NULL, canonical_json TEXT NOT NULL)");
			var evaluation = evaluation(topologyEdges());
			insert(connection, "INSERT INTO route_accessibility_edge_evidence VALUES(?,?,?)",
				evaluation.path("evaluationDigest").textValue(), "c".repeat(64), canonical(evaluation));
			execute(connection, requirementDdl);
			for (Object[] row : requirementRows) {
				insert(connection, "INSERT INTO transition_facility_requirement VALUES(?,?,?,?,?)", row);
			}
		}), 10));
		return payloads;
	}

	private static JdbcFacilityOperationalStatusRepository facilityStatusRepository() {
		var dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:bundle-facility-status-" + UUID.randomUUID()
				+ ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa", "");
		new ResourceDatabasePopulator(new ClassPathResource("db/migration/h2/V76__facility_operational_status.sql"))
			.execute(dataSource);
		return new JdbcFacilityOperationalStatusRepository(dataSource, new DataSourceTransactionManager(dataSource));
	}

	// 수집기 한 회차와 같이 모든 시설 관측을 한 번에 반영하고 30초 뒤 공급자가 캐시를 갱신한다.
	private static void collect(
		JdbcFacilityOperationalStatusRepository repository,
		FacilityStatusOverlayProvider provider,
		MutableClock clock,
		Set<String> outOfService
	) {
		clock.advance(Duration.ofSeconds(30));
		var observations = new ArrayList<FeedObservation>();
		for (String facilityId : List.of(E1_EXIT, E2_EXIT, D1, D2)) {
			boolean out = outOfService.contains(facilityId);
			observations.add(new FeedObservation(facilityId,
				out ? FacilityOperationalState.OUT_OF_SERVICE : FacilityOperationalState.OPERATING, out ? "S" : "M"));
		}
		repository.applyFeedCollection(FacilityOperationalStatusStore.SEOUL_METRO_ELEVATOR_FEED, observations, clock.instant());
		provider.refresh();
	}

	private static JourneyRequest request(JourneyRequest.ConstraintMode constraintMode) {
		return new JourneyRequest(
			"01HZY3Q4J5K6M7N8P9Q0R1S2T3",
			"station-a",
			"station-b",
			new JourneyRequest.Departure.Scheduled(DEPARTURE),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD,
			constraintMode,
			0,
			1,
			() -> false);
	}

	private static ActiveJourneySnapshot snapshot(RaptorRouteBundleRuntimeView runtime) {
		return new ActiveJourneySnapshot(
			"active:7", BUNDLE_ID, SHA, "timetable", "accessibility", 7, runtime,
			DEPARTURE.plusSeconds(3600), true,
			com.easysubway.journey.application.ActiveJourneySnapshotPort.ActiveServingEvidence.unobservable(),
			com.easysubway.journey.application.ActiveJourneySnapshotPort.SnapshotBoundaryReceipt.observed(0, 0));
	}

	private static JourneyRaptorPort.PlanResult plan(
		JourneyRaptorAdapter adapter, JourneyRequest request, ActiveJourneySnapshot snapshot) {
		return adapter.plan(request, snapshot, DEPARTURE, null, new JourneyRequestMeasurement(request.requestId()));
	}

	private static final class MutableClock extends Clock {
		private Instant now;

		private MutableClock(Instant now) {
			this.now = now;
		}

		void advance(Duration duration) {
			now = now.plus(duration);
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
			return now;
		}
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
		return payloads(topologyEdges(), identityTransform, accessibilityStatus);
	}

	private Map<String, byte[]> payloads(
		List<Edge> edges,
		java.util.function.UnaryOperator<String> identityTransform,
		String accessibilityStatus
	) throws Exception {
		return payloads(edges, identityTransform, accessibilityStatus, "OFFICIAL_SOURCE", "VERIFIED");
	}

	private Map<String, byte[]> payloads(
		List<Edge> edges,
		java.util.function.UnaryOperator<String> identityTransform,
		String accessibilityStatus,
		String provenanceKind,
		String verificationStatus
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
			for (var edge : edges) {
				insert(connection, "INSERT INTO network_edges VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
					edge.id(), edge.from(), edge.to(), edge.duration(), edge.distance(), edge.type(), edge.pattern(),
					edge.serviceClass(), edge.includesStairs(), "VERIFIED_PRESENT", accessibilityStatus, 100, "official", "snapshot",
					"d".repeat(64), provenanceKind, verificationStatus, null, 1_786_485_600_000L,
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
			facilities(connection);
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
		insert(connection, "INSERT INTO lines VALUES(?)", "line-2");
		insert(connection, "INSERT INTO station_lines VALUES(?,?,?)", "station-a", "line-1", 1);
		insert(connection, "INSERT INTO station_lines VALUES(?,?,?)", "station-b", "line-1", 2);
		insert(connection, "INSERT INTO station_lines VALUES(?,?,?)", "station-b", "line-2", 3);
	}

	// data 번들 accessibility 구성요소의 facilities 표(catalog-schema)에서 컴파일러가 읽는 열만 둔다.
	private static void facilities(Connection connection) throws Exception {
		execute(connection, "CREATE TABLE facilities (id TEXT NOT NULL PRIMARY KEY, station_id TEXT NOT NULL, type TEXT NOT NULL, name TEXT NOT NULL)");
		insert(connection, "INSERT INTO facilities VALUES(?,?,?,?)",
			"smrt-elev:0201:2:9번 출입구", "station-b", "ELEVATOR", "나역 엘리베이터 9번 출입구");
		insert(connection, "INSERT INTO facilities VALUES(?,?,?,?)",
			"smrt-elev:0150:1:나역 방면1-1", "station-a", "ELEVATOR", "가역 엘리베이터 나역 방면1-1");
		insert(connection, "INSERT INTO facilities VALUES(?,?,?,?)",
			"facility-a-escalator", "station-a", "ESCALATOR", "가역 에스컬레이터");
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
		return evaluation(edges, states, BUNDLE_ID);
	}

	private static ObjectNode evaluation(
		List<Edge> edges, Map<String, String> states, String candidateId) throws Exception {
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
		payload.set("candidate", JSON.createObjectNode().put("candidateId", candidateId));
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
		String serviceClass, int includesStairs) {
		Edge(String id, String from, String to, int duration, int distance, String type, String pattern, String serviceClass) {
			this(id, from, to, duration, distance, type, pattern, serviceClass, 0);
		}
	}
}

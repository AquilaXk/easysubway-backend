package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.application.port.in.RouteSearchUseCase;
import com.easysubway.route.application.port.in.RouteV2SearchUseCase.RouteV2Plan;
import com.easysubway.route.application.port.in.RouteV2SearchUseCase.RouteV2Status;
import com.easysubway.route.application.port.in.RouteV2SearchUseCase.SearchRouteV2Command;
import com.easysubway.route.application.port.in.SearchInternalRouteCommand;
import com.easysubway.route.application.port.in.SearchRouteCommand;
import com.easysubway.route.application.port.in.SubmitRouteFeedbackCommand;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import com.easysubway.route.domain.ConstraintMode;
import com.easysubway.route.domain.InternalRouteResult;
import com.easysubway.route.domain.RouteFeedback;
import com.easysubway.route.domain.RouteRefreshResult;
import com.easysubway.route.domain.RouteSearchResult;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.HexFormat;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@DisplayName("#2249 timetable compiled snapshot microbenchmark")
@EnabledIfEnvironmentVariable(named = "EASYSUBWAY_BENCHMARK", matches = "true")
class RouteTimetableRaptorPlannerBenchmarkTest {

	private static final String FIXTURE = "timetable/line4-timetable-seed.sql.gz";
	private static final String EVIDENCE = "timetable/server-timetable-snapshot-evidence.json";
	private static final ObjectMapper STRICT_JSON = new ObjectMapper(
		JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
		.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
	private static final int WARMUPS = 20;
	private static final int MEASUREMENTS = 100;
	private static String fixtureSha256;
	private static RouteV2Planner planner;
	private static RouteTimetableRaptorPlanner raptorPlanner;
	private static int legacyExpandedRoutes;
	private static int legacyExpandedTrips;

	@BeforeAll
	static void setUpFixture() throws Exception {
		var classLoader = RouteTimetableRaptorPlannerBenchmarkTest.class.getClassLoader();
		byte[] fixture = requireNonNull(classLoader.getResourceAsStream(FIXTURE)).readAllBytes();
		var evidence = STRICT_JSON.readTree(
			requireNonNull(classLoader.getResourceAsStream(EVIDENCE)).readAllBytes());
		fixtureSha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(fixture));
		assertThat(evidence.path("snapshotGzipSha256").asText()).isEqualTo(fixtureSha256);
		assertThat(evidence.path("snapshotGzipByteSize").isIntegralNumber()).isTrue();
		assertThat(evidence.path("snapshotGzipByteSize").asLong(-1)).isEqualTo(fixture.length);

		DataSource dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:route-timetable-benchmark;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		var jdbc = new JdbcTemplate(dataSource);
		jdbc.execute("RUNSCRIPT FROM 'src/main/resources/db/migration/h2/V16__datapack_source_snapshots.sql'");
		jdbc.execute("RUNSCRIPT FROM 'src/main/resources/db/migration/h2/V17__datapack_alias_quarantine_ledgers.sql'");
		jdbc.execute("RUNSCRIPT FROM 'src/main/resources/db/migration/h2/V19__datapack_route_edge_evidence.sql'");
		jdbc.execute("RUNSCRIPT FROM 'src/main/resources/db/migration/h2/V23__datapack_source_snapshot_raw_evidence_policy.sql'");
		jdbc.execute("RUNSCRIPT FROM 'src/main/resources/db/migration/h2/V29__canonical_transit_schedule.sql'");
		jdbc.execute("RUNSCRIPT FROM 'src/main/resources/db/migration/h2/V30__canonical_station_pathways.sql'");
		jdbc.execute("RUNSCRIPT FROM 'src/main/resources/db/migration/h2/V37__transit_feed_info.sql'");
		jdbc.execute("RUNSCRIPT FROM 'src/main/resources/db/migration/h2/V50__route_service_identity.sql'");
		jdbc.execute("RUNSCRIPT FROM 'src/main/resources/db/migration/h2/V52__datapack_source_governance.sql'");
		jdbc.execute("RUNSCRIPT FROM 'src/main/resources/db/migration/h2/V61__timetable_snapshot_state.sql'");
		jdbc.execute("RUNSCRIPT FROM 'src/main/resources/db/migration/h2/V62__route_v2_planner_identity.sql'");
		jdbc.execute("RUNSCRIPT FROM 'src/main/resources/db/migration/h2/V74__route_service_station_catalog_evidence.sql'");
		try (var reader = new BufferedReader(new InputStreamReader(
			new GZIPInputStream(new ByteArrayInputStream(fixture)), StandardCharsets.UTF_8))) {
			reader.lines().filter(line -> !line.isBlank()).forEach(jdbc::execute);
		}
		var timetable = new com.easysubway.route.adapter.out.persistence.JdbcRouteTimetableRepository(dataSource)
			.loadRouteTimetable();
		LoadRouteTimetablePort port = new LoadRouteTimetablePort() {
			@Override
			public RouteTimetable loadRouteTimetable() {
				return timetable;
			}

			@Override
			public boolean hasRouteTimetable() {
				return true;
			}

			@Override
			public RouteTimetableSnapshot loadRouteTimetableSnapshot() {
				return new RouteTimetableSnapshot(fixtureSha256, "issue-2249-full-feed", timetable);
			}

			@Override
			public String timetableCacheKey() {
				return fixtureSha256;
			}
		};
		planner = new RouteV2Planner(new NoLegacyRouteSearch(), port);
		var plannerField = RouteV2Planner.class.getDeclaredField("timetableRaptorPlanner");
		plannerField.setAccessible(true);
		raptorPlanner = (RouteTimetableRaptorPlanner) plannerField.get(planner);
		var compiled = raptorPlanner.compile(timetable);
		legacyExpandedRoutes = compiled.routePatternCount();
		legacyExpandedTrips = compiled.activeTripCount(java.time.LocalDate.of(2026, 7, 6));
	}

	@Test
	@DisplayName("full-feed hot query latency와 allocation raw sample을 기록한다")
	void recordsHotQueryLatencyAndAllocation() {
		var morning = command("2026-07-06T06:50:00+09:00");
		var afterLast = command("2026-07-07T02:59:00+09:00");

		Measurement morningResult = measure("morning-hot-search", () -> planner.search(morning));
		Measurement afterLastResult = measure("after-last-search-next-service", () -> planner.search(afterLast));

		assertThat(morningResult.lastPlan().statuses()).contains(RouteV2Status.FOUND);
		assertThat(afterLastResult.lastPlan().statuses()).contains(RouteV2Status.NO_TIMETABLE_SERVICE);
		assertThat(afterLastResult.lastPlan().nextServiceTime()).isNotNull();
	}

	@Test
	@DisplayName("allocation benchmark는 com.sun.management ThreadMXBean이 아니면 명확히 실패한다")
	void rejectsThreadMxBeanWithoutAllocationApi() {
		var bean = java.lang.management.ThreadMXBean.class.cast(java.lang.reflect.Proxy.newProxyInstance(
			getClass().getClassLoader(),
			new Class<?>[] { java.lang.management.ThreadMXBean.class },
			(proxy, method, arguments) -> {
				throw new AssertionError("standard ThreadMXBean method must not be called");
			}
		));

		assertThatThrownBy(() -> requireThreadAllocationBean(bean))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("benchmark requires com.sun.management.ThreadMXBean");
	}

	@Test
	@DisplayName("allocation benchmark는 thread allocation 측정 미지원 JVM에서 명확히 실패한다")
	void rejectsThreadMxBeanWithoutAllocationSupport() {
		var bean = com.sun.management.ThreadMXBean.class.cast(java.lang.reflect.Proxy.newProxyInstance(
			getClass().getClassLoader(),
			new Class<?>[] { com.sun.management.ThreadMXBean.class },
			(proxy, method, arguments) -> false
		));

		assertThatThrownBy(() -> requireThreadAllocationBean(bean))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("benchmark requires thread allocation measurement support");
	}

	private static Measurement measure(String scenario, Supplier<RouteV2Plan> query) {
		for (int index = 0; index < WARMUPS; index += 1) {
			query.get();
		}
		long[] nanos = new long[MEASUREMENTS];
		long[] allocatedBytes = new long[MEASUREMENTS];
		var bean = requireThreadAllocationBean(ManagementFactory.getThreadMXBean());
		if (!bean.isThreadAllocatedMemoryEnabled()) {
			bean.setThreadAllocatedMemoryEnabled(true);
		}
		long threadId = Thread.currentThread().threadId();
		RouteV2Plan lastPlan = null;
		for (int index = 0; index < MEASUREMENTS; index += 1) {
			long allocatedBefore = bean.getThreadAllocatedBytes(threadId);
			long startedAt = System.nanoTime();
			lastPlan = query.get();
			nanos[index] = System.nanoTime() - startedAt;
			allocatedBytes[index] = bean.getThreadAllocatedBytes(threadId) - allocatedBefore;
		}
		System.out.printf(
			"BENCHMARK_RAW {\"fixture\":\"%s\",\"fixtureSha256\":\"%s\",\"scenario\":\"%s\","
				+ "\"warmups\":%d,\"measurements\":%d,\"legacyExpandedRoutes\":%d,\"legacyExpandedTrips\":%d,"
				+ "\"expandedRoutes\":%d,\"expandedTrips\":%d,"
				+ "\"nanos\":%s,\"allocatedBytes\":%s}%n",
			FIXTURE,
			fixtureSha256,
			scenario,
			WARMUPS,
			MEASUREMENTS,
			legacyExpandedRoutes,
			legacyExpandedTrips,
			raptorPlanner.lastScanMetrics().expandedRoutes(),
			raptorPlanner.lastScanMetrics().expandedTrips(),
			Arrays.toString(nanos),
			Arrays.toString(allocatedBytes)
		);
		return new Measurement(lastPlan);
	}

	private static com.sun.management.ThreadMXBean requireThreadAllocationBean(
		java.lang.management.ThreadMXBean platformBean
	) {
		if (!(platformBean instanceof com.sun.management.ThreadMXBean allocationBean)) {
			throw new IllegalStateException("benchmark requires com.sun.management.ThreadMXBean");
		}
		if (!allocationBean.isThreadAllocatedMemorySupported()) {
			throw new IllegalStateException("benchmark requires thread allocation measurement support");
		}
		return allocationBean;
	}

	private static SearchRouteV2Command command(String departureTime) {
		return new SearchRouteV2Command(
			"station-sangnoksu",
			"station-sadang",
			OffsetDateTime.parse(departureTime),
			MobilityType.SENIOR,
			ConstraintMode.ALLOW_WITH_WARNINGS,
			false,
			0,
			3
		);
	}

	private record Measurement(RouteV2Plan lastPlan) {
	}

	private static final class NoLegacyRouteSearch implements RouteSearchUseCase {
		@Override
		public RouteSearchResult searchRoute(SearchRouteCommand command) {
			throw new AssertionError("legacy route search must not be called");
		}

		@Override
		public List<RouteSearchResult> searchRouteAlternatives(SearchRouteCommand command, int alternativeCount) {
			throw new AssertionError("legacy route search must not be called");
		}

		@Override
		public InternalRouteResult searchInternalRoute(SearchInternalRouteCommand command) {
			throw new AssertionError("legacy route search must not be called");
		}

		@Override
		public RouteSearchResult getRouteSearch(String routeSearchId) {
			throw new AssertionError("legacy route search must not be called");
		}

		@Override
		public RouteRefreshResult refreshRoute(String routeSearchId) {
			throw new AssertionError("legacy route search must not be called");
		}

		@Override
		public RouteFeedback submitRouteFeedback(SubmitRouteFeedbackCommand command) {
			throw new AssertionError("legacy route search must not be called");
		}
	}
}

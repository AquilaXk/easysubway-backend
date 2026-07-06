package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.adapter.out.persistence.JdbcRouteTimetableRepository;
import com.easysubway.route.application.port.in.RouteV2SearchUseCase.SearchRouteV2Command;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import com.easysubway.route.domain.ConstraintMode;
import com.easysubway.route.domain.RouteSearchResult;
import com.easysubway.route.domain.RouteSearchStatus;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * #1415 ③c: KRIC 4호선 실 시각표(라이브 수집→재구성→seed)로 백엔드 RAPTOR 플래너가 PLANNED 경로를
 * 처음으로 산출함을 실증한다. seed는 build-backend-timetable-seed 도구가 코리도 슬라이스에서 생성한
 * 실데이터(src/test/resources/timetable/line4-corridor-slice-seed.sql).
 */
@DisplayName("4호선 실데이터 timetable PLANNED 가동")
class RouteTimetableRaptorPlannerLine4PlannedTest {

	private static final String SANGNOKSU = "station-seoul-4-448";
	private static final String SADANG = "station-seoul-4-433";

	private RouteTimetable timetable;

	@BeforeEach
	void setUp() {
		var dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:line4-planned;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		var jdbc = new JdbcTemplate(dataSource);
		jdbc.execute("DROP ALL OBJECTS");
		jdbc.execute("RUNSCRIPT FROM 'src/main/resources/db/migration/h2/V29__canonical_transit_schedule.sql'");
		jdbc.execute("RUNSCRIPT FROM 'src/main/resources/db/migration/h2/V37__transit_feed_info.sql'");
		jdbc.execute("RUNSCRIPT FROM 'src/test/resources/timetable/line4-corridor-slice-seed.sql'");
		timetable = new JdbcRouteTimetableRepository(dataSource).loadRouteTimetable();
	}

	@Test
	@DisplayName("상록수→사당 평일 아침 조회는 실 시각표 기반 PLANNED 경로를 반환한다")
	void plansPlannedRouteFromRealLine4Timetable() {
		var command = new SearchRouteV2Command(
			SANGNOKSU,
			SADANG,
			// 2026-07-06(월) 06:50 KST — 상록수 07:00 열차를 탈 수 있는 평일 아침.
			OffsetDateTime.of(2026, 7, 6, 6, 50, 0, 0, ZoneOffset.ofHours(9)),
			MobilityType.SENIOR,
			ConstraintMode.ALLOW_WITH_WARNINGS,
			false,
			0,
			3
		);

		List<RouteSearchResult> results = new RouteTimetableRaptorPlanner().search(command, timetable);

		assertThat(results).isNotEmpty();
		RouteSearchResult best = results.getFirst();
		assertThat(best.status()).isEqualTo(RouteSearchStatus.FOUND);
		assertThat(best.originStationId()).isEqualTo(SANGNOKSU);
		assertThat(best.destinationStationId()).isEqualTo(SADANG);
		// 승차 leg는 시간표 기반 PLANNED, 실 소요(상록수 07:00→사당 ~07:37 = 37분) 범위.
		assertThat(best.steps())
			.anyMatch(step -> "PLANNED".equals(step.timeSource())
				&& step.estimatedMinutes() >= 30
				&& step.estimatedMinutes() <= 55);
	}
}

package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.adapter.out.persistence.JdbcRouteTimetableRepository;
import com.easysubway.route.application.port.in.RouteV2SearchUseCase.SearchRouteV2Command;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import com.easysubway.route.domain.ConstraintMode;
import com.easysubway.route.domain.EtaSource;
import com.easysubway.route.domain.RouteSearchResult;
import com.easysubway.route.domain.RouteSearchStatus;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * #1620 golden OD 6종: KRIC 4호선 코리도 실 시각표(build-backend-timetable-seed 재구성) 기준으로
 * RAPTOR planner가 산출하는 대표 OD 경로/경계 동작을 회귀로 고정한다. 모든 기대값은
 * src/test/resources/timetable/line4-corridor-slice-seed.sql 의 실 운행시각에서 유도했다.
 *
 * 코리도 실측 (평일 weekday-kric, 하행 down-only):
 *   448 상록수 dep  K4422 07:00 / K4308 07:03 / K4524 07:09
 *   433 사당   arr  K4422 07:37:30 / K4308 07:44 / K4524 07:49:30
 *   456 당고개 dep  K4422 06:42 / K4524 06:48
 *   409       arr  K4422 08:30:30 / K4308 08:37:30 / K4524 08:43
 */
@DisplayName("#1620 RAPTOR golden OD 6종 (4호선 코리도 실데이터)")
class RouteTimetableRaptorPlannerGoldenOdTest {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	private static final ZoneOffset KST = ZoneOffset.ofHours(9);

	private static final String SANGNOKSU = "station-seoul-4-448";
	private static final String SADANG = "station-seoul-4-433";
	private static final String DANGOGAE = "station-seoul-4-456";
	private static final String OIDO = "station-seoul-4-409";

	private final RouteTimetableRaptorPlanner planner = new RouteTimetableRaptorPlanner();
	private RouteTimetable timetable;

	@BeforeEach
	void setUp() {
		var dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:line4-golden;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
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
	@DisplayName("OD1 상록수→사당 평일 아침은 가장 이른 도착 열차(07:37:30)에 앵커된다")
	void od1_sangnoksuToSadangMorningEarliestArrival() {
		RouteSearchResult best = firstResult(SANGNOKSU, SADANG, weekday(6, 50));

		assertThat(best.status()).isEqualTo(RouteSearchStatus.FOUND);
		assertThat(best.originStationId()).isEqualTo(SANGNOKSU);
		assertThat(best.destinationStationId()).isEqualTo(SADANG);
		assertThat(best.transferCount()).isZero();
		assertThat(best.etaSource()).isEqualTo(EtaSource.PLANNED);
		// K4422 상록수 07:00→사당 07:37:30 = 37.5분 승차 leg (모든 leg가 시간표 PLANNED).
		assertThat(rideMinutes(best)).isBetween(35, 40);
		assertThat(best.steps()).allMatch(step -> EtaSource.PLANNED.name().equals(step.timeSource()));
	}

	@Test
	@DisplayName("OD2 당고개→오이도 전 구간은 하행 종주 열차 한 대로 연결된다")
	void od2_dangogaeToOidoFullCorridorSingleRide() {
		RouteSearchResult best = firstResult(DANGOGAE, OIDO, weekday(6, 30));

		assertThat(best.status()).isEqualTo(RouteSearchStatus.FOUND);
		assertThat(best.originStationId()).isEqualTo(DANGOGAE);
		assertThat(best.destinationStationId()).isEqualTo(OIDO);
		assertThat(best.transferCount()).isZero();
		// K4422 당고개 06:42→오이도 08:30:30 = 108.5분 단일 승차.
		assertThat(rideMinutes(best)).isBetween(105, 112);
	}

	@Test
	@DisplayName("OD3 EXPRESS 를 놓친 조회는 다음 LOCAL 열차(더 긴 승차)에 정직하게 앵커된다")
	void od3_missedExpressAnchorsToNextLocalTrain() {
		// SENIOR 진입 도보(240×1.35=324s)+slack 90s = 414s. 06:55 출발이면 ready 06:55+414s=07:01:54 라
		// EXPRESS K4422(07:00) 는 놓치고 LOCAL K4308(07:03 출발→사당 07:44 도착)에 탑승한다.
		RouteSearchResult best = firstResult(SANGNOKSU, SADANG, weekday(6, 55));

		assertThat(best.status()).isEqualTo(RouteSearchStatus.FOUND);
		assertThat(best.etaSource()).isEqualTo(EtaSource.PLANNED);
		// K4308 상록수 07:03→사당 07:44 = 41분 승차 — OD1 의 EXPRESS(37.5분→38)보다 길다.
		assertThat(rideMinutes(best))
			.as("EXPRESS 를 놓쳤으므로 승차 leg 는 LOCAL 소요(41분)여야 한다")
			.isBetween(40, 42);
	}

	@Test
	@DisplayName("OD4 막차 이후 조회는 결과가 없고 다음 운행일 첫 열차 시각을 안내한다")
	void od4_afterLastTrainReturnsEmptyWithNextServiceTime() {
		var command = command(SANGNOKSU, SADANG, weekday(23, 0));

		assertThat(planner.search(command, timetable)).isEmpty();

		Optional<OffsetDateTime> nextServiceTime = planner.nextServiceTime(command, timetable);
		assertThat(nextServiceTime).isPresent();
		var nextInSeoul = nextServiceTime.get().atZoneSameInstant(SEOUL);
		// 다음 평일(화 2026-07-07) 상록수 첫 탑승 열차 = K4422 07:00.
		assertThat(nextInSeoul.toLocalDate()).isEqualTo(LocalDate.of(2026, 7, 7));
		assertThat(nextInSeoul.toLocalTime().toString()).isEqualTo("07:00");
	}

	@Test
	@DisplayName("OD5 주말 조회는 운행 calendar에서 제외되어 다음 평일로 안내된다")
	void od5_weekendHasNoServiceAndSkipsToNextWeekday() {
		// 2026-07-11 은 토요일 — weekday-kric calendar(월~금)에 미포함.
		var command = command(SANGNOKSU, SADANG, atKst(LocalDate.of(2026, 7, 11), 6, 50));

		assertThat(planner.search(command, timetable)).isEmpty();

		Optional<OffsetDateTime> nextServiceTime = planner.nextServiceTime(command, timetable);
		assertThat(nextServiceTime).isPresent();
		var nextInSeoul = nextServiceTime.get().atZoneSameInstant(SEOUL);
		// 다음 평일 = 월 2026-07-13, 첫 탑승 열차 07:00.
		assertThat(nextInSeoul.toLocalDate()).isEqualTo(LocalDate.of(2026, 7, 13));
		assertThat(nextInSeoul.toLocalTime().toString()).isEqualTo("07:00");
	}

	@Test
	@DisplayName("OD6 하행 전용 코리도에서 역방향(사당→상록수)은 결과·다음시각을 지어내지 않는다")
	void od6_reverseDirectionOnDownOnlyCorridorFabricatesNothing() {
		var command = command(SADANG, SANGNOKSU, weekday(6, 50));

		assertThat(planner.search(command, timetable)).isEmpty();
		// 상행 trip 이 시드에 존재하지 않으므로 어떤 미래 운행일도 이 OD 를 만족시키지 못한다.
		assertThat(planner.nextServiceTime(command, timetable)).isEmpty();
	}

	private RouteSearchResult firstResult(String origin, String destination, OffsetDateTime departure) {
		List<RouteSearchResult> results = planner.search(command(origin, destination, departure), timetable);
		assertThat(results).as("golden OD 는 최소 1개 후보를 반환해야 한다").isNotEmpty();
		assertThat(results).hasSizeLessThanOrEqualTo(3);
		return results.getFirst();
	}

	private static SearchRouteV2Command command(String origin, String destination, OffsetDateTime departure) {
		return new SearchRouteV2Command(
			origin,
			destination,
			departure,
			MobilityType.SENIOR,
			ConstraintMode.ALLOW_WITH_WARNINGS,
			false,
			0,
			3
		);
	}

	private static int rideMinutes(RouteSearchResult result) {
		return result.steps().stream()
			.filter(step -> "ride".equals(step.stepType()))
			.mapToInt(com.easysubway.route.domain.RouteStep::estimatedMinutes)
			.sum();
	}

	private static OffsetDateTime weekday(int hour, int minute) {
		// 2026-07-06 은 월요일(평일).
		return atKst(LocalDate.of(2026, 7, 6), hour, minute);
	}

	private static OffsetDateTime atKst(LocalDate date, int hour, int minute) {
		return OffsetDateTime.of(date.getYear(), date.getMonthValue(), date.getDayOfMonth(), hour, minute, 0, 0, KST);
	}
}

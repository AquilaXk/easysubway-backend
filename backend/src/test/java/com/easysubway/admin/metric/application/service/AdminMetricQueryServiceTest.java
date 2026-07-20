package com.easysubway.admin.metric.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.easysubway.admin.metric.adapter.in.web.AnalyticsComparisonCard;
import com.easysubway.admin.metric.adapter.out.persistence.InMemoryAdminMetricDailyRepository;
import com.easysubway.admin.metric.application.service.AdminMetricQueryService.AdminMetricChart;
import com.easysubway.admin.metric.application.service.AdminMetricQueryService.AdminMetricComparison;
import com.easysubway.admin.metric.domain.AdminMetricDaily;
import com.easysubway.admin.metric.domain.AdminMetricKeys;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("분석 지표 조회 서비스 — 기간 비교")
class AdminMetricQueryServiceTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 7, 6);
	private static final ZoneId ZONE = ZoneOffset.UTC;

	private final InMemoryAdminMetricDailyRepository repository = new InMemoryAdminMetricDailyRepository();
	private final AdminMetricQueryService service = new AdminMetricQueryService(
		repository, Clock.fixed(TODAY.atStartOfDay(ZONE).toInstant(), ZONE));

	@Test
	@DisplayName("누계(GAUGE) 지표는 기간 합산이 아니라 기간 내 최신 스냅샷끼리 비교한다")
	void cumulativeGaugeComparesLatestSnapshotNotSum() {
		// route.searches는 all-time 누적 총량 스냅샷이라 일별 합산은 중복 합산(#2273 결함).
		// 최근 7일 [06-30, 07-06] 누적 스냅샷: 07-02=100, 07-05=150, 07-06=175 → 최신 175(합산이면 425).
		save(AdminMetricKeys.ROUTE_SEARCHES, TODAY.minusDays(4), 100);
		save(AdminMetricKeys.ROUTE_SEARCHES, TODAY.minusDays(1), 150);
		save(AdminMetricKeys.ROUTE_SEARCHES, TODAY, 175);
		// 직전 7일 [06-23, 06-29] 누적 스냅샷: 06-28=80, 06-29=90 → 최신 90(합산이면 170).
		save(AdminMetricKeys.ROUTE_SEARCHES, TODAY.minusDays(8), 80);
		save(AdminMetricKeys.ROUTE_SEARCHES, TODAY.minusDays(7), 90);

		AdminMetricComparison comparison = service.compare(List.of(AdminMetricKeys.ROUTE_SEARCHES), 7).getFirst();

		assertThat(comparison.current()).isEqualTo(175.0);
		assertThat(comparison.previous()).isEqualTo(90.0);
		assertThat(comparison.delta()).isEqualTo(85.0);
		assertThat(comparison.deltaPercent()).isCloseTo(94.444, within(0.001));
		assertThat(comparison.label()).isEqualTo("경로 검색");
	}

	@Test
	@DisplayName("이동 기간(ROLLING_WINDOW) 지표는 스냅샷이 겹치므로 합산하지 않고 최신 스냅샷을 비교한다")
	void rollingWindowComparesLatestSnapshotNotSum() {
		// users.active는 최근 7일 이동 활성 사용자라 스냅샷이 겹친다. 합산하면 중복(#2273 결함).
		save(AdminMetricKeys.USERS_ACTIVE, TODAY.minusDays(2), 20);
		save(AdminMetricKeys.USERS_ACTIVE, TODAY, 25);
		save(AdminMetricKeys.USERS_ACTIVE, TODAY.minusDays(7), 10);

		AdminMetricComparison comparison = service.compare(List.of(AdminMetricKeys.USERS_ACTIVE), 7).getFirst();

		assertThat(comparison.current()).isEqualTo(25.0); // 최신 스냅샷(합산이면 45)
		assertThat(comparison.previous()).isEqualTo(10.0);
		assertThat(comparison.deltaPercent()).isCloseTo(150.0, within(0.001));
	}

	@Test
	@DisplayName("비율(RATE) 지표는 합계·평균이 아니라 기간 내 최신 스냅샷을 비교한다")
	void rateComparesLatestSnapshot() {
		// 최근 7일 차단률 스냅샷: 07-04=20, 07-06=30 → 최신 30(합산 50·평균 25 아님).
		save(AdminMetricKeys.ROUTE_BLOCKED_RATE, TODAY.minusDays(2), 20);
		save(AdminMetricKeys.ROUTE_BLOCKED_RATE, TODAY, 30);
		save(AdminMetricKeys.ROUTE_BLOCKED_RATE, TODAY.minusDays(7), 10);

		AdminMetricComparison comparison = service.compare(List.of(AdminMetricKeys.ROUTE_BLOCKED_RATE), 7).getFirst();

		assertThat(comparison.current()).isEqualTo(30.0);
		assertThat(comparison.previous()).isEqualTo(10.0);
		assertThat(comparison.deltaPercent()).isCloseTo(200.0, within(0.001));
	}

	@Test
	@DisplayName("스냅샷이 없는 기간의 최신 스냅샷은 0으로 본다(경계 안정)")
	void latestSnapshotIsZeroWhenPeriodEmpty() {
		save(AdminMetricKeys.REPORTS_PENDING, TODAY, 8);

		AdminMetricComparison comparison = service.compare(List.of(AdminMetricKeys.REPORTS_PENDING), 7).getFirst();

		assertThat(comparison.current()).isEqualTo(8.0);
		assertThat(comparison.previous()).isEqualTo(0.0);
	}

	@Test
	@DisplayName("직전 기간 값이 0이면 증감률은 정의하지 않는다(null)")
	void undefinedPercentWhenPreviousIsZero() {
		save(AdminMetricKeys.USERS_ACTIVE, TODAY, 30);

		AdminMetricComparison comparison = service.compare(List.of(AdminMetricKeys.USERS_ACTIVE), 7).getFirst();

		assertThat(comparison.current()).isEqualTo(30.0);
		assertThat(comparison.previous()).isEqualTo(0.0);
		assertThat(comparison.deltaPercent()).isNull();
	}

	@Test
	@DisplayName("직전 기간에 스냅샷이 없으면 부재로 보고 카드에 '직전 없음'으로 표기한다")
	void previousAbsentRendersNoPreviousLabel() {
		// 직전 7일 [06-23, 06-29]에 스냅샷이 하나도 없음 → previous는 0으로 채우지만 실측이 아니다.
		save(AdminMetricKeys.USERS_ACTIVE, TODAY, 30);

		AdminMetricComparison comparison = service.compare(List.of(AdminMetricKeys.USERS_ACTIVE), 7).getFirst();

		assertThat(comparison.previous()).isEqualTo(0.0);
		assertThat(comparison.previousPresent()).isFalse();
		assertThat(comparison.deltaPercent()).isNull();

		AnalyticsComparisonCard card = AnalyticsComparisonCard.from(comparison, true);
		assertThat(card.deltaPercentLabel()).isEqualTo("직전 없음");
	}

	@Test
	@DisplayName("직전 기간 실측값이 0이면 부재와 구분해 '기준 0 — 증가율 산정 불가'로 표기한다")
	void previousMeasuredZeroRendersUndefinedGrowthLabel() {
		// 직전 7일 [06-23, 06-29]에 실측 0 스냅샷 존재 → 부재가 아니라 실측된 0이다.
		save(AdminMetricKeys.USERS_ACTIVE, TODAY.minusDays(8), 0);
		save(AdminMetricKeys.USERS_ACTIVE, TODAY, 30);

		AdminMetricComparison comparison = service.compare(List.of(AdminMetricKeys.USERS_ACTIVE), 7).getFirst();

		assertThat(comparison.current()).isEqualTo(30.0);
		assertThat(comparison.previous()).isEqualTo(0.0);
		assertThat(comparison.previousPresent()).isTrue();
		assertThat(comparison.deltaPercent()).isNull();

		AnalyticsComparisonCard card = AnalyticsComparisonCard.from(comparison, true);
		assertThat(card.deltaPercentLabel()).isEqualTo("기준 0 — 증가율 산정 불가");
	}

	@Test
	@DisplayName("기간 label(days)은 요청 기간을 그대로 보존한다")
	void preservesRequestedPeriodLabel() {
		save(AdminMetricKeys.ROUTE_SEARCHES, TODAY, 100);

		AdminMetricComparison comparison = service.compare(List.of(AdminMetricKeys.ROUTE_SEARCHES), 30).getFirst();

		assertThat(comparison.days()).isEqualTo(30);
	}

	@Test
	@DisplayName("조회 기간 내 스냅샷이 하나도 없으면 차트는 비어 있다(#2327 빈 상태 분기)")
	void chartIsEmptyWhenNoSnapshotInPeriod() {
		AdminMetricChart chart = service.chart(List.of(AdminMetricKeys.ROUTE_SEARCHES), 7);

		assertThat(chart.empty()).isTrue();
	}

	@Test
	@DisplayName("조회 기간 내 스냅샷이 하나라도 있으면 차트는 비어 있지 않다(#2327 빈 상태 분기)")
	void chartIsNotEmptyWhenAnySnapshotInPeriod() {
		save(AdminMetricKeys.ROUTE_SEARCHES, TODAY, 42);

		AdminMetricChart chart = service.chart(List.of(AdminMetricKeys.ROUTE_SEARCHES), 7);

		assertThat(chart.empty()).isFalse();
	}

	@Test
	@DisplayName("허용 밖 기간·미등록 키는 기본값으로 정규화한다")
	void normalizesInvalidInput() {
		List<AdminMetricComparison> comparisons = service.compare(List.of("made.up.key"), 13);

		assertThat(comparisons).allSatisfy(comparison -> assertThat(comparison.days()).isEqualTo(7));
		assertThat(comparisons).extracting(AdminMetricComparison::key)
			.contains(AdminMetricKeys.REPORTS_RECENT_24H, AdminMetricKeys.ROUTE_BLOCKED_RATE);
	}

	private void save(String key, LocalDate date, double value) {
		repository.save(AdminMetricDaily.scalar(key, date, value));
	}
}

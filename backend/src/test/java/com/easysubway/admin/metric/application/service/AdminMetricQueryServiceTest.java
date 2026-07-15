package com.easysubway.admin.metric.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.easysubway.admin.metric.adapter.out.persistence.InMemoryAdminMetricDailyRepository;
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
	@DisplayName("최근 기간과 직전 동일 기간 합계를 비교하고 결측일을 0으로 본다")
	void comparesCurrentAndPreviousPeriodSums() {
		// 최근 7일 [06-30, 07-06]: 07-06=100, 07-04=50, 나머지 결측 → 150
		save(AdminMetricKeys.ROUTE_SEARCHES, TODAY, 100);
		save(AdminMetricKeys.ROUTE_SEARCHES, TODAY.minusDays(2), 50);
		// 직전 7일 [06-23, 06-29]: 06-29=60, 나머지 결측 → 60
		save(AdminMetricKeys.ROUTE_SEARCHES, TODAY.minusDays(7), 60);

		AdminMetricComparison comparison = service.compare(List.of(AdminMetricKeys.ROUTE_SEARCHES), 7).getFirst();

		assertThat(comparison.current()).isEqualTo(150.0);
		assertThat(comparison.previous()).isEqualTo(60.0);
		assertThat(comparison.delta()).isEqualTo(90.0);
		assertThat(comparison.deltaPercent()).isCloseTo(150.0, within(0.001));
		assertThat(comparison.days()).isEqualTo(7);
		assertThat(comparison.label()).isEqualTo("경로 검색");
	}

	@Test
	@DisplayName("비율 지표는 합계 대신 값이 있는 날의 평균으로 비교한다")
	void ratesAreComparedByAverageOfPresentDays() {
		// 최근 7일 차단률: 07-06=30, 07-04=20 (present 2일 평균 25). 직전 7일: 06-29=10 (평균 10).
		save(AdminMetricKeys.ROUTE_BLOCKED_RATE, TODAY, 30);
		save(AdminMetricKeys.ROUTE_BLOCKED_RATE, TODAY.minusDays(2), 20);
		save(AdminMetricKeys.ROUTE_BLOCKED_RATE, TODAY.minusDays(7), 10);

		AdminMetricComparison comparison = service.compare(List.of(AdminMetricKeys.ROUTE_BLOCKED_RATE), 7).getFirst();

		assertThat(comparison.current()).isCloseTo(25.0, within(0.001));
		assertThat(comparison.previous()).isCloseTo(10.0, within(0.001));
		assertThat(comparison.deltaPercent()).isCloseTo(150.0, within(0.001));
	}

	@Test
	@DisplayName("직전 기간 합계가 0이면 증감률은 정의하지 않는다(null)")
	void undefinedPercentWhenPreviousIsZero() {
		save(AdminMetricKeys.USERS_ACTIVE, TODAY, 30);

		AdminMetricComparison comparison = service.compare(List.of(AdminMetricKeys.USERS_ACTIVE), 7).getFirst();

		assertThat(comparison.current()).isEqualTo(30.0);
		assertThat(comparison.previous()).isEqualTo(0.0);
		assertThat(comparison.deltaPercent()).isNull();
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

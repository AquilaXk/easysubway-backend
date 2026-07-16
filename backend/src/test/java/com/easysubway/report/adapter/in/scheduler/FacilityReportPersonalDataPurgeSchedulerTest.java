package com.easysubway.report.adapter.in.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.report.application.port.out.PurgeFacilityReportPersonalDataPort;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;

@DisplayName("시설 신고 개인정보 자동 파기 스케줄러")
class FacilityReportPersonalDataPurgeSchedulerTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(FacilityReportPersonalDataPurgeSchedulingConfiguration.class);

	@Test
	@DisplayName("일일 주기와 실행시간 안전 여유를 포함해도 기본 1년 보관 상한을 넘기지 않는다")
	void purgesPersonalDataOlderThanRetentionLimit() throws Exception {
		Instant now = Instant.parse("2026-07-16T12:00:00Z");
		AtomicReference<LocalDateTime> cutoff = new AtomicReference<>();
		PurgeFacilityReportPersonalDataPort port = value -> {
			cutoff.set(value);
			return 2;
		};
		var scheduler = new FacilityReportPersonalDataPurgeScheduler(
			port,
			Clock.fixed(now, ZoneOffset.UTC),
			365
		);

		scheduler.purgeExpiredPersonalData();

		assertThat(cutoff.get()).isEqualTo(LocalDateTime.of(2025, 7, 23, 12, 0));
		Method scheduledMethod = FacilityReportPersonalDataPurgeScheduler.class
			.getDeclaredMethod("purgeExpiredPersonalData");
		assertThat(scheduledMethod.getAnnotation(Scheduled.class).fixedRate())
			.isEqualTo(86_400_000L);
		assertThat(scheduledMethod.getAnnotation(Scheduled.class).fixedDelay()).isEqualTo(-1L);
		assertThat(scheduledMethod.getAnnotation(Scheduled.class).scheduler())
			.isEqualTo("facilityReportPersonalDataPurgeTaskScheduler");
		contextRunner.run(context -> {
			assertThat(context).hasBean("facilityReportPersonalDataPurgeTaskScheduler");
			assertThat(context.getBean("facilityReportPersonalDataPurgeTaskScheduler"))
				.isInstanceOf(TaskScheduler.class);
		});
	}

	@Test
	@DisplayName("최소 8일 보관 설정은 일일 실행 지연을 보정할 하루의 파기 경계를 남긴다")
	void keepsOneDayBoundaryAtMinimumRetentionPeriod() {
		Instant now = Instant.parse("2026-07-16T12:00:00Z");
		AtomicReference<LocalDateTime> cutoff = new AtomicReference<>();
		var scheduler = new FacilityReportPersonalDataPurgeScheduler(
			value -> {
				cutoff.set(value);
				return 0;
			},
			Clock.fixed(now, ZoneOffset.UTC),
			8
		);

		scheduler.purgeExpiredPersonalData();

		assertThat(cutoff.get()).isEqualTo(LocalDateTime.of(2026, 7, 15, 12, 0));
	}

	@Test
	@DisplayName("보관 기간은 일일 실행과 7일 안전 여유를 적용할 수 있는 8일 이상이어야 한다")
	void rejectsInvalidRetentionDays() {
		assertThatThrownBy(() -> new FacilityReportPersonalDataPurgeScheduler(
			cutoff -> 0,
			Clock.systemUTC(),
			7
		)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("보관 기간은 공개된 최대 365일을 넘을 수 없다")
	void rejectsRetentionDaysAbovePublishedMaximum() {
		assertThatThrownBy(() -> new FacilityReportPersonalDataPurgeScheduler(
			cutoff -> 0,
			Clock.systemUTC(),
			366
		)).isInstanceOf(IllegalArgumentException.class);
	}
}

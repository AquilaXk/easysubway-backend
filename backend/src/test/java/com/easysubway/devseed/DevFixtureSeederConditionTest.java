package com.easysubway.devseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.easysubway.admin.metric.application.port.out.AdminMetricDailyRepository;
import com.easysubway.report.application.port.out.SaveFacilityReportPort;
import com.easysubway.report.application.port.out.StoreFacilityReportPhotoPort;
import com.easysubway.transit.application.port.out.LoadTransitMasterPort;
import com.easysubway.transit.application.port.out.SaveAccessibilityFacilityStatusPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * dev seed 이중 스위치 회귀 검증(#2327 PR⑤). TimetableSeedLoaderConditionTest와 동일한 형태:
 * (1) {@code @ConditionalOnProperty}: {@code easysubway.dev-seed.enabled} 미설정/false면 빈이 아예
 * 없어야 하고 true일 때만 등록된다. (2) {@code @Profile}: 매칭 프로파일(dev)에서만 활성이고, prod
 * 계열 프로파일에서는 flag=true여도 등록되지 않는다.
 */
class DevFixtureSeederConditionTest {

	// 지정 프로파일을 활성화해 @Profile 게이트를 제어하고 @ConditionalOnProperty(flag)는 각 테스트에서 변수로 둔다.
	private static ApplicationContextRunner runnerWithProfile(String profile) {
		return new ApplicationContextRunner()
			.withInitializer(context -> context.getEnvironment().setActiveProfiles(profile))
			.withBean(LoadTransitMasterPort.class, () -> mock(LoadTransitMasterPort.class))
			.withBean(SaveAccessibilityFacilityStatusPort.class, () -> mock(SaveAccessibilityFacilityStatusPort.class))
			.withBean(SaveFacilityReportPort.class, () -> mock(SaveFacilityReportPort.class))
			.withBean(StoreFacilityReportPhotoPort.class, () -> mock(StoreFacilityReportPhotoPort.class))
			.withBean(AdminMetricDailyRepository.class, () -> mock(AdminMetricDailyRepository.class))
			.withUserConfiguration(DevFixtureSeeder.class);
	}

	// @Profile 게이트를 만족시키는 매칭 프로파일. @ConditionalOnProperty만 변수로 둘 때 사용.
	private final ApplicationContextRunner runner = runnerWithProfile("dev");

	@Test
	void seederAbsentWhenFlagMissing() {
		runner.run(context -> assertThat(context).doesNotHaveBean(DevFixtureSeeder.class));
	}

	@Test
	void seederAbsentWhenFlagFalse() {
		runner.withPropertyValues("easysubway.dev-seed.enabled=false")
			.run(context -> assertThat(context).doesNotHaveBean(DevFixtureSeeder.class));
	}

	@Test
	void seederPresentWhenFlagTrueInDevProfile() {
		runner.withPropertyValues("easysubway.dev-seed.enabled=true")
			.run(context -> assertThat(context).hasSingleBean(DevFixtureSeeder.class));
	}

	@Test
	void seederAbsentWhenProfileIsProdEvenWithFlagTrue() {
		runnerWithProfile("prod")
			.withPropertyValues("easysubway.dev-seed.enabled=true")
			.run(context -> assertThat(context).doesNotHaveBean(DevFixtureSeeder.class));
	}

	@Test
	void seederAbsentWhenProfileIsStagingEvenWithFlagTrue() {
		runnerWithProfile("staging")
			.withPropertyValues("easysubway.dev-seed.enabled=true")
			.run(context -> assertThat(context).doesNotHaveBean(DevFixtureSeeder.class));
	}

	@Test
	void seederAbsentWhenProfileIsReleaseEvenWithFlagTrue() {
		runnerWithProfile("release")
			.withPropertyValues("easysubway.dev-seed.enabled=true")
			.run(context -> assertThat(context).doesNotHaveBean(DevFixtureSeeder.class));
	}

	@Test
	void seederAbsentWhenProfileIsProdLikeEvenWithFlagTrue() {
		runnerWithProfile("prod-like")
			.withPropertyValues("easysubway.dev-seed.enabled=true")
			.run(context -> assertThat(context).doesNotHaveBean(DevFixtureSeeder.class));
	}
}

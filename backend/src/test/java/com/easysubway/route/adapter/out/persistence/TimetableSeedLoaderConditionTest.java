package com.easysubway.route.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 출시게이트 이중 스위치 회귀 검증. (1) @ConditionalOnProperty: flag 미설정/false면 로더 빈이 아예 없어야
 * 하고 flag=true일 때만 등록된다. (2) @Profile: 매칭 프로파일(prod-like)에서만 활성이고, 미매칭
 * 프로파일(dev)에서는 flag=true여도 등록되지 않는다.
 */
class TimetableSeedLoaderConditionTest {

	// 지정 프로파일을 활성화해 @Profile 게이트를 제어하고 @ConditionalOnProperty(flag)는 각 테스트에서 변수로 둔다.
	private static ApplicationContextRunner runnerWithProfile(String profile) {
		return new ApplicationContextRunner()
			.withInitializer(context -> context.getEnvironment().setActiveProfiles(profile))
			.withBean(LoadRouteTimetablePort.class, () -> LoadRouteTimetablePort.RouteTimetable::empty)
			.withBean(javax.sql.DataSource.class,
				() -> new DriverManagerDataSource("jdbc:h2:mem:seed-cond;DB_CLOSE_DELAY=-1", "sa", ""))
			.withBean(PlatformTransactionManager.class,
				() -> new DataSourceTransactionManager(
					new DriverManagerDataSource("jdbc:h2:mem:seed-cond;DB_CLOSE_DELAY=-1", "sa", "")))
			.withUserConfiguration(TimetableSeedLoader.class);
	}

	// @Profile 게이트를 만족시키는 매칭 프로파일. @ConditionalOnProperty만 변수로 둘 때 사용.
	private final ApplicationContextRunner runner = runnerWithProfile("prod-like");

	@Test
	void loaderAbsentWhenFlagMissing() {
		runner.run(context -> assertThat(context).doesNotHaveBean(TimetableSeedLoader.class));
	}

	@Test
	void loaderAbsentWhenFlagFalse() {
		runner.withPropertyValues("easysubway.timetable.seed.enabled=false")
			.run(context -> assertThat(context).doesNotHaveBean(TimetableSeedLoader.class));
	}

	@Test
	void loaderPresentWhenFlagTrue() {
		runner.withPropertyValues("easysubway.timetable.seed.enabled=true")
			.run(context -> assertThat(context).hasSingleBean(TimetableSeedLoader.class));
	}

	@Test
	void loaderAbsentWhenProfileDoesNotMatchEvenWithFlagTrue() {
		// @Profile("prod | staging | release | prod-like")에 없는 dev 프로파일에서는 flag=true여도 빈이 없어야 한다.
		runnerWithProfile("dev")
			.withPropertyValues("easysubway.timetable.seed.enabled=true")
			.run(context -> assertThat(context).doesNotHaveBean(TimetableSeedLoader.class));
	}
}

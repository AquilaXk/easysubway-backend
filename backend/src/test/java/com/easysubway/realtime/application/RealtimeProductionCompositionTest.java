package com.easysubway.realtime.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.easysubway.realtime.adapter.out.persistence.DevelopmentRealtimeSafetyPorts;
import com.easysubway.realtime.adapter.out.persistence.InMemoryRealtimeMappingPort;
import com.easysubway.realtime.adapter.out.persistence.JdbcRealtimeArrivalArchiveRepository;
import com.easysubway.realtime.adapter.out.persistence.JdbcRealtimeMappingRepository;
import com.easysubway.realtime.adapter.out.persistence.JdbcRealtimeProviderCallQuotaRepository;
import com.easysubway.realtime.application.port.out.RealtimeArrivalArchivePort;
import com.easysubway.realtime.application.port.out.RealtimeMappingPort;
import com.easysubway.realtime.application.port.out.RealtimeProviderCallQuotaPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.Executor;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("실시간 운영 조립 계약")
class RealtimeProductionCompositionTest {

	@Test
	@DisplayName("운영 계열 프로필은 TOPIS와 JDBC 포트 및 archive executor를 정확히 하나씩 조립한다")
	void productionProfilesComposeExactlyOneRealtimePath() {
		for (String profile : new String[] {"prod", "staging", "release", "prod-like"}) {
			realtimeRunner(profile, ProductionDependencies.class).run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBeanNamesForType(RealtimeGatewayService.class))
					.containsExactly("realtimeGatewayService");
				assertThat(context.getBeanNamesForType(RealtimeProvider.class)).containsExactly("topisRealtimeProvider");
				assertThat(context.getBean(RealtimeProvider.class)).isInstanceOf(TopisRealtimeProvider.class);
				assertThat(context.getBeanNamesForType(RealtimeMappingPort.class))
					.containsExactly("jdbcRealtimeMappingRepository");
				assertThat(context.getBean(RealtimeMappingPort.class)).isInstanceOf(JdbcRealtimeMappingRepository.class);
				assertThat(context.getBeanNamesForType(RealtimeArrivalArchivePort.class))
					.containsExactly("jdbcRealtimeArrivalArchiveRepository");
				assertThat(context.getBean(RealtimeArrivalArchivePort.class))
					.isInstanceOf(JdbcRealtimeArrivalArchiveRepository.class);
				assertThat(context.getBeanNamesForType(RealtimeProviderCallQuotaPort.class))
					.containsExactly("jdbcRealtimeProviderCallQuotaRepository");
				assertThat(context.getBean(RealtimeProviderCallQuotaPort.class))
					.isInstanceOf(JdbcRealtimeProviderCallQuotaRepository.class);
				Executor archiveExecutor = context.getBean("realtimeArchiveExecutor", Executor.class);
				assertThat(archiveExecutor).isInstanceOf(ThreadPoolTaskExecutor.class);
				assertThat(ReflectionTestUtils.getField(
					context.getBean(RealtimeGatewayService.class),
					"archiveExecutor"
				)).isSameAs(archiveExecutor);
				assertThat(context.getBean(RealtimeArrivalArchivePort.class))
					.isNotSameAs(RealtimeArrivalArchivePort.NO_OP);
				assertThat(context).doesNotHaveBean(DevelopmentRealtimeSafetyPorts.class);
				assertThat(context).doesNotHaveBean(InMemoryRealtimeMappingPort.class);
			});
		}
	}

	@Test
	@DisplayName("default·dev·test 프로필은 JDBC 대신 development archive·quota 포트만 조립한다")
	void developmentProfilesUseOnlyDevelopmentSafetyPortsForArchiveAndQuota() {
		for (String profile : new String[] {"default", "dev", "test"}) {
			realtimeRunner(profile, DevelopmentDependencies.class).run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(RealtimeGatewayService.class);
				assertThat(context.getBeanNamesForType(RealtimeMappingPort.class))
					.containsExactly("inMemoryRealtimeMappingPort");
				assertThat(context.getBean(RealtimeMappingPort.class))
					.isInstanceOf(InMemoryRealtimeMappingPort.class);
				assertThat(context.getBeanNamesForType(RealtimeArrivalArchivePort.class))
					.containsExactly("developmentRealtimeSafetyPorts");
				assertThat(context.getBeanNamesForType(RealtimeProviderCallQuotaPort.class))
					.containsExactly("developmentRealtimeSafetyPorts");
				assertThat(context).hasSingleBean(DevelopmentRealtimeSafetyPorts.class);
				assertThat(context).doesNotHaveBean(JdbcRealtimeMappingRepository.class);
				assertThat(context).doesNotHaveBean(JdbcRealtimeArrivalArchiveRepository.class);
				assertThat(context).doesNotHaveBean(JdbcRealtimeProviderCallQuotaRepository.class);
			});
		}
	}

	@Test
	@DisplayName("운영 실시간 의존성이 하나라도 없으면 gateway context가 기동하지 않는다")
	void missingProductionDependencyFailsContext() {
		for (Class<?> dependency : new Class<?>[] {
			TopisRealtimeProvider.class,
			RealtimeProviderControl.class,
			JdbcRealtimeMappingRepository.class,
			JdbcRealtimeArrivalArchiveRepository.class,
			JdbcRealtimeProviderCallQuotaRepository.class,
			RealtimeInfrastructureConfiguration.class
		}) {
			realtimeRunner("prod", ProductionDependencies.class)
				.withClassLoader(new FilteredClassLoader(dependency))
				.run(context ->
				assertThat(context).hasFailed()
			);
		}
	}

	@Test
	@DisplayName("운영 실시간 provider·port·executor가 중복되면 gateway context가 기동하지 않는다")
	void duplicateProductionDependencyFailsContext() {
		for (Class<?> duplicate : new Class<?>[] {
			DuplicateProviderConfiguration.class,
			DuplicateMappingConfiguration.class,
			DuplicateArchiveConfiguration.class,
			DuplicateQuotaConfiguration.class,
			DuplicateArchiveExecutorConfiguration.class
		}) {
			realtimeRunner("prod", ProductionDependencies.class, duplicate).run(context ->
				assertThat(context).hasFailed()
			);
		}
	}

	private ApplicationContextRunner realtimeRunner(
		String profile,
		Class<?> dependencies,
		Class<?>... additionalConfigurations
	) {
		return new ApplicationContextRunner()
			.withUserConfiguration(RealtimeComponentScan.class, dependencies)
			.withUserConfiguration(additionalConfigurations)
			.withPropertyValues("spring.profiles.active=" + profile);
	}

	@TestConfiguration(proxyBeanMethods = false)
	@ComponentScan(
		basePackages = {
			"com.easysubway.realtime.application",
			"com.easysubway.realtime.adapter.out.persistence"
		},
		excludeFilters = @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = TestComponent.class)
	)
	static class RealtimeComponentScan {
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ProductionDependencies {

		@Bean
		DataSource dataSource() {
			return new DriverManagerDataSource(
				"jdbc:h2:mem:realtime-production-composition;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
				"sa",
				""
			);
		}

		@Bean
		ObjectMapper objectMapper() {
			return new ObjectMapper();
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class DevelopmentDependencies {

		@Bean
		ObjectMapper objectMapper() {
			return new ObjectMapper();
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class DuplicateProviderConfiguration {

		@Bean
		RealtimeProvider alternateRealtimeProvider() {
			return mock(RealtimeProvider.class);
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class DuplicateMappingConfiguration {

		@Bean
		RealtimeMappingPort alternateRealtimeMappingPort() {
			return mock(RealtimeMappingPort.class);
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class DuplicateArchiveConfiguration {

		@Bean
		RealtimeArrivalArchivePort alternateRealtimeArrivalArchivePort() {
			return mock(RealtimeArrivalArchivePort.class);
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class DuplicateQuotaConfiguration {

		@Bean
		RealtimeProviderCallQuotaPort alternateRealtimeProviderCallQuotaPort() {
			return mock(RealtimeProviderCallQuotaPort.class);
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class DuplicateArchiveExecutorConfiguration {

		@Bean("realtimeArchiveExecutor")
		Executor alternateRealtimeArchiveExecutor() {
			return Runnable::run;
		}
	}
}

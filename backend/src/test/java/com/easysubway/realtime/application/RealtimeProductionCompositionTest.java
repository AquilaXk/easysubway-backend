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
import java.util.Arrays;
import java.util.concurrent.Executor;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@DisplayName("실시간 운영 조립 계약")
class RealtimeProductionCompositionTest {

	private static final Class<?>[] PRODUCTION_COMPONENTS = {
		ProductionDependencies.class,
		RealtimeGatewayService.class,
		RealtimeProviderControl.class,
		TopisRealtimeProvider.class,
		JdbcRealtimeMappingRepository.class,
		JdbcRealtimeArrivalArchiveRepository.class,
		JdbcRealtimeProviderCallQuotaRepository.class,
		RealtimeInfrastructureConfiguration.class
	};

	private static final Class<?>[] DEVELOPMENT_COMPONENTS = {
		DevelopmentDependencies.class,
		RealtimeGatewayService.class,
		RealtimeProviderControl.class,
		TopisRealtimeProvider.class,
		InMemoryRealtimeMappingPort.class,
		DevelopmentRealtimeSafetyPorts.class,
		RealtimeInfrastructureConfiguration.class
	};

	@Test
	@DisplayName("운영 계열 프로필은 TOPIS와 JDBC 포트 및 archive executor를 정확히 하나씩 조립한다")
	void productionProfilesComposeExactlyOneRealtimePath() {
		for (String profile : new String[] {"prod", "staging", "release", "prod-like"}) {
			productionRunner(profile, PRODUCTION_COMPONENTS).run(context -> {
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
				assertThat(context.getBeanNamesForType(Executor.class)).containsExactly("realtimeArchiveExecutor");
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
			developmentRunner(profile).run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(RealtimeGatewayService.class);
				assertThat(context.getBeanNamesForType(RealtimeMappingPort.class))
					.containsExactly("inMemoryRealtimeMappingPort");
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
			JdbcRealtimeMappingRepository.class,
			JdbcRealtimeArrivalArchiveRepository.class,
			JdbcRealtimeProviderCallQuotaRepository.class,
			RealtimeInfrastructureConfiguration.class
		}) {
			productionRunner("prod", withoutProductionComponent(dependency)).run(context ->
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
			productionRunner("prod", withProductionComponent(duplicate)).run(context ->
				assertThat(context).hasFailed()
			);
		}
	}

	private ApplicationContextRunner productionRunner(String profile, Class<?>... components) {
		return new ApplicationContextRunner()
			.withUserConfiguration(components)
			.withPropertyValues("spring.profiles.active=" + profile);
	}

	private ApplicationContextRunner developmentRunner(String profile) {
		return new ApplicationContextRunner()
			.withUserConfiguration(DEVELOPMENT_COMPONENTS)
			.withPropertyValues("spring.profiles.active=" + profile);
	}

	private Class<?>[] withoutProductionComponent(Class<?> component) {
		return Arrays.stream(PRODUCTION_COMPONENTS)
			.filter(candidate -> candidate != component)
			.toArray(Class<?>[]::new);
	}

	private Class<?>[] withProductionComponent(Class<?> component) {
		Class<?>[] components = Arrays.copyOf(PRODUCTION_COMPONENTS, PRODUCTION_COMPONENTS.length + 1);
		components[components.length - 1] = component;
		return components;
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

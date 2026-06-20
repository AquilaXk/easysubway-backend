package com.easysubway.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import com.easysubway.transit.application.port.out.LoadTransitMasterPort;
import com.easysubway.transit.domain.AccessibilityFacility;
import com.easysubway.transit.domain.DataQualityLevel;
import com.easysubway.transit.domain.DataSourceType;
import com.easysubway.transit.domain.RouteEdge;
import com.easysubway.transit.domain.RouteNode;
import com.easysubway.transit.domain.SimplifiedStationLayout;
import com.easysubway.transit.domain.Station;
import com.easysubway.transit.domain.StationExit;
import com.easysubway.transit.domain.StationLayoutSource;
import com.easysubway.transit.domain.StationLine;
import com.easysubway.transit.domain.SubwayLine;
import com.easysubway.transit.domain.TransitOperator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@DisplayName("운영 영속 저장소 준비 상태")
class ProductionPersistenceReadinessConfigurationTest {

	@Test
	@DisplayName("운영 프로필은 기동을 막지 않고 readiness 상태를 노출한다")
	void prodProfileStartsAndPublishesReadinessHealthIndicator() {
		productionContext(ReadyDependencyTestConfiguration.class)
			.withPropertyValues(
				"spring.profiles.active=prod"
			)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(HealthIndicator.class);

				Health health = context.getBean(HealthIndicator.class).health();

				assertThat(health.getStatus()).isEqualTo(Status.UP);
				assertThat(health.getDetails()).containsEntry("database", "ready");
				assertThat(health.getDetails()).containsEntry("masterData", "ready");
				assertThat(health.getDetails()).doesNotContainKey("redis");
				assertThat(health.getDetails()).doesNotContainKey("push");
			});
	}

	@Test
	@DisplayName("운영 프로필은 준비되지 않은 의존성을 readiness DOWN으로 보고한다")
	void prodReadinessReportsDownWhenDependenciesAreNotReady() {
		productionContext(UnreadyDependencyTestConfiguration.class)
			.withPropertyValues("spring.profiles.active=prod")
			.run(context -> {
				assertThat(context).hasNotFailed();

				Health health = context.getBean(HealthIndicator.class).health();

				assertThat(health.getStatus()).isEqualTo(Status.DOWN);
				assertThat(health.getDetails()).containsEntry("database", "down");
				assertThat(health.getDetails()).containsEntry("masterData", "empty");
				assertThat(health.getDetails()).doesNotContainKey("redis");
				assertThat(health.getDetails()).doesNotContainKey("push");
			});
	}

	@Test
	@DisplayName("개발 프로필은 운영 readiness indicator를 만들지 않는다")
	void devProfileDoesNotCreateProductionReadinessIndicator() {
		productionContext(ReadyDependencyTestConfiguration.class)
			.withPropertyValues("spring.profiles.active=dev")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).doesNotHaveBean(HealthIndicator.class);
			});
	}

	private ApplicationContextRunner productionContext(Class<?> testConfiguration) {
		return new ApplicationContextRunner()
			.withUserConfiguration(ProductionPersistenceReadinessConfiguration.class, testConfiguration);
	}

	@TestConfiguration
	static class ReadyDependencyTestConfiguration {

		@Bean
		DataSource dataSource() {
			return new DriverManagerDataSource(
				"jdbc:h2:mem:production-readiness;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
				"sa",
				""
			);
		}

		@Bean
		LoadTransitMasterPort loadTransitMasterPort() {
			return new StaticTransitMasterPort(
				List.of(new TransitOperator(
					"seoul-metro",
					"서울교통공사",
					"수도권",
					"https://www.seoulmetro.co.kr",
					"https://www.seoulmetro.co.kr",
					DataSourceType.OFFICIAL_API,
					true
				)),
				List.of(new SubwayLine("line-1", "seoul-metro", "1호선", "#0052A4", "수도권", "1", true)),
				List.of(new Station(
					"station-1",
					"서울역",
					"Seoul Station",
					"수도권",
					BigDecimal.valueOf(37.5547),
					BigDecimal.valueOf(126.9706),
					DataQualityLevel.LEVEL_1,
					DataSourceType.OFFICIAL_API,
					LocalDate.of(2026, 1, 1),
					true
				))
			);
		}
	}

	@TestConfiguration
	static class UnreadyDependencyTestConfiguration {

		@Bean
		DataSource dataSource() {
			return new DriverManagerDataSource(
				"jdbc:h2:tcp://127.0.0.1:1/missing-production-readiness",
				"sa",
				""
			);
		}

		@Bean
		LoadTransitMasterPort loadTransitMasterPort() {
			return new StaticTransitMasterPort(List.of(), List.of(), List.of());
		}
	}

	private record StaticTransitMasterPort(
		List<TransitOperator> operators,
		List<SubwayLine> lines,
		List<Station> stations
	) implements LoadTransitMasterPort {

		@Override
		public List<TransitOperator> loadOperators() {
			return operators;
		}

		@Override
		public List<SubwayLine> loadLines() {
			return lines;
		}

		@Override
		public List<Station> loadStations() {
			return stations;
		}

		@Override
		public List<StationLine> loadStationLines() {
			return List.of();
		}

		@Override
		public List<StationExit> loadStationExits() {
			return List.of();
		}

		@Override
		public List<AccessibilityFacility> loadAccessibilityFacilities() {
			return List.of();
		}

		@Override
		public List<StationLayoutSource> loadStationLayoutSources() {
			return List.of();
		}

		@Override
		public List<SimplifiedStationLayout> loadSimplifiedStationLayouts() {
			return List.of();
		}

		@Override
		public List<RouteNode> loadRouteNodes() {
			return List.of();
		}

		@Override
		public List<RouteEdge> loadRouteEdges() {
			return List.of();
		}
	}
}

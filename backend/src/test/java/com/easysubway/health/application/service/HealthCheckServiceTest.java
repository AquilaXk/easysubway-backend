package com.easysubway.health.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.easysubway.health.domain.HealthStatus;
import com.easysubway.transit.adapter.out.persistence.InMemoryTransitMasterRepository;
import com.easysubway.transit.adapter.out.persistence.UnavailableTransitMasterRepository;
import com.easysubway.transit.application.port.out.LoadTransitMasterPort;
import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("헬스체크 서비스")
class HealthCheckServiceTest {

	@Test
	@DisplayName("백엔드 상태와 서비스 이름, 기본 컴포넌트를 반환한다")
	void checkHealthReturnsBackendStatus() throws Exception {
		HealthStatus status = new HealthCheckService(availableDataSource(), new InMemoryTransitMasterRepository())
			.checkHealth();

		assertThat(status.status()).isEqualTo("UP");
		assertThat(status.service()).isEqualTo("easysubway-backend");
		assertThat(status.components())
			.extracting("name")
			.containsExactlyInAnyOrder(
				"application",
				"database",
				"masterData",
				"flyway",
				"objectStorage",
				"batch",
				"pushOutbox",
				"backup"
			);
	}

	@Test
	@DisplayName("읽기 전용 마스터 데이터는 top-level UP과 component READ_ONLY로 반영된다")
	void checkHealthReportsReadOnlyMasterData() throws Exception {
		HealthStatus status = new HealthCheckService(availableDataSource(), new UnavailableTransitMasterRepository())
			.checkHealth();

		assertThat(status.status()).isEqualTo("UP");
		assertThat(status.components())
			.filteredOn(component -> component.name().equals("masterData"))
			.singleElement()
			.satisfies(component -> {
				assertThat(component.status()).isEqualTo("READ_ONLY");
				assertThat(component.reason()).isEqualTo("마스터 데이터가 읽기 전용입니다.");
			});
	}

	@Test
	@DisplayName("필수 의존성이 없으면 top-level DOWN과 component DOWN으로 반영된다")
	void checkHealthReportsMissingRequiredDependenciesAsDown() {
		HealthStatus status = new HealthCheckService((DataSource) null, (LoadTransitMasterPort) null).checkHealth();

		assertThat(status.status()).isEqualTo("DOWN");
		assertThat(status.components())
			.filteredOn(component -> component.name().equals("database") || component.name().equals("masterData"))
			.extracting("status")
			.containsOnly("DOWN");
	}

	private static DataSource availableDataSource() throws Exception {
		DataSource dataSource = mock(DataSource.class);
		Connection connection = mock(Connection.class);
		when(dataSource.getConnection()).thenReturn(connection);
		when(connection.isValid(2)).thenReturn(true);
		return dataSource;
	}
}

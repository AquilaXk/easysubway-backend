package com.easysubway.health.application.service;

import com.easysubway.health.application.port.in.CheckHealthUseCase;
import com.easysubway.health.domain.HealthComponent;
import com.easysubway.health.domain.HealthStatus;
import com.easysubway.transit.application.port.out.LoadTransitMasterPort;
import com.easysubway.transit.application.port.out.MasterDataCapability;
import com.easysubway.transit.application.port.out.MasterDataCapabilityPort;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class HealthCheckService implements CheckHealthUseCase {

	private static final String SERVICE_NAME = "easysubway-backend";

	private final DataSource dataSource;
	private final LoadTransitMasterPort loadTransitMasterPort;

	@Autowired
	public HealthCheckService(
		ObjectProvider<DataSource> dataSourceProvider,
		ObjectProvider<LoadTransitMasterPort> loadTransitMasterPortProvider
	) {
		this(dataSourceProvider.getIfAvailable(), loadTransitMasterPortProvider.getIfAvailable());
	}

	public HealthCheckService(DataSource dataSource, LoadTransitMasterPort loadTransitMasterPort) {
		this.dataSource = dataSource;
		this.loadTransitMasterPort = loadTransitMasterPort;
	}

	@Override
	public HealthStatus checkHealth() {
		List<HealthComponent> components = new ArrayList<>();
		components.add(new HealthComponent(
			"application",
			"UP",
			"애플리케이션",
			"서비스 프로세스가 요청을 처리할 수 있습니다."
		));
		components.add(databaseHealth());
		components.add(masterDataHealth());
		components.add(unknown("flyway", "Flyway", "마이그레이션 상태는 actuator DB/readiness와 배포 로그에서 확인합니다."));
		components.add(unknown("objectStorage", "객체 저장소", "신고 사진 저장소 실시간 점검은 아직 구성되지 않았습니다."));
		components.add(unknown("batch", "배치", "최근 실행 이력은 관리자 수집 화면에서 확인합니다."));
		components.add(unknown("pushOutbox", "푸시 outbox", "대기/실패 수는 관리자 시스템 화면의 푸시 지표에서 확인합니다."));
		components.add(unknown("backup", "백업", "백업 리허설 상태는 배포/운영 run 증거에서 확인합니다."));
		return HealthStatus.of(summaryStatus(components), SERVICE_NAME, components);
	}

	private HealthComponent databaseHealth() {
		if (dataSource == null) {
			return new HealthComponent("database", "DOWN", "데이터베이스", "DataSource가 구성되지 않았습니다.");
		}
		try (Connection connection = dataSource.getConnection()) {
			boolean valid = connection.isValid(2);
			return new HealthComponent(
				"database",
				valid ? "UP" : "DOWN",
				"데이터베이스",
				valid ? "DB 연결이 유효합니다." : "DB 연결 validation에 실패했습니다."
			);
		} catch (Exception exception) {
			return new HealthComponent("database", "DOWN", "데이터베이스", "DB 연결을 확인할 수 없습니다.");
		}
	}

	private HealthComponent masterDataHealth() {
		if (loadTransitMasterPort == null) {
			return new HealthComponent("masterData", "DOWN", "마스터 데이터", "마스터 데이터 port가 구성되지 않았습니다.");
		}
		try {
			boolean hasMasterData = !loadTransitMasterPort.loadOperators().isEmpty()
				&& !loadTransitMasterPort.loadLines().isEmpty()
				&& !loadTransitMasterPort.loadStations().isEmpty();
			if (!hasMasterData) {
				return new HealthComponent("masterData", "DOWN", "마스터 데이터", "운영 마스터 데이터가 비어 있습니다.");
			}
			if (loadTransitMasterPort instanceof MasterDataCapabilityPort capabilityPort) {
				MasterDataCapability capability = capabilityPort.masterDataCapability();
				if (!capability.readable()) {
					return new HealthComponent("masterData", "DOWN", "마스터 데이터", "마스터 데이터를 읽을 수 없습니다.");
				}
				if (!capability.writable()) {
					return new HealthComponent("masterData", "READ_ONLY", "마스터 데이터", "마스터 데이터가 읽기 전용입니다.");
				}
			}
			return new HealthComponent("masterData", "UP", "마스터 데이터", "마스터 데이터를 읽고 쓸 수 있습니다.");
		} catch (Exception exception) {
			return new HealthComponent("masterData", "DOWN", "마스터 데이터", "마스터 데이터를 확인할 수 없습니다.");
		}
	}

	private static HealthComponent unknown(String name, String label, String reason) {
		return new HealthComponent(name, "UNKNOWN", label, reason);
	}

	private static String summaryStatus(List<HealthComponent> components) {
		if (hasStatus(components, "DOWN")) {
			return "DOWN";
		}
		if (hasStatus(components, "DEGRADED")) {
			return "DEGRADED";
		}
		if (hasStatus(components, "STALE")) {
			return "STALE";
		}
		return "UP";
	}

	private static boolean hasStatus(List<HealthComponent> components, String status) {
		return components.stream().anyMatch(component -> component.status().equals(status));
	}
}

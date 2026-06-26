package com.easysubway.common.persistence;

import com.easysubway.transit.application.port.out.LoadTransitMasterPort;
import com.easysubway.transit.application.port.out.MasterDataCapabilityPort;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("prod")
class ProductionPersistenceReadinessConfiguration {

	private static final String READY = "ready";
	private static final String DOWN = "down";
	private static final String EMPTY = "empty";
	private static final String READ_ONLY = "read_only";

	@Bean
	HealthIndicator productionReadinessHealthIndicator(
		DataSource dataSource,
		LoadTransitMasterPort loadTransitMasterPort
	) {
		return () -> {
			Map<String, Object> details = new LinkedHashMap<>();
			boolean databaseReady = databaseReady(dataSource);
			boolean masterDataReady = masterDataReady(loadTransitMasterPort);
			boolean masterDataWritable = masterDataWritable(loadTransitMasterPort);

			details.put("database", databaseReady ? READY : DOWN);
			details.put("masterData", masterDataReady ? (masterDataWritable ? READY : READ_ONLY) : EMPTY);

			boolean ready = databaseReady && masterDataReady;
			return health(ready).withDetails(details).build();
		};
	}

	private Health.Builder health(boolean ready) {
		return ready ? Health.up() : Health.status(Status.DOWN);
	}

	private boolean databaseReady(DataSource dataSource) {
		try (var connection = dataSource.getConnection()) {
			return connection.isValid(2);
		} catch (SQLException exception) {
			return false;
		}
	}

	private boolean masterDataReady(LoadTransitMasterPort loadTransitMasterPort) {
		try {
			return !loadTransitMasterPort.loadOperators().isEmpty()
				&& !loadTransitMasterPort.loadLines().isEmpty()
				&& !loadTransitMasterPort.loadStations().isEmpty();
		} catch (RuntimeException exception) {
			return false;
		}
	}

	private boolean masterDataWritable(LoadTransitMasterPort loadTransitMasterPort) {
		if (loadTransitMasterPort instanceof MasterDataCapabilityPort capabilityPort) {
			return capabilityPort.masterDataCapability().writable();
		}
		return true;
	}
}

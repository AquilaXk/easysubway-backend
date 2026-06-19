package com.easysubway.common.persistence;

import com.easysubway.transit.application.port.out.LoadTransitMasterPort;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

@Configuration(proxyBeanMethods = false)
@Profile("prod")
class ProductionPersistenceReadinessConfiguration {

	private static final String READY = "ready";
	private static final String DOWN = "down";
	private static final String EMPTY = "empty";
	private static final String UNCONFIGURED = "unconfigured";

	@Bean
	HealthIndicator productionReadinessHealthIndicator(
		DataSource dataSource,
		RedisConnectionFactory redisConnectionFactory,
		LoadTransitMasterPort loadTransitMasterPort,
		@Value("${easysubway.notifications.push.external-enabled:false}") boolean pushExternalEnabled
	) {
		return () -> {
			Map<String, Object> details = new LinkedHashMap<>();
			boolean databaseReady = databaseReady(dataSource);
			boolean redisReady = redisReady(redisConnectionFactory);
			boolean masterDataReady = masterDataReady(loadTransitMasterPort);
			boolean pushReady = pushExternalEnabled;

			details.put("database", databaseReady ? READY : DOWN);
			details.put("redis", redisReady ? READY : DOWN);
			details.put("masterData", masterDataReady ? READY : EMPTY);
			details.put("push", pushReady ? READY : UNCONFIGURED);

			boolean ready = databaseReady && redisReady && masterDataReady && pushReady;
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

	private boolean redisReady(RedisConnectionFactory redisConnectionFactory) {
		RedisConnection connection = null;
		try {
			connection = redisConnectionFactory.getConnection();
			return "PONG".equalsIgnoreCase(connection.ping());
		} catch (RuntimeException exception) {
			return false;
		} finally {
			if (connection != null) {
				connection.close();
			}
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
}

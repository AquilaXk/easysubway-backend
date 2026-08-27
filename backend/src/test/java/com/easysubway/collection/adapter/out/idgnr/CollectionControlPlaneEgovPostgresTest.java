package com.easysubway.collection.adapter.out.idgnr;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.collection.application.port.out.GenerateCollectionRunIdPort;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@DisplayName("PostgreSQL 수집 run ID control-plane 발번")
class CollectionControlPlaneEgovPostgresTest {

	@Container
	private static final PostgreSQLContainer<?> POSTGRES =
		new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

	@Test
	@DisplayName("fdl-idgnr는 운영 migration의 ids 행에서 연속 run ID를 발급한다")
	void issuesIncrementingIdsFromMigratedPostgresqlTable() {
		var dataSource = new DriverManagerDataSource(
			POSTGRES.getJdbcUrl(),
			POSTGRES.getUsername(),
			POSTGRES.getPassword()
		);
		Flyway.configure()
			.configuration(java.util.Map.of("flyway.postgresql.transactional.lock", "false"))
			.dataSource(dataSource)
			.locations("classpath:db/migration/postgresql")
			.load()
			.migrate();

		try (var context = new AnnotationConfigApplicationContext()) {
			context.registerBean(DataSource.class, () -> dataSource);
			context.register(CollectionControlPlaneEgovConfig.class);
			context.refresh();
			var generator = context.getBean(GenerateCollectionRunIdPort.class);

			String first = generator.nextCollectionRunId();
			String second = generator.nextCollectionRunId();

			assertThat(first).isEqualTo("collection-1");
			assertThat(second).isEqualTo("collection-2");
		}
	}
}

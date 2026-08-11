package com.easysubway.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@DisplayName("reviewed legacy V1 schema fingerprint")
class LegacySchemaFingerprintTest {

	@Container
	private static final PostgreSQLContainer<?> POSTGRES =
		new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

	@BeforeEach
	void resetPublicSchema() throws SQLException {
		try (Connection connection = connection(); var statement = connection.createStatement()) {
			statement.execute("DROP SCHEMA public CASCADE");
			statement.execute("CREATE SCHEMA public");
		}
	}

	@Test
	@DisplayName("V1-only schema fingerprint는 stable하고 data row와 history를 제외한다")
	void fingerprintIsStableAndIgnoresRowsAndFlywayHistory() throws SQLException {
		migrateV1();
		String withHistory;
		try (Connection connection = connection()) {
			withHistory = LegacySchemaFingerprint.calculate(connection, "public");
			try (var statement = connection.createStatement()) {
				statement.execute("INSERT INTO favorite_stations (user_id, station_id, added_at) VALUES ('user', 'station', CURRENT_TIMESTAMP)");
				statement.execute("DROP TABLE flyway_schema_history");
			}
			assertThat(LegacySchemaFingerprint.calculate(connection, "public")).isEqualTo(withHistory);
		}
		assertThat(withHistory).isEqualTo("077beae456593c54ea6c8b99a2f41f7c263623812aab17ff4ae018ce8c68290c");
	}

	@Test
	@DisplayName("column, default, constraint, index, sequence와 extra object drift를 모두 구별한다")
	void fingerprintDetectsEveryStructuralCategory() throws SQLException {
		migrateV1();
		try (Connection connection = connection(); var statement = connection.createStatement()) {
			statement.execute("DROP TABLE flyway_schema_history");
			String previous = LegacySchemaFingerprint.calculate(connection, "public");
			Map<String, String> mutations = new LinkedHashMap<>();
			mutations.put("column", "ALTER TABLE favorite_stations ADD COLUMN legacy_note TEXT");
			mutations.put("default", "ALTER TABLE favorite_stations ALTER COLUMN legacy_note SET DEFAULT 'legacy'");
			mutations.put("constraint", "ALTER TABLE favorite_stations ADD CONSTRAINT legacy_note_length CHECK (length(legacy_note) < 20)");
			mutations.put("index", "CREATE INDEX legacy_note_idx ON favorite_stations (legacy_note)");
			mutations.put("sequence", "ALTER SEQUENCE batch_job_seq INCREMENT BY 2");
			mutations.put("extra-object", "CREATE VIEW legacy_favorite_station_view AS SELECT user_id FROM favorite_stations");
			for (var mutation : mutations.entrySet()) {
				statement.execute(mutation.getValue());
				String changed = LegacySchemaFingerprint.calculate(connection, "public");
				assertThat(changed).as(mutation.getKey()).isNotEqualTo(previous);
				previous = changed;
			}
		}
	}

	@Test
	@DisplayName("standalone type, routine, trigger와 row policy state를 모두 fingerprint한다")
	void fingerprintCoversStandaloneCatalogObjects() throws SQLException {
		migrateV1();
		try (Connection connection = connection(); var statement = connection.createStatement()) {
			statement.execute("DROP TABLE flyway_schema_history");
			String previous = LegacySchemaFingerprint.calculate(connection, "public");
			Map<String, String> mutations = new LinkedHashMap<>();
			mutations.put("standalone-type", "CREATE TYPE legacy_pair AS (code TEXT, rank INTEGER)");
			mutations.put("routine", "CREATE FUNCTION legacy_touch() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RETURN NEW; END'");
			mutations.put("trigger", "CREATE TRIGGER legacy_touch_trigger BEFORE INSERT ON favorite_stations FOR EACH ROW EXECUTE FUNCTION legacy_touch()");
			mutations.put("trigger-state", "ALTER TABLE favorite_stations DISABLE TRIGGER legacy_touch_trigger");
			mutations.put("row-security", "ALTER TABLE favorite_stations ENABLE ROW LEVEL SECURITY");
			mutations.put("policy", "CREATE POLICY legacy_station_policy ON favorite_stations USING (user_id = CURRENT_USER)");
			for (var mutation : mutations.entrySet()) {
				statement.execute(mutation.getValue());
				String changed = LegacySchemaFingerprint.calculate(connection, "public");
				assertThat(changed).as(mutation.getKey()).isNotEqualTo(previous);
				previous = changed;
			}
			String role = "legacy_policy_role_" + System.nanoTime();
			statement.execute("CREATE ROLE " + role);
			assertThat(LegacySchemaFingerprint.calculate(connection, "public"))
				.as("role outside target schema")
				.isEqualTo(previous);
			statement.execute("ALTER POLICY legacy_station_policy ON favorite_stations TO " + role);
			assertThat(LegacySchemaFingerprint.calculate(connection, "public"))
				.as("policy role")
				.isNotEqualTo(previous);
			statement.execute("DROP POLICY legacy_station_policy ON favorite_stations");
			statement.execute("DROP ROLE " + role);
		}
	}

	@Test
	@DisplayName("target identity는 PostgreSQL major와 raw target tuple 대신 SHA-256만 반환한다")
	void databaseIdentityIsHashed() throws SQLException {
		try (Connection connection = connection()) {
			var identity = LegacySchemaFingerprint.databaseIdentity(connection, "public");
			assertThat(identity.postgresMajor()).isEqualTo(16);
			assertThat(identity.sha256()).matches("[a-f0-9]{64}");
			assertThat(identity.sha256()).doesNotContain(POSTGRES.getDatabaseName(), POSTGRES.getUsername());
		}
	}

	private void migrateV1() {
		Flyway.configure()
			.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
			.schemas("public")
			.defaultSchema("public")
			.createSchemas(false)
			.locations("classpath:db/migration/postgresql")
			.target(MigrationVersion.fromVersion("1"))
			.load()
			.migrate();
	}

	private Connection connection() throws SQLException {
		return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
	}
}

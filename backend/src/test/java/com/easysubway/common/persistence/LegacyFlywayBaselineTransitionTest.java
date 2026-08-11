package com.easysubway.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@DisplayName("reviewed legacy V1 one-shot baseline")
class LegacyFlywayBaselineTransitionTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC);
	private static final String SOURCE_SHA = "d8988d6508aebb0df334e8cbc34adc8adb90712b";
	private static final String ACTOR_SHA = "a".repeat(64);
	private static final String BACKUP_SHA = "b".repeat(64);

	@Container
	private static final PostgreSQLContainer<?> POSTGRES =
		new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

	@BeforeEach
	void resetPublicSchema() throws SQLException {
		resetSchema();
	}

	@Test
	@DisplayName("exact V1 schema만 one-shot baseline하고 data marker와 exact history 한 행을 보존한다")
	void baselinesExactV1SchemaOnly() throws SQLException {
		prepareReviewedLegacySchema();
		var policy = policy();
		var runtime = runtime(policy);
		var input = validInput(targetIdentity());

		var result = execute(input, policy, runtime, () -> {
		});

		assertThat(result.success()).isTrue();
		assertThat(result.reason()).isEqualTo(LegacyFlywayBaselineTransition.Reason.BASELINED);
		try (Connection connection = connection(); var statement = connection.createStatement()) {
			assertThat(statement.executeQuery("SELECT COUNT(*) FROM favorite_stations").next()).isTrue();
			try (var marker = statement.executeQuery("SELECT COUNT(*) FROM favorite_stations WHERE user_id = 'marker-user'")) {
				assertThat(marker.next()).isTrue();
				assertThat(marker.getInt(1)).isEqualTo(1);
			}
			try (var history = statement.executeQuery("""
				SELECT COUNT(*),
				       COUNT(*) FILTER (WHERE type = 'BASELINE' AND version = '1' AND success),
				       COUNT(*) FILTER (WHERE type <> 'BASELINE' OR version <> '1' OR NOT success)
				FROM flyway_schema_history
				""")) {
				assertThat(history.next()).isTrue();
				assertThat(history.getInt(1)).isEqualTo(1);
				assertThat(history.getInt(2)).isEqualTo(1);
				assertThat(history.getInt(3)).isZero();
			}
		}

		String evidence = LegacyFlywayBaselineTransition.renderEvidence(policy, result);
		assertThat(evidence)
			.contains("\"status\":\"SUCCESS\"", "\"reason\":\"BASELINED\"", "\"credentialRedacted\":true")
			.doesNotContain(
				POSTGRES.getJdbcUrl(),
				POSTGRES.getUsername(),
				POSTGRES.getPassword(),
				POSTGRES.getDatabaseName(),
				"favorite_stations",
				"Exception",
				" at "
			);
	}

	@Test
	@DisplayName("검증한 single-use JDBC connection에서 Flyway baseline과 사후 검증을 완료한다")
	void baselinesOnTheValidatedConnectionWithoutOpeningAnotherTarget() throws SQLException {
		prepareReviewedLegacySchema();
		var driver = new SingleUseDriver();
		DriverManager.registerDriver(driver);
		try {
			var credentials = new LegacyFlywayBaselineTransition.DatabaseCredentials(
				driver.url(),
				POSTGRES.getUsername(),
				POSTGRES.getPassword()
			);
			var policy = policy();
			var result = LegacyFlywayBaselineTransition.execute(
				credentials,
				validInput(targetIdentity()),
				policy,
				runtime(policy),
				CLOCK,
				() -> {
				}
			);

			assertThat(result.success()).isTrue();
			assertThat(driver.connectionCount()).isEqualTo(1);
			assertThat(historyRowCount()).isEqualTo(1);
		} finally {
			DriverManager.deregisterDriver(driver);
		}
	}

	@Test
	@DisplayName("empty, partial, unknown과 extra schema는 history/object/data mutation 없이 거부한다")
	void rejectsUnknownSchemaWithoutMutation() throws SQLException {
		var policy = policy();
		var runtime = runtime(policy);

		var empty = execute(validInput(targetIdentity()), policy, runtime, () -> {
		});
		assertThat(empty.reason()).isEqualTo(LegacyFlywayBaselineTransition.Reason.SCHEMA_FINGERPRINT_MISMATCH);
		assertHistoryAbsent();

		try (Connection connection = connection(); var statement = connection.createStatement()) {
			statement.execute("CREATE TABLE unknown_legacy_table (id INTEGER PRIMARY KEY, marker TEXT NOT NULL)");
			statement.execute("INSERT INTO unknown_legacy_table VALUES (1, 'preserve-me')");
		}
		var unknown = execute(validInput(targetIdentity()), policy, runtime, () -> {
		});
		assertThat(unknown.reason()).isEqualTo(LegacyFlywayBaselineTransition.Reason.SCHEMA_FINGERPRINT_MISMATCH);
		assertHistoryAbsent();
		try (Connection connection = connection(); var statement = connection.createStatement();
			var rows = statement.executeQuery("SELECT marker FROM unknown_legacy_table WHERE id = 1")) {
			assertThat(rows.next()).isTrue();
			assertThat(rows.getString(1)).isEqualTo("preserve-me");
		}

		resetSchema();
		prepareReviewedLegacySchema();
		try (Connection connection = connection(); var statement = connection.createStatement()) {
			statement.execute("CREATE VIEW unexpected_view AS SELECT user_id FROM favorite_stations");
		}
		var extra = execute(validInput(targetIdentity()), policy, runtime, () -> {
		});
		assertThat(extra.reason()).isEqualTo(LegacyFlywayBaselineTransition.Reason.SCHEMA_FINGERPRINT_MISMATCH);
		assertHistoryAbsent();
	}

	@Test
	@DisplayName("valid, partial 또는 corrupt history가 있으면 repeat하지 않고 exact refusal한다")
	void refusesEveryHistoryPresentState() throws SQLException {
		migrateV1();
		var policy = policy();
		var runtime = runtime(policy);
		var validHistory = execute(validInput(targetIdentity()), policy, runtime, () -> {
		});
		assertThat(validHistory.reason()).isEqualTo(LegacyFlywayBaselineTransition.Reason.HISTORY_PRESENT);
		assertThat(historyRowCount()).isEqualTo(1);

		resetSchema();
		try (Connection connection = connection(); var statement = connection.createStatement()) {
			statement.execute("CREATE TABLE flyway_schema_history (corrupt_marker INTEGER NOT NULL)");
			statement.execute("INSERT INTO flyway_schema_history VALUES (7)");
		}
		var corruptHistory = execute(validInput(targetIdentity()), policy, runtime, () -> {
		});
		assertThat(corruptHistory.reason()).isEqualTo(LegacyFlywayBaselineTransition.Reason.HISTORY_PRESENT);
		try (Connection connection = connection(); var statement = connection.createStatement();
			var rows = statement.executeQuery("SELECT corrupt_marker FROM flyway_schema_history")) {
			assertThat(rows.next()).isTrue();
			assertThat(rows.getInt(1)).isEqualTo(7);
		}
	}

	@Test
	@DisplayName("operation, source, actor, backup과 approval 입력 drift를 DB 접속 전 거부한다")
	void rejectsInvalidOperationInputsBeforeMutation() throws SQLException {
		prepareReviewedLegacySchema();
		var policy = policy();
		var runtime = runtime(policy);
		var valid = validInput(targetIdentity());
		Map<String, String> invalid = new LinkedHashMap<>();
		invalid.put("environment", "development");
		invalid.put("action", "MIGRATE");
		invalid.put("operationId", "not-an-operation-id");
		invalid.put("backendSourceSha", SOURCE_SHA.toUpperCase());
		invalid.put("actorIdentitySha256", "a".repeat(63));
		invalid.put("backupReceiptSha256", "b".repeat(63));
		invalid.put("targetDatabaseIdentitySha256", "target");
		invalid.put("approval", "UNAPPROVED");
		invalid.put("reason", "UNKNOWN_REASON");

		for (var drift : invalid.entrySet()) {
			var result = execute(mutate(valid, drift.getKey(), drift.getValue()), policy, runtime, () -> {
			});
			assertThat(result.reason()).as(drift.getKey())
				.isEqualTo(LegacyFlywayBaselineTransition.Reason.INVALID_INPUT);
			assertHistoryAbsent();
		}
	}

	@Test
	@DisplayName("target, PostgreSQL major/schema와 runtime policy identity drift는 mutation 없이 거부한다")
	void rejectsTargetAndPolicyIdentityDrift() throws SQLException {
		prepareReviewedLegacySchema();
		var policy = policy();
		var runtime = runtime(policy);
		var input = validInput(targetIdentity());

		var wrongTarget = execute(
			mutate(input, "targetDatabaseIdentitySha256", "0".repeat(64)),
			policy,
			runtime,
			() -> {
			}
		);
		assertThat(wrongTarget.reason()).isEqualTo(LegacyFlywayBaselineTransition.Reason.TARGET_IDENTITY_MISMATCH);

		var wrongRuntime = new LegacyFlywayBaselineTransition.RuntimeIdentity(
			"0".repeat(64),
			runtime.migrationDdlPolicySha256(),
			runtime.productionConfigSha256()
		);
		assertThat(execute(input, policy, wrongRuntime, () -> {
		}).reason()).isEqualTo(LegacyFlywayBaselineTransition.Reason.RESOURCE_DRIFT);

		var wrongMajorPolicy = copyPolicy(policy, 15, policy.targetSchema());
		assertThat(execute(input, wrongMajorPolicy, runtime, () -> {
		}).reason()).isEqualTo(LegacyFlywayBaselineTransition.Reason.POSTGRES_MAJOR_MISMATCH);

		var wrongSchemaPolicy = copyPolicy(policy, policy.postgresMajor(), "other_schema");
		assertThat(execute(input, wrongSchemaPolicy, runtime, () -> {
		}).reason()).isEqualTo(LegacyFlywayBaselineTransition.Reason.TARGET_IDENTITY_MISMATCH);
		assertHistoryAbsent();
	}

	@Test
	@DisplayName("동시 advisory lock과 interrupted pre-mutation은 history를 만들지 않는다")
	void refusesConcurrencyAndInterruptionBeforeMutation() throws SQLException {
		prepareReviewedLegacySchema();
		var policy = policy();
		var runtime = runtime(policy);
		var input = validInput(targetIdentity());

		try (Connection lockConnection = connection();
			var lock = lockConnection.prepareStatement("SELECT pg_advisory_lock(?)")) {
			lock.setLong(1, policy.advisoryLockKey());
			lock.execute();
			var concurrent = execute(input, policy, runtime, () -> {
			});
			assertThat(concurrent.reason()).isEqualTo(LegacyFlywayBaselineTransition.Reason.CONCURRENT_TRANSITION);
		}
		assertHistoryAbsent();

		var interrupted = execute(input, policy, runtime, () -> {
			throw new InterruptedException("test interruption");
		});
		assertThat(interrupted.reason()).isEqualTo(LegacyFlywayBaselineTransition.Reason.INTERRUPTED);
		assertThat(Thread.interrupted()).isTrue();
		assertHistoryAbsent();
	}

	@Test
	@DisplayName("policy/runtime resources는 closed identity로 검증되고 command failure는 한 줄로 redacted된다")
	void commandIsClosedAndRedacted() throws Exception {
		var policy = policy();
		assertThat(runtime(policy).matches(policy)).isTrue();
		assertThat(LegacyFlywayBaselineCommand.class.getAnnotations()).isEmpty();
		assertThat(LegacyFlywayBaselineCommand.class.getDeclaredMethod("main", String[].class)).isNotNull();

		var bytes = new ByteArrayOutputStream();
		int exit = LegacyFlywayBaselineCommand.run(
			new String[]{"unexpected"},
			Map.of(),
			new PrintStream(bytes, true, StandardCharsets.UTF_8),
			getClass().getClassLoader(),
			CLOCK
		);
		String output = bytes.toString(StandardCharsets.UTF_8);
		assertThat(exit).isEqualTo(1);
		assertThat(output.lines()).hasSize(1);
		assertThat(output)
			.contains("\"reason\":\"INVALID_ARGUMENTS\"", "\"credentialRedacted\":true")
			.doesNotContain("Exception", "stack", "jdbc:", "password", " at ");

		prepareReviewedLegacySchema();
		var successBytes = new ByteArrayOutputStream();
		var successOutput = new PrintStream(successBytes, true, StandardCharsets.UTF_8);
		PrintStream originalOut = System.out;
		PrintStream originalError = System.err;
		try {
			System.setOut(successOutput);
			System.setErr(successOutput);
			exit = LegacyFlywayBaselineCommand.run(
				new String[0],
				commandEnvironment(targetIdentity()),
				successOutput,
				getClass().getClassLoader(),
				CLOCK
			);
		} finally {
			System.setOut(originalOut);
			System.setErr(originalError);
		}
		String successEvidence = successBytes.toString(StandardCharsets.UTF_8);
		assertThat(exit).isZero();
		assertThat(successEvidence.lines()).hasSize(1);
		assertThat(successEvidence)
			.contains("\"status\":\"SUCCESS\"", "\"reason\":\"BASELINED\"")
			.doesNotContain(POSTGRES.getJdbcUrl(), POSTGRES.getPassword(), "Flyway", "Exception", " at ");
	}

	@Test
	@DisplayName("unknown policy field와 missing runtime resource는 DB mutation 전에 closed failure다")
	void rejectsPolicyAndResourceShapeDrift() throws Exception {
		ClassLoader parent = getClass().getClassLoader();
		byte[] originalPolicy;
		try (InputStream input = parent.getResourceAsStream(LegacyFlywayBaselineTransition.POLICY_RESOURCE)) {
			assertThat(input).isNotNull();
			originalPolicy = input.readAllBytes();
		}
		byte[] unknownFieldPolicy = new String(originalPolicy, StandardCharsets.UTF_8)
			.replaceFirst("\\{", "{\\\"unexpected\\\":true,")
			.getBytes(StandardCharsets.UTF_8);
		ClassLoader invalidPolicyLoader = new ClassLoader(parent) {
			@Override
			public InputStream getResourceAsStream(String name) {
				if (LegacyFlywayBaselineTransition.POLICY_RESOURCE.equals(name)) {
					return new ByteArrayInputStream(unknownFieldPolicy);
				}
				return super.getResourceAsStream(name);
			}
		};
		assertThatThrownBy(() -> LegacyFlywayBaselineTransition.loadPolicy(invalidPolicyLoader))
			.isInstanceOfSatisfying(LegacyFlywayBaselineTransition.ContractFailure.class,
				failure -> assertThat(failure.reason()).isEqualTo(LegacyFlywayBaselineTransition.Reason.POLICY_INVALID));

		var policy = policy();
		ClassLoader missingInventoryLoader = new ClassLoader(parent) {
			@Override
			public InputStream getResourceAsStream(String name) {
				if ("META-INF/easysubway/flyway-migration-inventory.lock.json".equals(name)) return null;
				return super.getResourceAsStream(name);
			}
		};
		assertThatThrownBy(() -> LegacyFlywayBaselineTransition.verifyRuntime(policy, missingInventoryLoader))
			.isInstanceOfSatisfying(LegacyFlywayBaselineTransition.ContractFailure.class,
				failure -> assertThat(failure.reason()).isEqualTo(LegacyFlywayBaselineTransition.Reason.RESOURCE_DRIFT));
		assertHistoryAbsent();
	}

	@Test
	@DisplayName("inventory에 없는 versioned 또는 repeatable classpath migration을 거부한다")
	void rejectsUninventoriedClasspathMigrations(@TempDir Path directory) throws Exception {
		var policy = policy();
		for (String filename : new String[]{"V70__unexpected.sql", "R__unexpected.sql"}) {
			Path root = directory.resolve(filename.replace('.', '_'));
			Path migration = root.resolve("db/migration/postgresql").resolve(filename);
			Files.createDirectories(migration.getParent());
			Files.writeString(migration, "SELECT 1;\n", StandardCharsets.UTF_8);
			try (var loader = new URLClassLoader(new java.net.URL[]{root.toUri().toURL()}, getClass().getClassLoader())) {
				assertThatThrownBy(() -> LegacyFlywayBaselineTransition.verifyRuntime(policy, loader))
					.as(filename)
					.isInstanceOfSatisfying(LegacyFlywayBaselineTransition.ContractFailure.class,
						failure -> assertThat(failure.reason())
							.isEqualTo(LegacyFlywayBaselineTransition.Reason.RESOURCE_DRIFT));
			}
		}
	}

	private LegacyFlywayBaselineTransition.Result execute(
		LegacyFlywayBaselineTransition.OperationInput input,
		LegacyFlywayBaselineTransition.Policy policy,
		LegacyFlywayBaselineTransition.RuntimeIdentity runtime,
		LegacyFlywayBaselineTransition.BeforeMutation hook
	) {
		return LegacyFlywayBaselineTransition.execute(credentials(), input, policy, runtime, CLOCK, hook);
	}

	private LegacyFlywayBaselineTransition.Policy policy() {
		return LegacyFlywayBaselineTransition.loadPolicy(getClass().getClassLoader());
	}

	private LegacyFlywayBaselineTransition.RuntimeIdentity runtime(LegacyFlywayBaselineTransition.Policy policy) {
		return LegacyFlywayBaselineTransition.verifyRuntime(policy, getClass().getClassLoader());
	}

	private LegacyFlywayBaselineTransition.DatabaseCredentials credentials() {
		return new LegacyFlywayBaselineTransition.DatabaseCredentials(
			POSTGRES.getJdbcUrl(),
			POSTGRES.getUsername(),
			POSTGRES.getPassword()
		);
	}

	private LegacyFlywayBaselineTransition.OperationInput validInput(String targetIdentity) {
		return new LegacyFlywayBaselineTransition.OperationInput(
			"prod",
			"BASELINE_REVIEWED_LEGACY_V1",
			"018f6d8e-7b6c-4f5a-9d8c-7a6b5c4d3e2f",
			SOURCE_SHA,
			ACTOR_SHA,
			BACKUP_SHA,
			targetIdentity,
			"APPROVED_BY_ISSUE_8",
			"REVIEWED_LEGACY_V1_SCHEMA"
		);
	}

	private Map<String, String> commandEnvironment(String targetIdentity) {
		var environment = new LinkedHashMap<String, String>();
		environment.put("EASYSUBWAY_DATASOURCE_URL", POSTGRES.getJdbcUrl());
		environment.put("EASYSUBWAY_DATASOURCE_USERNAME", POSTGRES.getUsername());
		environment.put("EASYSUBWAY_DATASOURCE_PASSWORD", POSTGRES.getPassword());
		environment.put("EASYSUBWAY_BASELINE_ENVIRONMENT", "prod");
		environment.put("EASYSUBWAY_BASELINE_ACTION", "BASELINE_REVIEWED_LEGACY_V1");
		environment.put("EASYSUBWAY_BASELINE_OPERATION_ID", "018f6d8e-7b6c-4f5a-9d8c-7a6b5c4d3e2f");
		environment.put("EASYSUBWAY_BASELINE_BACKEND_SOURCE_SHA", SOURCE_SHA);
		environment.put("EASYSUBWAY_BASELINE_ACTOR_IDENTITY_SHA256", ACTOR_SHA);
		environment.put("EASYSUBWAY_BASELINE_BACKUP_RECEIPT_SHA256", BACKUP_SHA);
		environment.put("EASYSUBWAY_BASELINE_TARGET_DATABASE_IDENTITY_SHA256", targetIdentity);
		environment.put("EASYSUBWAY_BASELINE_APPROVAL", "APPROVED_BY_ISSUE_8");
		environment.put("EASYSUBWAY_BASELINE_REASON", "REVIEWED_LEGACY_V1_SCHEMA");
		return environment;
	}

	private LegacyFlywayBaselineTransition.OperationInput mutate(
		LegacyFlywayBaselineTransition.OperationInput input,
		String field,
		String value
	) {
		return new LegacyFlywayBaselineTransition.OperationInput(
			field.equals("environment") ? value : input.environment(),
			field.equals("action") ? value : input.action(),
			field.equals("operationId") ? value : input.operationId(),
			field.equals("backendSourceSha") ? value : input.backendSourceSha(),
			field.equals("actorIdentitySha256") ? value : input.actorIdentitySha256(),
			field.equals("backupReceiptSha256") ? value : input.backupReceiptSha256(),
			field.equals("targetDatabaseIdentitySha256") ? value : input.targetDatabaseIdentitySha256(),
			field.equals("approval") ? value : input.approval(),
			field.equals("reason") ? value : input.reason()
		);
	}

	private LegacyFlywayBaselineTransition.Policy copyPolicy(
		LegacyFlywayBaselineTransition.Policy policy,
		int postgresMajor,
		String targetSchema
	) {
		return new LegacyFlywayBaselineTransition.Policy(
			policy.contractVersion(),
			policy.evidenceSchema(),
			postgresMajor,
			targetSchema,
			policy.baselineVersion(),
			policy.allowedEnvironments(),
			policy.action(),
			policy.approvalUrl(),
			policy.approval(),
			policy.reason(),
			policy.schemaFingerprintSha256(),
			policy.migrationInventorySha256(),
			policy.migrationDdlPolicySha256(),
			policy.productionConfigSha256(),
			policy.advisoryLockKey(),
			policy.resultReasons()
		);
	}

	private void prepareReviewedLegacySchema() throws SQLException {
		migrateV1();
		try (Connection connection = connection(); var statement = connection.createStatement()) {
			statement.execute("INSERT INTO favorite_stations (user_id, station_id, added_at) VALUES ('marker-user', 'marker-station', CURRENT_TIMESTAMP)");
			statement.execute("DROP TABLE flyway_schema_history");
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

	private String targetIdentity() throws SQLException {
		try (Connection connection = connection()) {
			return LegacySchemaFingerprint.databaseIdentity(connection, "public").sha256();
		}
	}

	private int historyRowCount() throws SQLException {
		try (Connection connection = connection(); var statement = connection.createStatement();
			var result = statement.executeQuery("SELECT COUNT(*) FROM flyway_schema_history")) {
			assertThat(result.next()).isTrue();
			return result.getInt(1);
		}
	}

	private void assertHistoryAbsent() throws SQLException {
		try (Connection connection = connection(); var statement = connection.prepareStatement("""
			SELECT COUNT(*)
			FROM information_schema.tables
			WHERE table_schema = 'public' AND table_name = 'flyway_schema_history'
			"""); var result = statement.executeQuery()) {
			assertThat(result.next()).isTrue();
			assertThat(result.getInt(1)).isZero();
		}
	}

	private void resetSchema() throws SQLException {
		try (Connection connection = connection(); var statement = connection.createStatement()) {
			statement.execute("DROP SCHEMA public CASCADE");
			statement.execute("CREATE SCHEMA public");
		}
	}

	private Connection connection() throws SQLException {
		return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
	}

	private static final class SingleUseDriver implements Driver {

		private static final String URL = "jdbc:easysubway-single-use:legacy-baseline";
		private final AtomicInteger connectionCount = new AtomicInteger();

		String url() {
			return URL;
		}

		int connectionCount() {
			return connectionCount.get();
		}

		@Override
		public Connection connect(String url, Properties info) throws SQLException {
			if (!acceptsURL(url)) return null;
			if (connectionCount.incrementAndGet() != 1) throw new SQLException("second target connection rejected");
			return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		}

		@Override
		public boolean acceptsURL(String url) {
			return URL.equals(url);
		}

		@Override
		public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
			return new DriverPropertyInfo[0];
		}

		@Override
		public int getMajorVersion() {
			return 1;
		}

		@Override
		public int getMinorVersion() {
			return 0;
		}

		@Override
		public boolean jdbcCompliant() {
			return false;
		}

		@Override
		public Logger getParentLogger() {
			return Logger.getLogger("com.easysubway.common.persistence.single-use-driver");
		}
	}
}

package com.easysubway.common.persistence;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.logging.Log;
import org.flywaydb.core.api.logging.LogFactory;

final class LegacyFlywayBaselineTransition {

	static final String CONTRACT_VERSION = "LEGACY_FLYWAY_BASELINE_V1";
	static final String EVIDENCE_SCHEMA = "LEGACY_FLYWAY_BASELINE_EVIDENCE_V1";
	static final String POLICY_RESOURCE = "legacy-flyway-baseline-policy.json";
	private static final String INVENTORY_RESOURCE = "META-INF/easysubway/flyway-migration-inventory.lock.json";
	private static final String DDL_POLICY_RESOURCE = "META-INF/easysubway/migration-ddl-gate.json";
	private static final String PRODUCTION_CONFIG_RESOURCE = "application-prod.yml";
	private static final String MIGRATION_SOURCE_PREFIX = "backend/src/main/resources/";
	private static final String HISTORY_TABLE = "flyway_schema_history";
	private static final Set<String> POLICY_KEYS = Set.of(
		"contractVersion",
		"evidenceSchema",
		"postgresMajor",
		"targetSchema",
		"baselineVersion",
		"allowedEnvironments",
		"action",
		"approvalUrl",
		"approval",
		"reason",
		"schemaFingerprintSha256",
		"migrationInventorySha256",
		"migrationDdlPolicySha256",
		"productionConfigSha256",
		"advisoryLockKey",
		"resultReasons"
	);
	private static final Set<String> INVENTORY_KEYS = Set.of("schemaVersion", "migrations");
	private static final Set<String> MIGRATION_KEYS = Set.of("version", "description", "path", "sha256");
	private static final List<String> REQUIRED_ENVIRONMENT_KEYS = List.of(
		"EASYSUBWAY_DATASOURCE_URL",
		"EASYSUBWAY_DATASOURCE_USERNAME",
		"EASYSUBWAY_DATASOURCE_PASSWORD",
		"EASYSUBWAY_BASELINE_ENVIRONMENT",
		"EASYSUBWAY_BASELINE_ACTION",
		"EASYSUBWAY_BASELINE_OPERATION_ID",
		"EASYSUBWAY_BASELINE_BACKEND_SOURCE_SHA",
		"EASYSUBWAY_BASELINE_ACTOR_IDENTITY_SHA256",
		"EASYSUBWAY_BASELINE_BACKUP_RECEIPT_SHA256",
		"EASYSUBWAY_BASELINE_TARGET_DATABASE_IDENTITY_SHA256",
		"EASYSUBWAY_BASELINE_APPROVAL",
		"EASYSUBWAY_BASELINE_REASON"
	);
	private static final Log SILENT_FLYWAY_LOG = new Log() {
		@Override
		public boolean isDebugEnabled() {
			return false;
		}

		@Override
		public void debug(String message) {
		}

		@Override
		public void info(String message) {
		}

		@Override
		public void warn(String message) {
		}

		@Override
		public void error(String message) {
		}

		@Override
		public void error(String message, Exception exception) {
		}

		@Override
		public void notice(String message) {
		}
	};

	private LegacyFlywayBaselineTransition() {
	}

	static Policy loadPolicy(ClassLoader classLoader) {
		try {
			byte[] bytes = readResource(classLoader, POLICY_RESOURCE);
			JsonNode root = strictMapper().readTree(bytes);
			assertExactKeys(root, POLICY_KEYS, "policy");
			Policy policy = strictMapper().treeToValue(root, Policy.class);
			policy.validate();
			return policy;
		} catch (IOException | RuntimeException exception) {
			throw new ContractFailure(Reason.POLICY_INVALID);
		}
	}

	static RuntimeIdentity verifyRuntime(Policy policy, ClassLoader classLoader) {
		try {
			byte[] inventoryBytes = readResource(classLoader, INVENTORY_RESOURCE);
			byte[] ddlPolicyBytes = readResource(classLoader, DDL_POLICY_RESOURCE);
			byte[] productionConfigBytes = readResource(classLoader, PRODUCTION_CONFIG_RESOURCE);
			verifyMigrationInventory(classLoader, inventoryBytes);
			RuntimeIdentity identity = new RuntimeIdentity(
				sha256(inventoryBytes),
				sha256(ddlPolicyBytes),
				sha256(productionConfigBytes)
			);
			if (!identity.matches(policy)) throw new ContractFailure(Reason.RESOURCE_DRIFT);
			return identity;
		} catch (IOException | RuntimeException exception) {
			if (exception instanceof ContractFailure contractFailure) throw contractFailure;
			throw new ContractFailure(Reason.RESOURCE_DRIFT);
		}
	}

	static CommandInput readEnvironment(Map<String, String> environment) {
		if (environment == null) throw new ContractFailure(Reason.INVALID_INPUT);
		for (String key : REQUIRED_ENVIRONMENT_KEYS) {
			if (environment.get(key) == null || environment.get(key).isBlank()) {
				throw new ContractFailure(Reason.INVALID_INPUT);
			}
		}
		return new CommandInput(
			new DatabaseCredentials(
				environment.get("EASYSUBWAY_DATASOURCE_URL"),
				environment.get("EASYSUBWAY_DATASOURCE_USERNAME"),
				environment.get("EASYSUBWAY_DATASOURCE_PASSWORD")
			),
			new OperationInput(
				environment.get("EASYSUBWAY_BASELINE_ENVIRONMENT"),
				environment.get("EASYSUBWAY_BASELINE_ACTION"),
				environment.get("EASYSUBWAY_BASELINE_OPERATION_ID"),
				environment.get("EASYSUBWAY_BASELINE_BACKEND_SOURCE_SHA"),
				environment.get("EASYSUBWAY_BASELINE_ACTOR_IDENTITY_SHA256"),
				environment.get("EASYSUBWAY_BASELINE_BACKUP_RECEIPT_SHA256"),
				environment.get("EASYSUBWAY_BASELINE_TARGET_DATABASE_IDENTITY_SHA256"),
				environment.get("EASYSUBWAY_BASELINE_APPROVAL"),
				environment.get("EASYSUBWAY_BASELINE_REASON")
			)
		);
	}

	static Result execute(
		DatabaseCredentials credentials,
		OperationInput input,
		Policy policy,
		RuntimeIdentity runtimeIdentity,
		Clock clock,
		BeforeMutation beforeMutation
	) {
		Reason validationFailure = validateInput(credentials, input, policy, runtimeIdentity);
		if (validationFailure != null) return Result.failure(validationFailure, clock.instant());

		try (Connection connection = DriverManager.getConnection(credentials.url(), credentials.username(), credentials.password())) {
			return executeConnected(connection, credentials, input, policy, runtimeIdentity, clock, beforeMutation);
		} catch (SQLException exception) {
			return Result.failure(Reason.DATABASE_CONNECTION_FAILED, clock.instant());
		}
	}

	static String renderEvidence(Policy policy, Result result) {
		Map<String, Object> evidence = new LinkedHashMap<>();
		evidence.put("contractVersion", CONTRACT_VERSION);
		evidence.put("evidenceSchema", EVIDENCE_SCHEMA);
		evidence.put("status", result.success() ? "SUCCESS" : "FAILURE");
		evidence.put("reason", result.reason().name());
		if (result.success()) {
			OperationInput input = result.input();
			RuntimeIdentity runtime = result.runtimeIdentity();
			evidence.put("action", input.action());
			evidence.put("environment", input.environment());
			evidence.put("operationId", input.operationId());
			evidence.put("backendSourceSha", input.backendSourceSha());
			evidence.put("actorIdentitySha256", input.actorIdentitySha256());
			evidence.put("backupReceiptSha256", input.backupReceiptSha256());
			evidence.put("targetDatabaseIdentitySha256", input.targetDatabaseIdentitySha256());
			evidence.put("schemaFingerprintSha256", policy.schemaFingerprintSha256());
			evidence.put("migrationInventorySha256", runtime.migrationInventorySha256());
			evidence.put("migrationDdlPolicySha256", runtime.migrationDdlPolicySha256());
			evidence.put("productionConfigSha256", runtime.productionConfigSha256());
			evidence.put("baselineVersion", policy.baselineVersion());
			evidence.put("beforeState", result.beforeState());
			evidence.put("afterState", result.afterState());
		}
		evidence.put("observedAt", result.observedAt().toString());
		evidence.put("credentialRedacted", true);
		try {
			return strictMapper().writeValueAsString(evidence);
		} catch (IOException exception) {
			return "{\"contractVersion\":\"LEGACY_FLYWAY_BASELINE_V1\",\"evidenceSchema\":\"LEGACY_FLYWAY_BASELINE_EVIDENCE_V1\",\"status\":\"FAILURE\",\"reason\":\"INTERNAL_FAILURE\",\"credentialRedacted\":true}";
		}
	}

	private static Result executeConnected(
		Connection connection,
		DatabaseCredentials credentials,
		OperationInput input,
		Policy policy,
		RuntimeIdentity runtimeIdentity,
		Clock clock,
		BeforeMutation beforeMutation
	) {
		boolean lockAcquired = false;
		try {
			LegacySchemaFingerprint.DatabaseIdentity databaseIdentity;
			try {
				databaseIdentity = LegacySchemaFingerprint.databaseIdentity(connection, policy.targetSchema());
			} catch (SQLException exception) {
				return Result.failure(Reason.TARGET_IDENTITY_MISMATCH, clock.instant());
			}
			if (databaseIdentity.postgresMajor() != policy.postgresMajor()) {
				return Result.failure(Reason.POSTGRES_MAJOR_MISMATCH, clock.instant());
			}
			if (!databaseIdentity.sha256().equals(input.targetDatabaseIdentitySha256())) {
				return Result.failure(Reason.TARGET_IDENTITY_MISMATCH, clock.instant());
			}
			lockAcquired = tryAdvisoryLock(connection, policy.advisoryLockKey());
			if (!lockAcquired) return Result.failure(Reason.CONCURRENT_TRANSITION, clock.instant());
			Reason stateFailure = validateLegacyState(connection, policy, input);
			if (stateFailure != null) return Result.failure(stateFailure, clock.instant());

			try {
				beforeMutation.run();
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				return Result.failure(Reason.INTERRUPTED, clock.instant());
			} catch (Exception exception) {
				return Result.failure(Reason.INTERNAL_FAILURE, clock.instant());
			}

			stateFailure = validateLegacyState(connection, policy, input);
			if (stateFailure != null) return Result.failure(stateFailure, clock.instant());
			Flyway flyway = Flyway.configure()
				.dataSource(credentials.url(), credentials.username(), credentials.password())
				.schemas(policy.targetSchema())
				.defaultSchema(policy.targetSchema())
				.createSchemas(false)
				.locations("classpath:db/migration/postgresql")
				.baselineOnMigrate(false)
				.baselineVersion(MigrationVersion.fromVersion(policy.baselineVersion()))
				.baselineDescription("reviewed legacy V1")
				.load();
			LogFactory.setLogCreator(ignored -> SILENT_FLYWAY_LOG);
			flyway.baseline();
			if (!hasExactBaselineHistory(connection, policy.targetSchema(), policy.baselineVersion())) {
				return Result.failure(Reason.BASELINE_VERIFICATION_FAILED, clock.instant());
			}
			return Result.success(input, runtimeIdentity, clock.instant());
		} catch (SQLException exception) {
			return Result.failure(Reason.INTERNAL_FAILURE, clock.instant());
		} catch (RuntimeException exception) {
			return Result.failure(Reason.BASELINE_VERIFICATION_FAILED, clock.instant());
		} finally {
			if (lockAcquired) releaseAdvisoryLock(connection, policy.advisoryLockKey());
		}
	}

	private static Reason validateInput(
		DatabaseCredentials credentials,
		OperationInput input,
		Policy policy,
		RuntimeIdentity runtimeIdentity
	) {
		if (credentials == null || input == null || policy == null || runtimeIdentity == null
			|| isBlank(credentials.url()) || isBlank(credentials.username()) || isBlank(credentials.password())
			|| isBlank(input.environment()) || isBlank(input.action()) || isBlank(input.operationId())
			|| isBlank(input.backendSourceSha()) || isBlank(input.actorIdentitySha256())
			|| isBlank(input.backupReceiptSha256()) || isBlank(input.targetDatabaseIdentitySha256())
			|| isBlank(input.approval()) || isBlank(input.reason())) {
			return Reason.INVALID_INPUT;
		}
		if (!runtimeIdentity.matches(policy)) return Reason.RESOURCE_DRIFT;
		if (!policy.allowedEnvironments().contains(input.environment())
			|| !policy.action().equals(input.action())
			|| !input.operationId().matches("[a-f0-9]{8}-[a-f0-9]{4}-[1-5][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}")
			|| !input.backendSourceSha().matches("[a-f0-9]{40}")
			|| !input.actorIdentitySha256().matches("[a-f0-9]{64}")
			|| !input.backupReceiptSha256().matches("[a-f0-9]{64}")
			|| !input.targetDatabaseIdentitySha256().matches("[a-f0-9]{64}")
			|| !policy.approval().equals(input.approval())
			|| !policy.reason().equals(input.reason())) {
			return Reason.INVALID_INPUT;
		}
		return null;
	}

	private static Reason validateLegacyState(Connection connection, Policy policy, OperationInput input) throws SQLException {
		if (historyExists(connection, policy.targetSchema())) return Reason.HISTORY_PRESENT;
		var identity = LegacySchemaFingerprint.databaseIdentity(connection, policy.targetSchema());
		if (identity.postgresMajor() != policy.postgresMajor()) return Reason.POSTGRES_MAJOR_MISMATCH;
		if (!identity.sha256().equals(input.targetDatabaseIdentitySha256())) return Reason.TARGET_IDENTITY_MISMATCH;
		String fingerprint = LegacySchemaFingerprint.calculate(connection, policy.targetSchema());
		if (!policy.schemaFingerprintSha256().equals(fingerprint)) return Reason.SCHEMA_FINGERPRINT_MISMATCH;
		return null;
	}

	private static boolean historyExists(Connection connection, String schema) throws SQLException {
		try (var statement = connection.prepareStatement("""
			SELECT EXISTS (
			  SELECT 1
			  FROM pg_catalog.pg_class c
			  JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
			  WHERE n.nspname = ? AND c.relname = ?
			)
			""")) {
			statement.setString(1, schema);
			statement.setString(2, HISTORY_TABLE);
			try (var result = statement.executeQuery()) {
				return result.next() && result.getBoolean(1);
			}
		}
	}

	private static boolean tryAdvisoryLock(Connection connection, long key) throws SQLException {
		try (var statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
			statement.setLong(1, key);
			try (var result = statement.executeQuery()) {
				return result.next() && result.getBoolean(1);
			}
		}
	}

	private static void releaseAdvisoryLock(Connection connection, long key) {
		try (var statement = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
			statement.setLong(1, key);
			statement.execute();
		} catch (SQLException ignored) {
			// Closing the dedicated connection also releases this session-level lock.
		}
	}

	private static boolean hasExactBaselineHistory(Connection connection, String schema, String baselineVersion) throws SQLException {
		String quotedSchema = '"' + schema + '"';
		String sql = """
			SELECT COUNT(*),
			       COUNT(*) FILTER (WHERE type = 'BASELINE' AND version = ? AND success)
			FROM %s.flyway_schema_history
			""".formatted(quotedSchema);
		try (var statement = connection.prepareStatement(sql)) {
			statement.setString(1, baselineVersion);
			try (var result = statement.executeQuery()) {
				return result.next() && result.getInt(1) == 1 && result.getInt(2) == 1;
			}
		}
	}

	private static void verifyMigrationInventory(ClassLoader classLoader, byte[] inventoryBytes) throws IOException {
		JsonNode inventory = strictMapper().readTree(inventoryBytes);
		assertExactKeys(inventory, INVENTORY_KEYS, "migration inventory");
		if (inventory.path("schemaVersion").asInt(-1) != 1 || !inventory.path("migrations").isArray()
			|| inventory.path("migrations").isEmpty()) {
			throw new ContractFailure(Reason.RESOURCE_DRIFT);
		}
		int previousVersion = 0;
		for (JsonNode migration : inventory.path("migrations")) {
			assertExactKeys(migration, MIGRATION_KEYS, "migration inventory entry");
			String version = migration.path("version").asText();
			String path = migration.path("path").asText();
			String expectedSha256 = migration.path("sha256").asText();
			if (!version.matches("[1-9][0-9]*") || !path.startsWith(MIGRATION_SOURCE_PREFIX)
				|| !path.matches("backend/src/main/resources/db/migration/postgresql/V[1-9][0-9]*__[a-z0-9_]+\\.sql")
				|| !expectedSha256.matches("[a-f0-9]{64}")) {
				throw new ContractFailure(Reason.RESOURCE_DRIFT);
			}
			int numericVersion = Integer.parseInt(version);
			if (numericVersion <= previousVersion) throw new ContractFailure(Reason.RESOURCE_DRIFT);
			previousVersion = numericVersion;
			String resourcePath = path.substring(MIGRATION_SOURCE_PREFIX.length());
			if (!sha256(readResource(classLoader, resourcePath)).equals(expectedSha256)) {
				throw new ContractFailure(Reason.RESOURCE_DRIFT);
			}
		}
		if (previousVersion < 2) throw new ContractFailure(Reason.RESOURCE_DRIFT);
	}

	private static byte[] readResource(ClassLoader classLoader, String path) throws IOException {
		try (InputStream input = classLoader.getResourceAsStream(path)) {
			if (input == null) throw new IOException("missing resource");
			return input.readAllBytes();
		}
	}

	private static ObjectMapper strictMapper() {
		JsonFactory factory = JsonFactory.builder()
			.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
			.build();
		return JsonMapper.builder(factory)
			.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
			.build();
	}

	private static void assertExactKeys(JsonNode value, Set<String> expected, String label) {
		if (value == null || !value.isObject()) throw new IllegalArgumentException("invalid " + label);
		List<String> actual = new ArrayList<>();
		value.fieldNames().forEachRemaining(actual::add);
		if (!Set.copyOf(actual).equals(expected) || actual.size() != expected.size()) {
			throw new IllegalArgumentException("invalid " + label);
		}
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 unavailable", exception);
		}
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	enum Reason {
		BASELINED,
		INVALID_ARGUMENTS,
		INVALID_INPUT,
		POLICY_INVALID,
		RESOURCE_DRIFT,
		DATABASE_CONNECTION_FAILED,
		POSTGRES_MAJOR_MISMATCH,
		TARGET_IDENTITY_MISMATCH,
		HISTORY_PRESENT,
		SCHEMA_FINGERPRINT_MISMATCH,
		CONCURRENT_TRANSITION,
		INTERRUPTED,
		BASELINE_VERIFICATION_FAILED,
		INTERNAL_FAILURE
	}

	record Policy(
		String contractVersion,
		String evidenceSchema,
		int postgresMajor,
		String targetSchema,
		String baselineVersion,
		List<String> allowedEnvironments,
		String action,
		String approvalUrl,
		String approval,
		String reason,
		String schemaFingerprintSha256,
		String migrationInventorySha256,
		String migrationDdlPolicySha256,
		String productionConfigSha256,
		long advisoryLockKey,
		List<String> resultReasons
	) {
		Policy {
			allowedEnvironments = allowedEnvironments == null ? List.of() : List.copyOf(allowedEnvironments);
			resultReasons = resultReasons == null ? List.of() : List.copyOf(resultReasons);
		}

		void validate() {
			List<String> expectedReasons = Arrays.stream(Reason.values()).map(Enum::name).toList();
			if (!CONTRACT_VERSION.equals(contractVersion)
				|| !EVIDENCE_SCHEMA.equals(evidenceSchema)
				|| postgresMajor != 16
				|| !"public".equals(targetSchema)
				|| !"1".equals(baselineVersion)
				|| !allowedEnvironments.equals(List.of("prod", "staging", "release", "prod-like"))
				|| !"BASELINE_REVIEWED_LEGACY_V1".equals(action)
				|| !"https://github.com/AquilaXk/easysubway-backend/issues/8".equals(approvalUrl)
				|| !"APPROVED_BY_ISSUE_8".equals(approval)
				|| !"REVIEWED_LEGACY_V1_SCHEMA".equals(reason)
				|| !schemaFingerprintSha256.matches("[a-f0-9]{64}")
				|| !migrationInventorySha256.matches("[a-f0-9]{64}")
				|| !migrationDdlPolicySha256.matches("[a-f0-9]{64}")
				|| !productionConfigSha256.matches("[a-f0-9]{64}")
				|| advisoryLockKey == 0
				|| !resultReasons.equals(expectedReasons)) {
				throw new IllegalArgumentException("invalid policy");
			}
		}
	}

	record DatabaseCredentials(String url, String username, String password) {
		@Override
		public String toString() {
			return "DatabaseCredentials[redacted]";
		}
	}

	record OperationInput(
		String environment,
		String action,
		String operationId,
		String backendSourceSha,
		String actorIdentitySha256,
		String backupReceiptSha256,
		String targetDatabaseIdentitySha256,
		String approval,
		String reason
	) {
	}

	record CommandInput(DatabaseCredentials credentials, OperationInput operation) {
	}

	record RuntimeIdentity(
		String migrationInventorySha256,
		String migrationDdlPolicySha256,
		String productionConfigSha256
	) {
		boolean matches(Policy policy) {
			return policy.migrationInventorySha256().equals(migrationInventorySha256)
				&& policy.migrationDdlPolicySha256().equals(migrationDdlPolicySha256)
				&& policy.productionConfigSha256().equals(productionConfigSha256);
		}
	}

	record Result(
		boolean success,
		Reason reason,
		OperationInput input,
		RuntimeIdentity runtimeIdentity,
		String beforeState,
		String afterState,
		Instant observedAt
	) {
		static Result success(OperationInput input, RuntimeIdentity runtimeIdentity, Instant observedAt) {
			return new Result(
				true,
				Reason.BASELINED,
				input,
				runtimeIdentity,
				"REVIEWED_LEGACY_V1_NO_HISTORY",
				"BASELINE_VERSION_1",
				observedAt
			);
		}

		static Result failure(Reason reason, Instant observedAt) {
			return new Result(false, reason, null, null, null, null, observedAt);
		}
	}

	@FunctionalInterface
	interface BeforeMutation {
		void run() throws Exception;
	}

	static final class ContractFailure extends RuntimeException {
		@java.io.Serial
		private static final long serialVersionUID = 1L;

		private final Reason reason;

		ContractFailure(Reason reason) {
			super(reason.name(), null, false, false);
			this.reason = reason;
		}

		Reason reason() {
			return reason;
		}
	}
}

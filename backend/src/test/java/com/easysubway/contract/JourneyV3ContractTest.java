package com.easysubway.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

@DisplayName("Journey V3 raw contract")
class JourneyV3ContractTest {

	private static final Path CONTRACTS = Path.of("..", "contracts", "api");
	private static final Path OPENAPI = CONTRACTS.resolve("journey-v3.openapi.yaml");
	private static final Path ERROR_CATALOG = CONTRACTS.resolve("journey-v3-error-catalog.json");
	private static final Path SESSION_INTEGRITY = CONTRACTS.resolve("journey-v3-session-integrity.json");
	private static final Path DIGESTS = CONTRACTS.resolve("journey-v3-contract-digests.json");
	private static final Path CONTRACT_ATTRIBUTES = CONTRACTS.resolve(".gitattributes");
	private static final ObjectMapper JSON = new ObjectMapper();

	private static final List<ErrorPair> APPLICATION_ERRORS = List.of(
		new ErrorPair("searchJourneys", 400, "INVALID_JOURNEY_REQUEST"),
		new ErrorPair("searchJourneys", 404, "STATION_NOT_FOUND"),
		new ErrorPair("searchJourneys", 422, "ROUTE_NOT_FOUND"),
		new ErrorPair("searchJourneys", 422, "ACCESSIBILITY_CONSTRAINT_UNSATISFIED"),
		new ErrorPair("searchJourneys", 503, "ROUTING_BUNDLE_UNAVAILABLE"),
		new ErrorPair("searchJourneys", 503, "ROUTING_BUNDLE_STALE"),
		new ErrorPair("searchJourneys", 503, "TIMETABLE_UNAVAILABLE"),
		new ErrorPair("searchJourneys", 503, "TIMETABLE_STALE"),
		new ErrorPair("searchJourneys", 503, "REALTIME_REQUIRED_UNAVAILABLE"),
		new ErrorPair("searchJourneys", 503, "ROUTING_IDENTITY_MISMATCH"),
		new ErrorPair("searchJourneys", 503, "ROUTE_SERVICE_UNAVAILABLE"),
		new ErrorPair("searchJourneys", 504, "JOURNEY_SEARCH_TIMEOUT")
	);

	private static final List<ErrorPair> INGRESS_ERRORS = List.of(
		new ErrorPair("searchJourneys", 401, "ROUTE_SESSION_REQUIRED"),
		new ErrorPair("searchJourneys", 429, "ROUTE_RATE_LIMITED"),
		new ErrorPair("issueJourneySession", 400, "INVALID_JOURNEY_SESSION_REQUEST"),
		new ErrorPair("issueJourneySession", 403, "ROUTE_SESSION_ATTESTATION_REJECTED"),
		new ErrorPair("issueJourneySession", 503, "ROUTE_SESSION_ATTESTATION_UNAVAILABLE")
	);

	private static final List<ArtifactDigest> EXPECTED_DIGESTS = List.of(
		new ArtifactDigest("journey-v3-error-catalog.json", "5b93075c2e19801c8084e8ab08b5efb1ef8267822b3a71487742e7888e822772"),
		new ArtifactDigest("journey-v3-error-disposition.json", "1e03ee7262897e0887ef837c95a2802ff420ffeaf15c921e0dca8a9750280780"),
		new ArtifactDigest("journey-v3-session-integrity.json", "06e4fce1260ef807c5a1cc226789ea9e952d2c49f0a50bd0bd7d954b4f1910ad"),
		new ArtifactDigest("journey-v3.openapi.yaml", "7de00754abb8c0088707164bca634abf6fe01cd846151b95084357735702b980")
	);

	@Test
	@DisplayName("public surface and bearer boundary are exact")
	void publicSurfaceAndBearerBoundaryAreExact() throws IOException {
		Map<String, Object> document = openApi();
		assertThat(document.get("openapi")).isEqualTo("3.0.3");
		assertThat(document).doesNotContainKey("security");
		assertThat(map(document.get("paths")).keySet()).containsExactly(
			"/api/v3/journeys/session",
			"/api/v3/journeys/search"
		);

		Map<String, Object> session = operation(document, "/api/v3/journeys/session");
		Map<String, Object> search = operation(document, "/api/v3/journeys/search");
		assertThat(session.get("operationId")).isEqualTo("issueJourneySession");
		assertThat(session).doesNotContainKey("security");
		assertThat(search.get("operationId")).isEqualTo("searchJourneys");
		assertThat(list(search.get("security"))).containsExactly(Map.of("JourneySessionBearer", List.of()));
		assertOperationSchemaRefs(session, "JourneySessionRequest", "JourneySessionResponse");
		assertOperationSchemaRefs(search, "JourneySearchRequest", "JourneySearchSuccess");
		assertThat(map(search.get("x-easysubway-time-policy-contract"))).containsExactly(
			Map.entry("TIMETABLE_REQUIRED", "realtime-fields-null"),
			Map.entry("REALTIME_REQUIRED", "realtime-fields-required-non-null")
		);

		Map<String, Object> scheme = map(map(document.get("components")).get("securitySchemes"));
		assertThat(map(scheme.get("JourneySessionBearer"))).containsExactly(
			Map.entry("type", "http"),
			Map.entry("scheme", "bearer"),
			Map.entry("bearerFormat", "opaque-route-session")
		);
	}

	@Test
	@DisplayName("session and search request schemas reject unknown and ambiguous input")
	void requestSchemasRejectUnknownAndAmbiguousInput() throws IOException {
		Map<String, Object> document = openApi();
		assertClosedSchema(document, "JourneySessionRequest",
			Set.of("integrityToken", "clientNonce"), Set.of("integrityToken", "clientNonce"));
		assertClosedSchema(document, "JourneySessionResponse",
			Set.of("token", "scope", "issuedAt", "expiresAt"),
			Set.of("token", "scope", "issuedAt", "expiresAt"));
		assertThat(property(document, "JourneySessionRequest", "integrityToken").get("maxLength")).isEqualTo(16_384);
		assertThat(property(document, "JourneySessionRequest", "clientNonce").get("pattern"))
			.isEqualTo("^[A-Za-z0-9_-]{22}$");
		assertThat(property(document, "JourneySessionResponse", "token")).containsExactly(
			Map.entry("type", "string"),
			Map.entry("minLength", 1));
		assertEnum(property(document, "JourneySessionResponse", "scope"), "journey:v3");

		assertClosedSchema(document, "JourneySearchRequest",
			Set.of("requestId", "originStationId", "destinationStationId", "departure", "timePolicy",
				"walkingPace", "mobilityProfile", "constraintMode", "maxTransfers", "alternativeCount"),
			Set.of("requestId", "originStationId", "destinationStationId", "departure", "timePolicy",
				"walkingPace", "mobilityProfile", "constraintMode", "maxTransfers", "alternativeCount"));
		assertThat(property(document, "JourneySearchRequest", "requestId").get("pattern"))
			.isEqualTo("^[0-7][0-9A-HJKMNP-TV-Z]{25}$");
		assertThat(property(document, "JourneySearchSuccess", "requestId").get("pattern"))
			.isEqualTo("^[0-7][0-9A-HJKMNP-TV-Z]{25}$");
		assertThat(property(document, "JourneyError", "requestId").get("pattern"))
			.isEqualTo("^[0-7][0-9A-HJKMNP-TV-Z]{25}$");
		assertThat(property(document, "JourneySearchRequest", "maxTransfers"))
			.containsEntry("minimum", 0).containsEntry("maximum", 3);
		assertThat(property(document, "JourneySearchRequest", "alternativeCount"))
			.containsEntry("minimum", 1).containsEntry("maximum", 3);
		assertEnum(schema(document, "TimePolicy"), "TIMETABLE_REQUIRED", "REALTIME_REQUIRED");
		assertEnum(schema(document, "WalkingPace"), "SLOW", "STANDARD", "FAST");
		assertEnum(schema(document, "MobilityProfile"), "STANDARD", "SLOW", "NO_STAIRS", "STEP_FREE");
		assertEnum(schema(document, "ConstraintMode"), "NONE", "REQUIRE_STEP_FREE");

		assertThat(references(schema(document, "JourneyDeparture").get("oneOf"))).containsExactly(
			"#/components/schemas/JourneyDepartureNow",
			"#/components/schemas/JourneyDepartureScheduled"
		);
		assertClosedSchema(document, "JourneyDepartureNow", Set.of("mode"), Set.of("mode"));
		assertClosedSchema(document, "JourneyDepartureScheduled", Set.of("mode", "requestedAt"),
			Set.of("mode", "requestedAt"));
		assertEnum(property(document, "JourneyDepartureNow", "mode"), "NOW");
		assertEnum(property(document, "JourneyDepartureScheduled", "mode"), "SCHEDULED");
		assertThat(property(document, "JourneyDepartureScheduled", "requestedAt"))
			.containsEntry("type", "string").containsEntry("format", "date-time");

		Map<String, Object> forbidden = map(schema(document, "JourneySearchRequest").get("not"));
		assertThat(strings(forbidden.get("required"))).containsExactly("mobilityProfile", "constraintMode");
		Map<String, Object> forbiddenProperties = map(forbidden.get("properties"));
		assertEnum(map(forbiddenProperties.get("mobilityProfile")), "NO_STAIRS");
		assertEnum(map(forbiddenProperties.get("constraintMode")), "NONE");
	}

	@Test
	@DisplayName("success can contain only verified RAPTOR journeys from one source identity")
	void successContainsOnlyVerifiedRaptorJourneys() throws IOException {
		Map<String, Object> document = openApi();
		assertClosedSchema(document, "JourneySearchSuccess",
			Set.of("contractVersion", "requestId", "queryId", "calculatedAt", "validUntil",
				"effectiveDepartureTime", "serviceDate", "serviceTimezone", "sourceIdentity", "requestPolicy", "journeys"),
			Set.of("contractVersion", "requestId", "queryId", "calculatedAt", "validUntil",
				"effectiveDepartureTime", "serviceDate", "serviceTimezone", "sourceIdentity", "requestPolicy", "journeys"));
		assertEnum(property(document, "JourneySearchSuccess", "contractVersion"), "JOURNEY_SEARCH_V3");
		assertEnum(property(document, "JourneySearchSuccess", "serviceTimezone"), "Asia/Seoul");
		assertClosedSchema(document, "JourneySourceIdentity",
			Set.of("routeBundleId", "routeBundleSha256", "timetableSnapshotId", "accessibilitySnapshotId",
				"realtimeSnapshotId"),
			Set.of("routeBundleId", "routeBundleSha256", "timetableSnapshotId", "accessibilitySnapshotId",
				"realtimeSnapshotId"));
		assertThat(property(document, "JourneySourceIdentity", "realtimeSnapshotId").get("nullable")).isEqualTo(true);
		assertClosedSchema(document, "JourneyRequestPolicy",
			Set.of("timePolicy", "walkingPace", "mobilityProfile", "constraintMode", "maxTransfers", "alternativeCount"),
			Set.of("timePolicy", "walkingPace", "mobilityProfile", "constraintMode", "maxTransfers", "alternativeCount"));

		Set<String> journeyFields = Set.of("journeyId", "status", "planSource", "plannedDepartureTime",
			"plannedArrivalTime", "realtimeDepartureTime", "realtimeArrivalTime", "durationSeconds",
			"transferCount", "walkingDistanceMeters", "timeSource", "accessibility", "legs");
		assertClosedSchema(document, "Journey", journeyFields, journeyFields);
		assertEnum(property(document, "Journey", "status"), "FOUND");
		assertEnum(property(document, "Journey", "planSource"), "SERVER_TIMETABLE_RAPTOR");
		assertEnum(property(document, "Journey", "timeSource"), "TIMETABLE", "REALTIME");
		assertThat(property(document, "Journey", "realtimeDepartureTime").get("nullable")).isEqualTo(true);
		assertThat(property(document, "Journey", "realtimeArrivalTime").get("nullable")).isEqualTo(true);
		assertClosedSchema(document, "JourneyAccessibility",
			Set.of("result", "stairFree", "reasonCodes"), Set.of("result", "stairFree", "reasonCodes"));
		assertEnum(property(document, "JourneyAccessibility", "result"), "VERIFIED");

		assertThat(references(schema(document, "JourneyLeg").get("oneOf"))).containsExactly(
			"#/components/schemas/JourneyEntryLeg", "#/components/schemas/JourneyRideLeg",
			"#/components/schemas/JourneyTransferLeg", "#/components/schemas/JourneyExitLeg"
		);
		assertLeg(document, "JourneyEntryLeg", "ENTRY", Set.of("type", "fromStationId", "durationSeconds"));
		assertLeg(document, "JourneyExitLeg", "EXIT", Set.of("type", "fromStationId", "durationSeconds"));
		assertLeg(document, "JourneyTransferLeg", "TRANSFER",
			Set.of("type", "fromStationId", "toStationId", "durationSeconds"));
		Set<String> rideFields = Set.of("type", "lineId", "tripId", "directionStationId", "fromStationId",
			"toStationId", "plannedDepartureTime", "plannedArrivalTime", "realtimeDepartureTime",
			"realtimeArrivalTime");
		assertLeg(document, "JourneyRideLeg", "RIDE", rideFields);
		assertThat(property(document, "JourneyRideLeg", "realtimeDepartureTime").get("nullable")).isEqualTo(true);
		assertThat(property(document, "JourneyRideLeg", "realtimeArrivalTime").get("nullable")).isEqualTo(true);
	}

	@Test
	@DisplayName("OpenAPI and error catalog contain the same closed status-code inventory")
	void openApiAndErrorCatalogHaveExactErrorInventory() throws IOException {
		Map<String, Object> document = openApi();
		JsonNode catalog = JSON.readTree(ERROR_CATALOG.toFile());
		assertThat(fieldNames(catalog)).containsExactlyInAnyOrder(
			"schemaVersion", "artifactKind", "applicationErrors", "ingressErrors");
		assertThat(catalog.path("schemaVersion").asText()).isEqualTo("JOURNEY_ERROR_CATALOG_V1");
		assertThat(catalog.path("artifactKind").asText()).isEqualTo("journey-v3-error-catalog");
		assertThat(errorPairs(catalog.path("applicationErrors"))).containsExactlyElementsOf(APPLICATION_ERRORS);
		assertThat(errorPairs(catalog.path("ingressErrors"))).containsExactlyElementsOf(INGRESS_ERRORS);
		assertThat(new LinkedHashSet<>(APPLICATION_ERRORS)).hasSameSizeAs(APPLICATION_ERRORS);
		assertThat(new LinkedHashSet<>(INGRESS_ERRORS)).hasSameSizeAs(INGRESS_ERRORS);

		List<ErrorPair> searchPairs = responsePairs(operation(document, "/api/v3/journeys/search"));
		List<ErrorPair> sessionPairs = responsePairs(operation(document, "/api/v3/journeys/session"));
		assertThat(searchPairs).containsExactlyElementsOf(concat(
			APPLICATION_ERRORS,
			INGRESS_ERRORS.stream().filter(pair -> pair.operation().equals("searchJourneys")).toList()
		));
		assertThat(sessionPairs).containsExactlyElementsOf(
			INGRESS_ERRORS.stream().filter(pair -> pair.operation().equals("issueJourneySession")).toList()
		);

		Set<String> errorFields = Set.of("contractVersion", "requestId", "code", "retryable", "occurredAt");
		assertClosedSchema(document, "JourneyError", errorFields, errorFields);
		assertEnum(property(document, "JourneyError", "contractVersion"), "JOURNEY_ERROR_V1");
		assertEnum(schema(document, "JourneyErrorCode"), concat(APPLICATION_ERRORS, INGRESS_ERRORS).stream()
			.map(ErrorPair::code).toArray(String[]::new));
	}

	@Test
	@DisplayName("session integrity raw resource fixes the closed issuance and attestation contract")
	void sessionIntegrityResourceIsExact() throws IOException {
		JsonNode integrity = JSON.readTree(SESSION_INTEGRITY.toFile());
		assertThat(fieldNames(integrity)).containsExactly(
			"schemaVersion", "artifactKind", "operationId", "nonce", "requestHash", "verdict", "session"
		);
		assertThat(integrity.path("schemaVersion").asText()).isEqualTo("JOURNEY_V3_SESSION_INTEGRITY_V1");
		assertThat(integrity.path("artifactKind").asText()).isEqualTo("journey-v3-session-integrity");
		assertThat(integrity.path("operationId").asText()).isEqualTo("issueJourneySession");
		assertThat(operation(openApi(), "/api/v3/journeys/session").get("operationId"))
			.isEqualTo(integrity.path("operationId").asText());
		assertClosedSchema(openApi(), "JourneySessionRequest",
			Set.of("integrityToken", "clientNonce"), Set.of("integrityToken", "clientNonce"));
		assertClosedSchema(openApi(), "JourneySessionResponse",
			Set.of("token", "scope", "issuedAt", "expiresAt"), Set.of("token", "scope", "issuedAt", "expiresAt"));

		JsonNode nonce = integrity.path("nonce");
		assertThat(fieldNames(nonce)).containsExactly("source", "entropyBytes", "encoding", "pattern", "lifecycle");
		assertThat(nonce.path("source").asText()).isEqualTo("CSPRNG");
		assertThat(nonce.path("entropyBytes").isIntegralNumber()).isTrue();
		assertThat(nonce.path("entropyBytes").asInt()).isEqualTo(16);
		assertThat(nonce.path("encoding").asText()).isEqualTo("BASE64URL_NO_PADDING");
		assertThat(nonce.path("pattern").asText()).isEqualTo("^[A-Za-z0-9_-]{21}[AQgw]$");
		assertThat(nonce.path("lifecycle").asText()).isEqualTo("ONE_PER_SESSION_ISSUANCE");
		String knownNonce = "AAAAAAAAAAAAAAAAAAAAAA";
		assertThat(knownNonce).matches(nonce.path("pattern").asText());
		assertThat(Base64.getUrlDecoder().decode(knownNonce)).hasSize(16);
		String nonCanonicalNonce = "AAAAAAAAAAAAAAAAAAAAAB";
		assertThat(Base64.getUrlDecoder().decode(nonCanonicalNonce))
			.isEqualTo(Base64.getUrlDecoder().decode(knownNonce));
		assertThat(nonCanonicalNonce).doesNotMatch(nonce.path("pattern").asText());

		JsonNode requestHash = integrity.path("requestHash");
		assertThat(fieldNames(requestHash)).containsExactly("requestType", "algorithm", "encoding", "pattern",
			"canonicalPayloadUtf8Template", "purpose", "version", "sensitivePlaintextAllowed");
		assertThat(requestHash.path("requestType").asText()).isEqualTo("PLAY_INTEGRITY_STANDARD");
		assertThat(requestHash.path("algorithm").asText()).isEqualTo("SHA-256");
		assertThat(requestHash.path("encoding").asText()).isEqualTo("BASE64URL_NO_PADDING");
		assertThat(requestHash.path("pattern").asText()).isEqualTo("^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$");
		String canonicalPayload = "{\"clientNonce\":\"" + knownNonce + "\",\"purpose\":\"journey:v3:session\",\"version\":1}";
		assertThat(requestHash.path("canonicalPayloadUtf8Template").asText())
			.isEqualTo("{\"clientNonce\":\"<clientNonce>\",\"purpose\":\"journey:v3:session\",\"version\":1}");
		assertThat(requestHash.path("purpose").asText()).isEqualTo("journey:v3:session");
		assertThat(requestHash.path("version").isIntegralNumber()).isTrue();
		assertThat(requestHash.path("version").asInt()).isEqualTo(1);
		assertThat(requestHash.path("sensitivePlaintextAllowed").isBoolean()).isTrue();
		assertThat(requestHash.path("sensitivePlaintextAllowed").asBoolean()).isFalse();
		String knownRequestHash = "oiyD4z8SIUGWUKR8znsbTQ1Z26WO43JHm3RUZLuwErU";
		assertThat(base64UrlSha256(canonicalPayload)).isEqualTo(knownRequestHash);
		assertThat(knownRequestHash).matches(requestHash.path("pattern").asText());
		assertThat(Base64.getUrlDecoder().decode(knownRequestHash)).hasSize(32);
		String nonCanonicalRequestHash = "oiyD4z8SIUGWUKR8znsbTQ1Z26WO43JHm3RUZLuwErV";
		assertThat(Base64.getUrlDecoder().decode(nonCanonicalRequestHash))
			.isEqualTo(Base64.getUrlDecoder().decode(knownRequestHash));
		assertThat(nonCanonicalRequestHash).doesNotMatch(requestHash.path("pattern").asText());

		JsonNode verdict = integrity.path("verdict");
		assertThat(fieldNames(verdict)).containsExactly("expectedRequestPackageName", "expectedAppPackageName",
			"maxAgeSeconds", "futureTimestampAllowed", "requiredAppRecognitionVerdict",
			"requiredAppLicensingVerdict", "requiredDeviceRecognitionVerdict",
			"configuredCertificateSha256Required", "configuredCertificateSha256Encoding",
			"requestHashConstantTimeEqualityRequired", "nonceSingleUseRequired", "nonceClaimTtlSeconds");
		assertThat(verdict.path("expectedRequestPackageName").asText()).isEqualTo("com.easysubway.app");
		assertThat(verdict.path("expectedAppPackageName").asText()).isEqualTo("com.easysubway.app");
		assertThat(verdict.path("maxAgeSeconds").isIntegralNumber()).isTrue();
		assertThat(verdict.path("maxAgeSeconds").asInt()).isEqualTo(120);
		assertThat(verdict.path("futureTimestampAllowed").isBoolean()).isTrue();
		assertThat(verdict.path("futureTimestampAllowed").asBoolean()).isFalse();
		assertThat(verdict.path("requiredAppRecognitionVerdict").asText()).isEqualTo("PLAY_RECOGNIZED");
		assertThat(verdict.path("requiredAppLicensingVerdict").asText()).isEqualTo("LICENSED");
		assertThat(verdict.path("requiredDeviceRecognitionVerdict").asText()).isEqualTo("MEETS_DEVICE_INTEGRITY");
		assertThat(verdict.path("configuredCertificateSha256Required").isBoolean()).isTrue();
		assertThat(verdict.path("configuredCertificateSha256Required").asBoolean()).isTrue();
		assertThat(verdict.path("configuredCertificateSha256Encoding").asText()).isEqualTo("BASE64URL_NO_PADDING");
		assertThat(verdict.path("requestHashConstantTimeEqualityRequired").isBoolean()).isTrue();
		assertThat(verdict.path("requestHashConstantTimeEqualityRequired").asBoolean()).isTrue();
		assertThat(verdict.path("nonceSingleUseRequired").isBoolean()).isTrue();
		assertThat(verdict.path("nonceSingleUseRequired").asBoolean()).isTrue();
		assertThat(verdict.path("nonceClaimTtlSeconds").isIntegralNumber()).isTrue();
		assertThat(verdict.path("nonceClaimTtlSeconds").asInt()).isEqualTo(120);

		JsonNode session = integrity.path("session");
		assertThat(fieldNames(session)).containsExactly("scope", "ttlSeconds");
		assertThat(session.path("scope").asText()).isEqualTo("journey:v3");
		assertThat(session.path("ttlSeconds").isIntegralNumber()).isTrue();
		assertThat(session.path("ttlSeconds").asInt()).isEqualTo(600);
	}

	@Test
	@DisplayName("digest artifact binds the exact Journey V3 raw contract bytes")
	void digestArtifactBindsRawContractBytes() throws IOException {
		assertThat(Files.readAllLines(CONTRACT_ATTRIBUTES, StandardCharsets.UTF_8)).containsExactly(
			"*.yaml text eol=lf",
			"*.json text eol=lf");
		JsonNode digest = JSON.readTree(DIGESTS.toFile());
		assertThat(fieldNames(digest)).containsExactlyInAnyOrder("schemaVersion", "artifactKind", "artifacts");
		assertThat(digest.path("schemaVersion").asText()).isEqualTo("JOURNEY_V3_CONTRACT_DIGESTS_V1");
		assertThat(digest.path("artifactKind").asText()).isEqualTo("journey-v3-contract-digests");
		assertThat(digest.path("artifacts").isArray()).isTrue();

		List<JsonNode> artifacts = new ArrayList<>();
		digest.path("artifacts").forEach(artifacts::add);
		assertThat(artifacts.stream().map(node -> node.path("path").asText()).toList())
			.containsExactlyElementsOf(EXPECTED_DIGESTS.stream().map(ArtifactDigest::path).toList());
		for (int index = 0; index < artifacts.size(); index++) {
			JsonNode artifact = artifacts.get(index);
			ArtifactDigest expected = EXPECTED_DIGESTS.get(index);
			assertThat(fieldNames(artifact)).containsExactlyInAnyOrder("path", "sha256");
			Path file = CONTRACTS.resolve(artifact.path("path").asText());
			assertThat(artifact.path("sha256").asText()).isEqualTo(expected.sha256());
			assertThat(sha256(Files.readAllBytes(file))).isEqualTo(expected.sha256());
		}
	}

	private static Map<String, Object> openApi() throws IOException {
		return map(new Yaml().load(Files.readString(OPENAPI, StandardCharsets.UTF_8)));
	}

	private static Map<String, Object> operation(Map<String, Object> document, String path) {
		return map(map(map(document.get("paths")).get(path)).get("post"));
	}

	private static void assertOperationSchemaRefs(
		Map<String, Object> operation,
		String requestSchema,
		String successSchema
	) {
		Map<String, Object> requestBody = map(operation.get("requestBody"));
		Map<String, Object> requestContent = map(requestBody.get("content"));
		assertThat(map(map(requestContent.get("application/json")).get("schema")).get("$ref"))
			.isEqualTo("#/components/schemas/" + requestSchema);
		Map<String, Object> success = map(map(operation.get("responses")).get("200"));
		Map<String, Object> successContent = map(success.get("content"));
		assertThat(map(map(successContent.get("application/json")).get("schema")).get("$ref"))
			.isEqualTo("#/components/schemas/" + successSchema);
	}

	private static Map<String, Object> schema(Map<String, Object> document, String name) {
		return map(map(map(document.get("components")).get("schemas")).get(name));
	}

	private static Map<String, Object> property(Map<String, Object> document, String schema, String property) {
		return map(map(schema(document, schema).get("properties")).get(property));
	}

	private static void assertClosedSchema(
		Map<String, Object> document,
		String name,
		Set<String> required,
		Set<String> properties
	) {
		Map<String, Object> schema = schema(document, name);
		assertThat(schema.get("type")).isEqualTo("object");
		assertThat(schema.get("additionalProperties")).isEqualTo(false);
		assertThat(new LinkedHashSet<>(strings(schema.get("required")))).isEqualTo(required);
		assertThat(map(schema.get("properties")).keySet()).isEqualTo(properties);
	}

	private static void assertLeg(Map<String, Object> document, String name, String type, Set<String> fields) {
		assertClosedSchema(document, name, fields, fields);
		assertEnum(property(document, name, "type"), type);
	}

	private static void assertEnum(Map<String, Object> schema, String... values) {
		assertThat(strings(schema.get("enum"))).containsExactly(values);
	}

	private static List<String> references(Object value) {
		return list(value).stream().map(entry -> String.valueOf(map(entry).get("$ref"))).toList();
	}

	private static List<ErrorPair> responsePairs(Map<String, Object> operation) {
		String operationId = String.valueOf(operation.get("operationId"));
		List<ErrorPair> result = new ArrayList<>();
		for (Map.Entry<String, Object> response : map(operation.get("responses")).entrySet()) {
			if ("200".equals(response.getKey())) {
				continue;
			}
			Map<String, Object> content = map(map(response.getValue()).get("content"));
			Map<String, Object> json = map(content.get("application/json"));
			assertThat(map(json.get("schema")).get("$ref")).isEqualTo("#/components/schemas/JourneyError");
			for (Object example : map(json.get("examples")).values()) {
				String code = String.valueOf(map(map(example).get("value")).get("code"));
				result.add(new ErrorPair(operationId, Integer.parseInt(response.getKey()), code));
			}
		}
		return result;
	}

	private static List<ErrorPair> errorPairs(JsonNode array) {
		List<ErrorPair> result = new ArrayList<>();
		for (JsonNode entry : array) {
			assertThat(fieldNames(entry)).containsExactlyInAnyOrder("operation", "httpStatus", "code");
			result.add(new ErrorPair(entry.path("operation").asText(), entry.path("httpStatus").asInt(),
				entry.path("code").asText()));
		}
		return result;
	}

	private static List<ErrorPair> concat(List<ErrorPair> first, List<ErrorPair> second) {
		List<ErrorPair> result = new ArrayList<>(first);
		result.addAll(second);
		return List.copyOf(result);
	}

	private static Set<String> fieldNames(JsonNode node) {
		Set<String> names = new LinkedHashSet<>();
		node.fieldNames().forEachRemaining(names::add);
		return names;
	}

	private static String sha256(byte[] bytes) {
		return HexFormat.of().formatHex(sha256Bytes(bytes));
	}

	private static byte[] sha256Bytes(byte[] bytes) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(bytes);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 unavailable", exception);
		}
	}

	private static String base64UrlSha256(String value) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(
			sha256Bytes(value.getBytes(StandardCharsets.UTF_8))
		);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(Object value) {
		return (Map<String, Object>) value;
	}

	@SuppressWarnings("unchecked")
	private static List<Object> list(Object value) {
		return (List<Object>) value;
	}

	private static List<String> strings(Object value) {
		return list(value).stream().map(String::valueOf).toList();
	}

	private record ErrorPair(String operation, int httpStatus, String code) {
	}

	private record ArtifactDigest(String path, String sha256) {
	}
}

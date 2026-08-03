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
	private static final Path DIGESTS = CONTRACTS.resolve("journey-v3-contract-digests.json");
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
		new ErrorPair("issueJourneySession", 403, "ROUTE_SESSION_ATTESTATION_REJECTED"),
		new ErrorPair("issueJourneySession", 503, "ROUTE_SESSION_ATTESTATION_UNAVAILABLE")
	);

	@Test
	@DisplayName("public surface and bearer boundary are exact")
	void publicSurfaceAndBearerBoundaryAreExact() throws IOException {
		Map<String, Object> document = openApi();
		assertThat(document.get("openapi")).isEqualTo("3.0.3");
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
			Map.entry("type", "string"));
		assertEnum(property(document, "JourneySessionResponse", "scope"), "journey:v3");

		assertClosedSchema(document, "JourneySearchRequest",
			Set.of("requestId", "originStationId", "destinationStationId", "departure", "timePolicy",
				"mobilityProfile", "constraintMode", "maxTransfers", "alternativeCount"),
			Set.of("requestId", "originStationId", "destinationStationId", "departure", "timePolicy",
				"mobilityProfile", "constraintMode", "maxTransfers", "alternativeCount"));
		assertThat(property(document, "JourneySearchRequest", "requestId").get("pattern"))
			.isEqualTo("^[0-9A-HJKMNP-TV-Z]{26}$");
		assertThat(property(document, "JourneySearchRequest", "maxTransfers"))
			.containsEntry("minimum", 0).containsEntry("maximum", 3);
		assertThat(property(document, "JourneySearchRequest", "alternativeCount"))
			.containsEntry("minimum", 1).containsEntry("maximum", 3);
		assertEnum(schema(document, "TimePolicy"), "TIMETABLE_REQUIRED", "REALTIME_REQUIRED");
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
			Set.of("timePolicy", "mobilityProfile", "constraintMode", "maxTransfers", "alternativeCount"),
			Set.of("timePolicy", "mobilityProfile", "constraintMode", "maxTransfers", "alternativeCount"));

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
	@DisplayName("digest artifact binds the exact OpenAPI and catalog raw bytes")
	void digestArtifactBindsRawContractBytes() throws IOException {
		JsonNode digest = JSON.readTree(DIGESTS.toFile());
		assertThat(fieldNames(digest)).containsExactlyInAnyOrder("schemaVersion", "artifactKind", "artifacts");
		assertThat(digest.path("schemaVersion").asText()).isEqualTo("JOURNEY_V3_CONTRACT_DIGESTS_V1");
		assertThat(digest.path("artifactKind").asText()).isEqualTo("journey-v3-contract-digests");

		List<JsonNode> artifacts = new ArrayList<>();
		digest.path("artifacts").forEach(artifacts::add);
		assertThat(artifacts.stream().map(node -> node.path("path").asText()).toList()).containsExactly(
			"journey-v3-error-catalog.json", "journey-v3.openapi.yaml");
		for (JsonNode artifact : artifacts) {
			assertThat(fieldNames(artifact)).containsExactlyInAnyOrder("path", "sha256");
			Path file = CONTRACTS.resolve(artifact.path("path").asText());
			assertThat(artifact.path("sha256").asText()).isEqualTo(sha256(Files.readAllBytes(file)));
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
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 unavailable", exception);
		}
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
}

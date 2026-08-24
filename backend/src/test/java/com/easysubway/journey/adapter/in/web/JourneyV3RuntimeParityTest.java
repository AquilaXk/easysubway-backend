package com.easysubway.journey.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.journey.application.JourneyApplicationDeadlineExecutor;
import com.easysubway.journey.application.JourneyApplicationDeadlineExecutor.Completed;
import com.easysubway.journey.application.JourneyCandidate;
import com.easysubway.journey.application.JourneyExecutionResult;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.journey.application.JourneySessionException;
import com.easysubway.journey.application.JourneySessionService;
import com.easysubway.journey.application.JourneySessionService.AuthorizedSession;
import com.easysubway.journey.application.JourneySessionService.IssuedSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.yaml.snakeyaml.Yaml;

@DisplayName("Journey V3 runtime contract parity")
class JourneyV3RuntimeParityTest {

	private static final Path CONTRACTS = Path.of("..", "contracts", "api");
	private static final Path OPENAPI = CONTRACTS.resolve("journey-v3.openapi.yaml");
	private static final Path ERROR_CATALOG = CONTRACTS.resolve("journey-v3-error-catalog.json");
	private static final Path ERROR_DISPOSITION = CONTRACTS.resolve("journey-v3-error-disposition.json");
	private static final Instant NOW = Instant.parse("2026-08-24T00:00:00Z");
	private static final String REQUEST_ID = "01K1Y000000000000000000000";
	private static final String NONCE = "AAAAAAAAAAAAAAAAAAAAAA";
	private static final ObjectMapper PRODUCTION_JSON = new ObjectMapper().findAndRegisterModules();

	@Test
	@DisplayName("production JSON and HTTP boundary match the tracked success and representative error contracts")
	void runtimeMatchesTrackedSuccessAndErrorContracts() throws Exception {
		Map<String, Object> openApi = openApi();
		JsonNode errorCatalog = PRODUCTION_JSON.readTree(ERROR_CATALOG.toFile());
		JsonNode errorDisposition = PRODUCTION_JSON.readTree(ERROR_DISPOSITION.toFile());
		Runtime runtime = runtime();

		when(runtime.sessionService().issue("integrity-token", NONCE)).thenReturn(new IssuedSession(
			"A".repeat(43), "journey:v3", NOW, NOW.plusSeconds(600)
		));
		MvcResult session = runtime.mockMvc().perform(post("/api/v3/journeys/session")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"integrityToken\":\"integrity-token\",\"clientNonce\":\"" + NONCE + "\"}"))
			.andExpect(status().isOk())
			.andReturn();
		assertClosedPayload(openApi, "JourneySessionResponse", body(session));

		when(runtime.sessionService().authorize("session-token"))
			.thenReturn(new AuthorizedSession("journey:v3", NOW.plusSeconds(600)));
		when(runtime.deadlineExecutor().execute(any())).thenReturn(new Completed(success()));
		MvcResult search = runtime.mockMvc().perform(post("/api/v3/journeys/search")
				.header(HttpHeaders.AUTHORIZATION, "Bearer session-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(validSearchRequest()))
			.andExpect(status().isOk())
			.andReturn();
		assertSearchSuccess(openApi, body(search));

		Runtime sessionFailureRuntime = runtime();
		when(sessionFailureRuntime.sessionService().issue("rejected-token", NONCE))
			.thenThrow(new JourneySessionException(JourneySessionException.Kind.ATTESTATION_REJECTED));
		MvcResult sessionFailure = sessionFailureRuntime.mockMvc().perform(post("/api/v3/journeys/session")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"integrityToken\":\"rejected-token\",\"clientNonce\":\"" + NONCE + "\"}"))
			.andExpect(status().isForbidden())
			.andReturn();
		assertError(openApi, errorCatalog, errorDisposition, "issueJourneySession", sessionFailure);

		Runtime searchFailureRuntime = runtime();
		MvcResult searchFailure = searchFailureRuntime.mockMvc().perform(post("/api/v3/journeys/search")
				.contentType(MediaType.APPLICATION_JSON)
				.content(validSearchRequest()))
			.andExpect(status().isUnauthorized())
			.andReturn();
		assertError(openApi, errorCatalog, errorDisposition, "searchJourneys", searchFailure);
	}

	@Test
	@DisplayName("an undeclared success enum mutation makes the parity assertion red")
	void rejectsSuccessEnumMutation() throws Exception {
		JsonNode actual = PRODUCTION_JSON.readTree("""
			{
			  "contractVersion":"JOURNEY_SEARCH_V3",
			  "requestPolicy":{"walkingPace":"UNDECLARED"}
			}
			""");

		assertThatThrownBy(() -> assertEnum(openApi(), "JourneyRequestPolicy", "walkingPace",
			actual.path("requestPolicy").path("walkingPace").asText()))
			.isInstanceOf(AssertionError.class);
	}

	@Test
	@DisplayName("a status or machine-code mapping mutation makes the parity assertion red")
	void rejectsErrorMappingMutation() throws Exception {
		JsonNode errorCatalog = PRODUCTION_JSON.readTree(ERROR_CATALOG.toFile());
		JsonNode errorDisposition = PRODUCTION_JSON.readTree(ERROR_DISPOSITION.toFile());

		assertThatThrownBy(() -> assertErrorMapping(errorCatalog, errorDisposition,
			"searchJourneys", 500, "ROUTE_SESSION_REQUIRED"))
			.isInstanceOf(AssertionError.class);
	}

	private static Runtime runtime() {
		JourneySessionService sessionService = mock(JourneySessionService.class);
		JourneyApplicationDeadlineExecutor deadlineExecutor = mock(JourneyApplicationDeadlineExecutor.class);
		Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
		MockMvc mockMvc = MockMvcBuilders
			.standaloneSetup(
				new JourneySessionController(sessionService),
				new JourneySearchController(sessionService, deadlineExecutor)
			)
			.setControllerAdvice(
				new JourneySessionExceptionHandler(clock, new SecureRandom(new byte[] {1, 2, 3, 4})),
				new JourneySearchExceptionHandler(clock, new SecureRandom(new byte[] {5, 6, 7, 8}))
			)
			.setMessageConverters(
				new ByteArrayHttpMessageConverter(),
				new MappingJackson2HttpMessageConverter(PRODUCTION_JSON)
			)
			.build();
		return new Runtime(sessionService, deadlineExecutor, mockMvc);
	}

	private static void assertSearchSuccess(Map<String, Object> openApi, JsonNode payload) {
		assertClosedPayload(openApi, "JourneySearchSuccess", payload);
		assertEnum(openApi, "JourneySearchSuccess", "contractVersion", payload.path("contractVersion").asText());
		assertClosedPayload(openApi, "JourneySourceIdentity", payload.path("sourceIdentity"));
		assertNullable(openApi, "JourneySourceIdentity", "realtimeSnapshotId", payload.path("sourceIdentity").path("realtimeSnapshotId"));
		assertClosedPayload(openApi, "JourneyRequestPolicy", payload.path("requestPolicy"));
		assertEnum(openApi, "JourneyRequestPolicy", "timePolicy", payload.path("requestPolicy").path("timePolicy").asText());
		assertEnum(openApi, "JourneyRequestPolicy", "walkingPace", payload.path("requestPolicy").path("walkingPace").asText());
		assertEnum(openApi, "JourneyRequestPolicy", "mobilityProfile", payload.path("requestPolicy").path("mobilityProfile").asText());
		assertEnum(openApi, "JourneyRequestPolicy", "constraintMode", payload.path("requestPolicy").path("constraintMode").asText());

		JsonNode journey = payload.path("journeys").get(0);
		assertClosedPayload(openApi, "Journey", journey);
		assertEnum(openApi, "Journey", "status", journey.path("status").asText());
		assertEnum(openApi, "Journey", "planSource", journey.path("planSource").asText());
		assertEnum(openApi, "Journey", "timeSource", journey.path("timeSource").asText());
		assertNullable(openApi, "Journey", "realtimeDepartureTime", journey.path("realtimeDepartureTime"));
		assertNullable(openApi, "Journey", "realtimeArrivalTime", journey.path("realtimeArrivalTime"));
		assertClosedPayload(openApi, "JourneyRideLeg", journey.path("legs").get(0));
		assertEnum(openApi, "JourneyRideLeg", "type", journey.path("legs").get(0).path("type").asText());
		assertNullable(openApi, "JourneyRideLeg", "realtimeDepartureTime", journey.path("legs").get(0).path("realtimeDepartureTime"));
		assertNullable(openApi, "JourneyRideLeg", "realtimeArrivalTime", journey.path("legs").get(0).path("realtimeArrivalTime"));
	}

	private static void assertError(
		Map<String, Object> openApi,
		JsonNode errorCatalog,
		JsonNode errorDisposition,
		String operation,
		MvcResult result
	) throws Exception {
		JsonNode payload = body(result);
		assertClosedPayload(openApi, "JourneyError", payload);
		assertEnum(openApi, "JourneyError", "contractVersion", payload.path("contractVersion").asText());
		assertEnum(openApi, "JourneyErrorCode", null, payload.path("code").asText());
		assertErrorMapping(errorCatalog, errorDisposition, operation, result.getResponse().getStatus(), payload.path("code").asText());
		assertOpenApiErrorExample(openApi, operation, result.getResponse().getStatus(), payload.path("code").asText());
	}

	private static void assertErrorMapping(
		JsonNode errorCatalog,
		JsonNode errorDisposition,
		String operation,
		int httpStatus,
		String code
	) {
		assertThat(errorCatalog.path("applicationErrors").isArray()).isTrue();
		assertThat(errorCatalog.path("ingressErrors").isArray()).isTrue();
		assertThat(errorCatalog.findValues("operation")).isNotEmpty();
		assertThat(hasPair(errorCatalog.path("applicationErrors"), operation, httpStatus, code)
			|| hasPair(errorCatalog.path("ingressErrors"), operation, httpStatus, code)).isTrue();
		assertThat(hasPair(errorDisposition.path("entries"), operation, httpStatus, code)).isTrue();
	}

	private static void assertOpenApiErrorExample(
		Map<String, Object> openApi,
		String operationId,
		int httpStatus,
		String code
	) {
		Map<String, Object> operation = operation(openApi, operationId);
		Map<String, Object> response = map(map(operation.get("responses")).get(String.valueOf(httpStatus)));
		Map<String, Object> json = map(map(response.get("content")).get("application/json"));
		assertThat(map(json.get("schema")).get("$ref")).isEqualTo("#/components/schemas/JourneyError");
		assertThat(map(json.get("examples")).values())
			.anySatisfy(example -> assertThat(map(map(example).get("value")).get("code")).isEqualTo(code));
	}

	private static boolean hasPair(JsonNode entries, String operation, int httpStatus, String code) {
		for (JsonNode entry : entries) {
			if (operation.equals(entry.path("operation").asText())
				&& httpStatus == entry.path("httpStatus").asInt()
				&& (code.equals(entry.path("code").asText()) || code.equals(entry.path("machineCode").asText()))) {
				return true;
			}
		}
		return false;
	}

	private static void assertClosedPayload(Map<String, Object> openApi, String schemaName, JsonNode payload) {
		Map<String, Object> schema = schema(openApi, schemaName);
		assertThat(payload.isObject()).isTrue();
		assertThat(schema.get("additionalProperties")).isEqualTo(false);
		Set<String> actual = fieldNames(payload);
		assertThat(actual).containsExactlyInAnyOrderElementsOf(strings(schema.get("required")));
		assertThat(actual).containsExactlyInAnyOrderElementsOf(map(schema.get("properties")).keySet());
	}

	private static void assertNullable(Map<String, Object> openApi, String schemaName, String propertyName, JsonNode value) {
		assertThat(property(openApi, schemaName, propertyName).get("nullable")).isEqualTo(true);
		assertThat(value.isNull()).isTrue();
	}

	private static void assertEnum(Map<String, Object> openApi, String schemaName, String propertyName, String actual) {
		Map<String, Object> value = propertyName == null
			? schema(openApi, schemaName)
			: resolveSchema(openApi, property(openApi, schemaName, propertyName));
		assertThat(strings(value.get("enum"))).contains(actual);
	}

	private static JsonNode body(MvcResult result) throws Exception {
		return PRODUCTION_JSON.readTree(result.getResponse().getContentAsByteArray());
	}

	private static Map<String, Object> openApi() throws Exception {
		return map(new Yaml().load(Files.readString(OPENAPI, StandardCharsets.UTF_8)));
	}

	private static Map<String, Object> operation(Map<String, Object> openApi, String operationId) {
		for (Object pathItem : map(openApi.get("paths")).values()) {
			Map<String, Object> post = map(pathItem).containsKey("post") ? map(map(pathItem).get("post")) : null;
			if (post != null && operationId.equals(post.get("operationId"))) return post;
		}
		throw new AssertionError("missing OpenAPI operation: " + operationId);
	}

	private static Map<String, Object> schema(Map<String, Object> openApi, String schemaName) {
		return map(map(map(openApi.get("components")).get("schemas")).get(schemaName));
	}

	private static Map<String, Object> property(Map<String, Object> openApi, String schemaName, String propertyName) {
		return map(map(schema(openApi, schemaName).get("properties")).get(propertyName));
	}

	private static Map<String, Object> resolveSchema(Map<String, Object> openApi, Map<String, Object> value) {
		Object reference = value.get("$ref");
		if (reference == null) return value;
		String prefix = "#/components/schemas/";
		assertThat(reference).isInstanceOf(String.class);
		assertThat((String) reference).startsWith(prefix);
		return schema(openApi, ((String) reference).substring(prefix.length()));
	}

	private static Set<String> fieldNames(JsonNode node) {
		Set<String> fields = new LinkedHashSet<>();
		node.fieldNames().forEachRemaining(fields::add);
		return fields;
	}

	private static List<String> strings(Object value) {
		return list(value).stream().map(String::valueOf).toList();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(Object value) {
		assertThat(value).isInstanceOf(Map.class);
		return (Map<String, Object>) value;
	}

	@SuppressWarnings("unchecked")
	private static List<Object> list(Object value) {
		assertThat(value).isInstanceOf(List.class);
		return (List<Object>) value;
	}

	private static String validSearchRequest() {
		return """
			{
			  "requestId":"%s",
			  "originStationId":"station-origin",
			  "destinationStationId":"station-destination",
			  "departure":{"mode":"NOW"},
			  "timePolicy":"TIMETABLE_REQUIRED",
			  "walkingPace":"STANDARD",
			  "mobilityProfile":"STEP_FREE",
			  "constraintMode":"REQUIRE_STEP_FREE",
			  "maxTransfers":2,
			  "alternativeCount":1
			}
			""".formatted(REQUEST_ID);
	}

	private static JourneyExecutionResult.Success success() {
		Instant departure = NOW.plusSeconds(60);
		Instant arrival = departure.plusSeconds(300);
		var journey = new JourneyCandidate(
			"journey-1", departure, arrival, null, null, 300, 0, 0,
			JourneyCandidate.TimeSource.TIMETABLE,
			new JourneyCandidate.Accessibility(true, List.of("STEP_FREE_PATH")),
			List.of(new JourneyCandidate.Ride(
				"line-1", "trip-1", "station-destination", "station-origin", "station-destination",
				departure, arrival, null, null
			))
		);
		return new JourneyExecutionResult.Success(
			REQUEST_ID, "query-1", NOW, NOW.plusSeconds(600), departure, LocalDate.of(2026, 8, 24),
			new JourneyExecutionResult.SourceIdentity(
				"bundle-1", "a".repeat(64), "timetable-1", "accessibility-1", null
			),
			new JourneyExecutionResult.RequestPolicy(
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
				JourneyRequest.WalkingPace.STANDARD,
				JourneyRequest.MobilityProfile.STEP_FREE,
				JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE,
				2,
				1
			),
			List.of(journey)
		);
	}

	private record Runtime(
		JourneySessionService sessionService,
		JourneyApplicationDeadlineExecutor deadlineExecutor,
		MockMvc mockMvc
	) {
	}
}

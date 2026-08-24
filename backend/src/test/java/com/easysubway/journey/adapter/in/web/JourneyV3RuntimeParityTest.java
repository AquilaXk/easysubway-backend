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
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
	private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, WebMvcAutoConfiguration.class))
		.withUserConfiguration(RuntimeConfiguration.class);

	@Test
	@DisplayName("production JSON and HTTP boundary match the tracked success and representative error contracts")
	void runtimeMatchesTrackedSuccessAndErrorContracts() throws Exception {
		withRuntime(runtime -> {
			Map<String, Object> openApi = openApi();
			JsonNode errorCatalog = runtime.json().readTree(ERROR_CATALOG.toFile());
			JsonNode errorDisposition = runtime.json().readTree(ERROR_DISPOSITION.toFile());

			when(runtime.sessionService().issue("integrity-token", NONCE)).thenReturn(new IssuedSession(
				"A".repeat(43), "journey:v3", NOW, NOW.plusSeconds(600)
			));
			MvcResult session = runtime.mockMvc().perform(post("/api/v3/journeys/session")
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"integrityToken\":\"integrity-token\",\"clientNonce\":\"" + NONCE + "\"}"))
				.andExpect(status().isOk())
				.andReturn();
			assertResponseContract(openApi, "issueJourneySession", session, runtime.json());

			when(runtime.sessionService().authorize("session-token"))
				.thenReturn(new AuthorizedSession("journey:v3", NOW.plusSeconds(600)));
			when(runtime.deadlineExecutor().execute(any())).thenReturn(new Completed(success()));
			MvcResult search = runtime.mockMvc().perform(post("/api/v3/journeys/search")
					.header(HttpHeaders.AUTHORIZATION, "Bearer session-token")
					.contentType(MediaType.APPLICATION_JSON)
					.content(validSearchRequest()))
				.andExpect(status().isOk())
				.andReturn();
			assertResponseContract(openApi, "searchJourneys", search, runtime.json());

			when(runtime.sessionService().issue("rejected-token", NONCE))
				.thenThrow(new JourneySessionException(JourneySessionException.Kind.ATTESTATION_REJECTED));
			MvcResult sessionFailure = runtime.mockMvc().perform(post("/api/v3/journeys/session")
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"integrityToken\":\"rejected-token\",\"clientNonce\":\"" + NONCE + "\"}"))
				.andExpect(status().isForbidden())
				.andReturn();
			assertError(openApi, errorCatalog, errorDisposition, "issueJourneySession", sessionFailure, runtime.json());

			MvcResult searchFailure = runtime.mockMvc().perform(post("/api/v3/journeys/search")
					.contentType(MediaType.APPLICATION_JSON)
					.content(validSearchRequest()))
				.andExpect(status().isUnauthorized())
				.andReturn();
			assertError(openApi, errorCatalog, errorDisposition, "searchJourneys", searchFailure, runtime.json());
		});
	}

	@Test
	@DisplayName("an undeclared success enum mutation makes the parity assertion red")
	void rejectsSuccessEnumMutation() throws Exception {
		withRuntime(runtime -> {
			JsonNode actual = runtime.json().readTree("""
				{
				  "contractVersion":"JOURNEY_SEARCH_V3",
				  "requestPolicy":{"walkingPace":"UNDECLARED"}
				}
				""");

			assertThatThrownBy(() -> assertEnum(openApi(), "JourneyRequestPolicy", "walkingPace",
				actual.path("requestPolicy").path("walkingPace").asText()))
				.isInstanceOf(AssertionError.class);
		});
	}

	@Test
	@DisplayName("a status or machine-code mapping mutation makes the parity assertion red")
	void rejectsErrorMappingMutation() throws Exception {
		withRuntime(runtime -> {
			JsonNode errorCatalog = runtime.json().readTree(ERROR_CATALOG.toFile());
			JsonNode errorDisposition = runtime.json().readTree(ERROR_DISPOSITION.toFile());

			assertThatThrownBy(() -> assertErrorMapping(errorCatalog, errorDisposition,
				"searchJourneys", 500, "ROUTE_SESSION_REQUIRED"))
				.isInstanceOf(AssertionError.class);
		});
	}

	@Test
	@DisplayName("a wrong nested scalar type makes the recursive response-schema assertion red")
	void rejectsWrongNestedScalarTypeMutation() throws Exception {
		withRuntime(runtime -> {
			when(runtime.sessionService().authorize("session-token"))
				.thenReturn(new AuthorizedSession("journey:v3", NOW.plusSeconds(600)));
			when(runtime.deadlineExecutor().execute(any())).thenReturn(new Completed(success()));
			MvcResult search = runtime.mockMvc().perform(post("/api/v3/journeys/search")
					.header(HttpHeaders.AUTHORIZATION, "Bearer session-token")
					.contentType(MediaType.APPLICATION_JSON)
					.content(validSearchRequest()))
				.andExpect(status().isOk())
				.andReturn();
			JsonNode mutated = body(runtime.json(), search).deepCopy();
			((ObjectNode) mutated.path("journeys").get(0).path("accessibility")).put("stairFree", "true");

			assertThatThrownBy(() -> assertResponseSchema(openApi(), "searchJourneys", 200, mutated))
				.isInstanceOf(AssertionError.class);
		});
	}

	@Test
	@DisplayName("a non-JSON response media type makes the response-media assertion red")
	void rejectsNonJsonResponseMediaTypeMutation() {
		assertThatThrownBy(() -> assertJsonMediaType(MediaType.TEXT_PLAIN_VALUE))
			.isInstanceOf(AssertionError.class);
	}

	private static void assertError(
		Map<String, Object> openApi,
		JsonNode errorCatalog,
		JsonNode errorDisposition,
		String operation,
		MvcResult result,
		ObjectMapper json
	) throws Exception {
		JsonNode payload = body(json, result);
		assertResponseContract(openApi, operation, result, json);
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

	private static void assertResponseContract(
		Map<String, Object> openApi,
		String operationId,
		MvcResult result,
		ObjectMapper json
	) throws Exception {
		assertJsonMediaType(result.getResponse().getContentType());
		assertResponseSchema(openApi, operationId, result.getResponse().getStatus(), body(json, result));
	}

	private static void assertJsonMediaType(String contentType) {
		assertThat(contentType).isNotBlank();
		assertThat(MediaType.parseMediaType(contentType).isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
	}

	private static void assertResponseSchema(
		Map<String, Object> openApi,
		String operationId,
		int httpStatus,
		JsonNode payload
	) {
		Map<String, Object> response = map(map(operation(openApi, operationId).get("responses"))
			.get(String.valueOf(httpStatus)));
		Map<String, Object> json = map(map(response.get("content")).get("application/json"));
		assertSchema(openApi, map(json.get("schema")), payload, "$response");
	}

	private static void assertSchema(
		Map<String, Object> openApi,
		Map<String, Object> declaredSchema,
		JsonNode value,
		String path
	) {
		Map<String, Object> schema = resolveSchema(openApi, declaredSchema);
		if (value.isNull()) {
			assertThat(schema.get("nullable")).as(path + " nullable").isEqualTo(true);
			return;
		}
		if (schema.containsKey("oneOf")) {
			List<AssertionError> failures = new ArrayList<>();
			for (Object option : list(schema.get("oneOf"))) {
				try {
					assertSchema(openApi, map(option), value, path);
					return;
				} catch (AssertionError failure) {
					failures.add(failure);
				}
			}
			throw new AssertionError(path + " matches no oneOf branch", failures.get(0));
		}

		Object type = schema.get("type");
		assertThat(type).as(path + " schema type").isInstanceOf(String.class);
		switch ((String) type) {
			case "object" -> assertObjectSchema(openApi, schema, value, path);
			case "array" -> assertArraySchema(openApi, schema, value, path);
			case "string" -> assertStringSchema(schema, value, path);
			case "integer" -> assertIntegerSchema(schema, value, path);
			case "boolean" -> assertThat(value.isBoolean()).as(path + " boolean").isTrue();
			default -> throw new AssertionError("unsupported OpenAPI scalar type at " + path + ": " + type);
		}
	}

	private static void assertObjectSchema(
		Map<String, Object> openApi,
		Map<String, Object> schema,
		JsonNode value,
		String path
	) {
		assertThat(value.isObject()).as(path + " object").isTrue();
		Map<String, Object> properties = map(schema.get("properties"));
		Set<String> actual = fieldNames(value);
		assertThat(actual).containsAll(strings(schema.get("required")));
		if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
			assertThat(actual).containsExactlyInAnyOrderElementsOf(properties.keySet());
		}
		for (String property : actual) {
			assertThat(properties).as(path + "." + property + " declared").containsKey(property);
			assertSchema(openApi, map(properties.get(property)), value.path(property), path + "." + property);
		}
	}

	private static void assertArraySchema(
		Map<String, Object> openApi,
		Map<String, Object> schema,
		JsonNode value,
		String path
	) {
		assertThat(value.isArray()).as(path + " array").isTrue();
		if (schema.containsKey("minItems")) assertThat(value.size()).isGreaterThanOrEqualTo((Integer) schema.get("minItems"));
		if (schema.containsKey("maxItems")) assertThat(value.size()).isLessThanOrEqualTo((Integer) schema.get("maxItems"));
		for (int index = 0; index < value.size(); index++) {
			assertSchema(openApi, map(schema.get("items")), value.get(index), path + "[" + index + "]");
		}
	}

	private static void assertStringSchema(Map<String, Object> schema, JsonNode value, String path) {
		assertThat(value.isTextual()).as(path + " string").isTrue();
		String actual = value.textValue();
		if (schema.containsKey("minLength")) assertThat(actual.length()).isGreaterThanOrEqualTo((Integer) schema.get("minLength"));
		if (schema.containsKey("maxLength")) assertThat(actual.length()).isLessThanOrEqualTo((Integer) schema.get("maxLength"));
		if (schema.containsKey("pattern")) assertThat(actual).matches((String) schema.get("pattern"));
		if (schema.containsKey("enum")) assertThat(strings(schema.get("enum"))).contains(actual);
		if ("date-time".equals(schema.get("format"))) assertThatCodePointDateTime(actual, path);
		if ("date".equals(schema.get("format"))) assertThatCodePointDate(actual, path);
	}

	private static void assertIntegerSchema(Map<String, Object> schema, JsonNode value, String path) {
		assertThat(value.isIntegralNumber()).as(path + " integer").isTrue();
		long actual = value.longValue();
		if (schema.containsKey("minimum")) assertThat(actual).isGreaterThanOrEqualTo(((Number) schema.get("minimum")).longValue());
		if (schema.containsKey("maximum")) assertThat(actual).isLessThanOrEqualTo(((Number) schema.get("maximum")).longValue());
	}

	private static void assertThatCodePointDateTime(String actual, String path) {
		try {
			java.time.OffsetDateTime.parse(actual);
		} catch (java.time.format.DateTimeParseException exception) {
			throw new AssertionError(path + " date-time", exception);
		}
	}

	private static void assertThatCodePointDate(String actual, String path) {
		try {
			LocalDate.parse(actual);
		} catch (java.time.format.DateTimeParseException exception) {
			throw new AssertionError(path + " date", exception);
		}
	}

	private static void assertEnum(Map<String, Object> openApi, String schemaName, String propertyName, String actual) {
		Map<String, Object> value = propertyName == null
			? schema(openApi, schemaName)
			: resolveSchema(openApi, property(openApi, schemaName, propertyName));
		assertThat(strings(value.get("enum"))).contains(actual);
	}

	private static JsonNode body(ObjectMapper json, MvcResult result) throws Exception {
		return json.readTree(result.getResponse().getContentAsByteArray());
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

	private void withRuntime(RuntimeAssertion assertion) {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			try {
				assertion.verify(new Runtime(
					context.getBean(JourneySessionService.class),
					context.getBean(JourneyApplicationDeadlineExecutor.class),
					context.getBean(ObjectMapper.class),
					MockMvcBuilders.webAppContextSetup(context).build()
				));
			} catch (Exception exception) {
				throw new AssertionError("runtime contract assertion failed", exception);
			}
		});
	}

	@FunctionalInterface
	private interface RuntimeAssertion {
		void verify(Runtime runtime) throws Exception;
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class RuntimeConfiguration {

		@Bean
		JourneySessionService journeySessionService() {
			return mock(JourneySessionService.class);
		}

		@Bean
		JourneyApplicationDeadlineExecutor journeyApplicationDeadlineExecutor() {
			return mock(JourneyApplicationDeadlineExecutor.class);
		}

		@Bean
		JourneySessionController journeySessionController(JourneySessionService sessionService) {
			return new JourneySessionController(sessionService);
		}

		@Bean
		JourneySearchController journeySearchController(
			JourneySessionService sessionService,
			JourneyApplicationDeadlineExecutor deadlineExecutor
		) {
			return new JourneySearchController(sessionService, deadlineExecutor);
		}

		@Bean
		JourneySessionExceptionHandler journeySessionExceptionHandler() {
			return new JourneySessionExceptionHandler(Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom(new byte[] {1, 2, 3, 4}));
		}

		@Bean
		JourneySearchExceptionHandler journeySearchExceptionHandler() {
			return new JourneySearchExceptionHandler(Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom(new byte[] {5, 6, 7, 8}));
		}
	}

	private record Runtime(
		JourneySessionService sessionService,
		JourneyApplicationDeadlineExecutor deadlineExecutor,
		ObjectMapper json,
		MockMvc mockMvc
	) {
	}
}

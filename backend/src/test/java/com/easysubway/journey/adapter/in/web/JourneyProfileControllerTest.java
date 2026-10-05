package com.easysubway.journey.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import com.easysubway.journey.analytics.JourneySearchRecordStore;
import com.easysubway.journey.analytics.JourneySearchRecorder;
import com.easysubway.journey.application.JourneyProfileDeadlineExecutor;
import com.easysubway.journey.application.JourneyProfileResourcePolicy;
import com.easysubway.journey.application.JourneyProfileExecutionResult;
import com.easysubway.journey.application.JourneyProfileRaptorPort;
import com.easysubway.journey.application.JourneyRaptorQuery;
import com.easysubway.journey.application.JourneySessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

class JourneyProfileControllerTest {
	private static final String PATH = "/api/v3/journeys/profile";
	private static final String REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
	private static final String ARRIVE_BY = """
		{"kind":"ARRIVE_BY","earliestReadyAt":"2026-09-01T00:00:00Z",
		"arrivalDeadline":"2026-09-01T00:10:00Z"}
		""";
	private final JourneySessionService sessions = mock(JourneySessionService.class);
	private final JourneyProfileDeadlineExecutor executor = mock(JourneyProfileDeadlineExecutor.class);
	private final JourneyProfileResourcePolicy policy = JourneyProfileResponseMapperTest.policy();
	private final JourneySearchRecordStore recordStore = mock(JourneySearchRecordStore.class);
	private final JourneySearchRecorder recorder = new JourneySearchRecorder(
		recordStore, Runnable::run, java.time.Clock.fixed(java.time.Instant.parse("2026-09-01T00:00:00Z"), java.time.ZoneOffset.UTC),
		new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

	@Test
	void rejectsMissingBearerMalformedAndOversizedBodiesBeforeExecution() throws Exception {
		var mvc = mvc(4096);
		mvc.perform(post(PATH).content("{}"))
			.andExpect(status().isUnauthorized())
			.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
		mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer session").content("{}"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TEMPORAL_QUERY"));
		var body = request(ARRIVE_BY);
		mvc(body.getBytes(StandardCharsets.UTF_8).length - 1).perform(post(PATH)
			.header(HttpHeaders.AUTHORIZATION, "Bearer session").content(body))
			.andExpect(status().isBadRequest());
		verifyNoInteractions(sessions, executor);
	}

	@Test
	void chargesEachTemporalModeOnceAndPreservesTheTypedFailure() throws Exception {
		var modes = List.of(
			"{\"kind\":\"DEPART_BETWEEN\",\"earliestReadyAt\":\"2026-09-01T00:00:00Z\",\"latestReadyAt\":\"2026-09-01T00:01:00Z\"}",
			ARRIVE_BY, "{\"kind\":\"LAST_CONNECTION\",\"serviceDate\":\"2026-09-01\"}");
		when(executor.execute(any(), same(policy))).thenReturn(new JourneyProfileDeadlineExecutor.Completed(
			new JourneyProfileExecutionResult.Failure(JourneyProfileExecutionResult.Reason.ACTIVE_SNAPSHOT_STALE)));
		for (var temporal : modes) {
			var body = request(temporal);
			var query = JourneyProfileRequestDecoder.decode(body.getBytes(StandardCharsets.UTF_8), () -> false);
			mvc(4096).perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer session").content(body))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("ROUTING_BUNDLE_STALE"))
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));
			verify(sessions).authorize("session", policy.costUnitsFor(query.temporalQuery()));
			verify(executor).execute(any(), same(policy));
			clearInvocations(sessions, executor);
		}
	}

	@Test
	void publishesNativeSuccessWithAnIndependentServerQueryId() throws Exception {
		when(executor.execute(any(), same(policy))).thenAnswer(invocation -> {
			JourneyRaptorQuery query = invocation.getArgument(0);
			var plan = new JourneyProfileRaptorPort.ArriveByPlan((JourneyRaptorQuery.ArriveBy) query.temporalQuery(),
				new JourneyProfileRaptorPort.ReversePlan.Found(List.of(JourneyProfileResponseMapperTest.itinerary(true))));
			return new JourneyProfileDeadlineExecutor.Completed(JourneyProfileResponseMapperTest.success(query, plan));
		});
		String body = request(ARRIVE_BY);
		var response = mvc(body.getBytes(StandardCharsets.UTF_8).length).perform(post(PATH)
			.header(HttpHeaders.AUTHORIZATION, "Bearer session").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isOk()).andExpect(jsonPath("$.requestId").value(REQUEST_ID))
			.andExpect(jsonPath("$.summary.kind").value("ARRIVE_BY"))
			.andExpect(jsonPath("$.journeys[0].journey.timeSource").value("TIMETABLE"))
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store")).andReturn();
		String queryId = new ObjectMapper().readTree(response.getResponse().getContentAsByteArray())
			.path("queryId").asText();
		assertThat(queryId).isNotBlank().isNotEqualTo(REQUEST_ID);
		assertThat(java.util.UUID.fromString(queryId).toString()).isEqualTo(queryId);
	}

	@Test
	void keepsTimeoutAndUnexpectedPlannerFailureDistinct() throws Exception {
		when(executor.execute(any(), same(policy))).thenReturn(new JourneyProfileDeadlineExecutor.TimedOut());
		mvc(4096).perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer session").content(request(ARRIVE_BY)))
			.andExpect(status().isGatewayTimeout()).andExpect(jsonPath("$.code").value("JOURNEY_PROFILE_TIMEOUT"));

		// RAPTOR_FAILED maps to ROUTE_SERVICE_UNAVAILABLE 503
		when(executor.execute(any(), same(policy))).thenReturn(new JourneyProfileDeadlineExecutor.Completed(
			new JourneyProfileExecutionResult.Failure(JourneyProfileExecutionResult.Reason.RAPTOR_FAILED)));
		mvc(4096).perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer session").content(request(ARRIVE_BY)))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("ROUTE_SERVICE_UNAVAILABLE"))
			.andExpect(jsonPath("$.contractVersion").value("JOURNEY_ERROR_V1"));

		// CANCELLED maps to ROUTE_SERVICE_UNAVAILABLE 503
		when(executor.execute(any(), same(policy))).thenReturn(new JourneyProfileDeadlineExecutor.Completed(
			new JourneyProfileExecutionResult.Failure(JourneyProfileExecutionResult.Reason.CANCELLED)));
		mvc(4096).perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer session").content(request(ARRIVE_BY)))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("ROUTE_SERVICE_UNAVAILABLE"))
			.andExpect(jsonPath("$.contractVersion").value("JOURNEY_ERROR_V1"));

		// Unexpected runtime exception during executor.execute maps to ROUTE_SERVICE_UNAVAILABLE 503
		when(executor.execute(any(), same(policy))).thenThrow(new RuntimeException("Simulated executor crash"));
		mvc(4096).perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer session").content(request(ARRIVE_BY)))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("ROUTE_SERVICE_UNAVAILABLE"))
			.andExpect(jsonPath("$.contractVersion").value("JOURNEY_ERROR_V1"));

		// null outcome maps to ROUTE_SERVICE_UNAVAILABLE 503
		when(executor.execute(any(), same(policy))).thenReturn(null);
		mvc(4096).perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer session").content(request(ARRIVE_BY)))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("ROUTE_SERVICE_UNAVAILABLE"))
			.andExpect(jsonPath("$.contractVersion").value("JOURNEY_ERROR_V1"));
	}

	@Test
	void recordsProfileKindOutcomeAndEngineVersion() throws Exception {
		when(executor.execute(any(), same(policy))).thenAnswer(invocation -> {
			JourneyRaptorQuery query = invocation.getArgument(0);
			var plan = new JourneyProfileRaptorPort.ArriveByPlan((JourneyRaptorQuery.ArriveBy) query.temporalQuery(),
				new JourneyProfileRaptorPort.ReversePlan.Found(List.of(JourneyProfileResponseMapperTest.itinerary(true))));
			return new JourneyProfileDeadlineExecutor.Completed(JourneyProfileResponseMapperTest.success(query, plan));
		});
		String body = request(ARRIVE_BY);
		mvc(4096).perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer session").content(body))
			.andExpect(status().isOk());

		var saved = org.mockito.ArgumentCaptor.forClass(com.easysubway.journey.analytics.JourneySearchRecord.class);
		verify(recordStore).save(saved.capture());
		assertThat(saved.getValue()).satisfies(record -> {
			assertThat(record.kind()).isEqualTo(com.easysubway.journey.analytics.JourneySearchKind.ARRIVE_BY);
			assertThat(record.outcome()).isEqualTo(com.easysubway.journey.analytics.JourneySearchOutcome.FOUND);
			assertThat(record.engineVersion()).isEqualTo(
				com.easysubway.journey.application.JourneyRaptorPruningInventoryV1.REVERSE_RANGE_RAPTOR.algorithmSuiteId()
					+ "/" + com.easysubway.journey.application.JourneyRaptorPruningInventoryV1.REVERSE_RANGE_RAPTOR.queryAlgorithmId()
					+ "/" + com.easysubway.journey.application.JourneyRaptorPruningInventoryV1.REVERSE_RANGE_RAPTOR.semanticVersion());
			assertThat(record.engineVersion()).doesNotContain("UNKNOWN");
			assertThat(record.mobilityProfile()).isEqualTo("STEP_FREE");
			assertThat(record.stairFreeStatus()).isEqualTo("UNKNOWN");
			assertThat(record.alternativeCategories()).isNotEmpty();
		});
	}

	@Test
	void recordsProfileFailureClassifications() throws Exception {
		var cases = List.of(
			new Object[] {JourneyProfileExecutionResult.Reason.TEMPORAL_QUERY_TOO_COMPLEX, 422, "TOO_COMPLEX"},
			new Object[] {JourneyProfileExecutionResult.Reason.NO_ROUTE_ARRIVING_BY_DEADLINE, 422, "NO_ROUTE"},
			new Object[] {JourneyProfileExecutionResult.Reason.TEMPORAL_WINDOW_TOO_LARGE, 400, "REJECTED"},
			new Object[] {JourneyProfileExecutionResult.Reason.ACTIVE_SNAPSHOT_STALE, 503, "UNAVAILABLE"});
		for (Object[] c : cases) {
			clearInvocations(recordStore);
			when(executor.execute(any(), same(policy))).thenReturn(new JourneyProfileDeadlineExecutor.Completed(
				new JourneyProfileExecutionResult.Failure((JourneyProfileExecutionResult.Reason) c[0])));
			mvc(4096).perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer session").content(request(ARRIVE_BY)))
				.andExpect(status().is((Integer) c[1]));
			var saved = org.mockito.ArgumentCaptor.forClass(com.easysubway.journey.analytics.JourneySearchRecord.class);
			verify(recordStore).save(saved.capture());
			assertThat(saved.getValue().outcome().name()).isEqualTo(c[2]);
			assertThat(saved.getValue().httpStatus()).isEqualTo((Integer) c[1]);
		}
	}

	@Test
	void recordsProfileTimeoutAndKeepsResponseWhenRecordingFails() throws Exception {
		when(executor.execute(any(), same(policy))).thenReturn(new JourneyProfileDeadlineExecutor.TimedOut());
		mvc(4096).perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer session").content(request(ARRIVE_BY)))
			.andExpect(status().isGatewayTimeout());
		var saved = org.mockito.ArgumentCaptor.forClass(com.easysubway.journey.analytics.JourneySearchRecord.class);
		verify(recordStore).save(saved.capture());
		assertThat(saved.getValue().outcome()).isEqualTo(com.easysubway.journey.analytics.JourneySearchOutcome.TIMEOUT);

		org.mockito.Mockito.doThrow(new IllegalStateException("db down")).when(recordStore).save(any());
		mvc(4096).perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer session").content(request(ARRIVE_BY)))
			.andExpect(status().isGatewayTimeout()).andExpect(jsonPath("$.code").value("JOURNEY_PROFILE_TIMEOUT"));
		assertThat(recorder.failureCount()).isEqualTo(1);
	}

	@Test
	void recordsMappingFailureAsUnavailableWithTheEngineVersion() throws Exception {
		when(executor.execute(any(), same(policy))).thenAnswer(invocation -> {
			JourneyRaptorQuery query = invocation.getArgument(0);
			var plan = new JourneyProfileRaptorPort.ArriveByPlan((JourneyRaptorQuery.ArriveBy) query.temporalQuery(),
				new JourneyProfileRaptorPort.ReversePlan.Found(List.of(JourneyProfileResponseMapperTest.itinerary(false))));
			return new JourneyProfileDeadlineExecutor.Completed(JourneyProfileResponseMapperTest.success(query, plan));
		});
		mvc(4096).perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer session").content(request(ARRIVE_BY)))
			.andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("ROUTE_SERVICE_UNAVAILABLE"));

		var saved = org.mockito.ArgumentCaptor.forClass(com.easysubway.journey.analytics.JourneySearchRecord.class);
		verify(recordStore).save(saved.capture());
		assertThat(saved.getValue().outcome()).isEqualTo(com.easysubway.journey.analytics.JourneySearchOutcome.UNAVAILABLE);
		assertThat(saved.getValue().engineVersion()).contains("REVERSE_RANGE_RAPTOR");
	}

	@Test
	void doesNotRecordInvalidTemporalQueries() throws Exception {
		mvc(4096).perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer session").content("{}"))
			.andExpect(status().isBadRequest());
		verifyNoInteractions(recordStore);
	}

	private MockMvc mvc(int maxBytes) {
		return MockMvcBuilders.standaloneSetup(new JourneyProfileController(sessions, executor, policy, maxBytes, recorder))
			.setControllerAdvice(new JourneySearchExceptionHandler()).build();
	}

	private static String request(String temporal) {
		return """
			{"requestId":"%s","originStationId":"origin","destinationStationId":"destination",
			"temporalQuery":%s,"timePolicy":"TIMETABLE_REQUIRED","walkingPace":"STANDARD",
			"mobilityProfile":"STEP_FREE","constraintMode":"REQUIRE_STEP_FREE","maxTransfers":1,"alternativeCount":1}
			""".formatted(REQUEST_ID, temporal);
	}

	@Test
	void requiresAPositiveBoundedRequestSize() {
		assertThatThrownBy(() -> new JourneyProfileController(
			mock(JourneySessionService.class), mock(JourneyProfileDeadlineExecutor.class),
			mock(JourneyProfileResourcePolicy.class), 0, recorder))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("maxRequestBytes must be positive");
	}

	@Test
	@DisplayName("@Value max-request-bytes 설정이 JourneyProfileController에 올바르게 바인딩된다")
	void bindsMaxRequestBytesConfigurationProperty() {
		var runner = new ApplicationContextRunner()
			.withBean(JourneySessionService.class, () -> mock(JourneySessionService.class))
			.withBean(JourneySearchRecorder.class, () -> recorder)
			.withBean(JourneyProfileDeadlineExecutor.class, () -> mock(JourneyProfileDeadlineExecutor.class))
			.withBean(JourneyProfileResourcePolicy.class, () -> policy)
			.withUserConfiguration(ProfileWebConfiguration.class)
			.withPropertyValues("easysubway.journey-v3.search-web.enabled=true");

		// 기본값 검증 (설정 미제공 시 DEFAULT_MAX_REQUEST_BYTES: 65536)
		runner.run(context -> {
			assertThat(context).hasSingleBean(JourneyProfileController.class);
			var controller = context.getBean(JourneyProfileController.class);
			assertThat(ReflectionTestUtils.getField(controller, "maxRequestBytes"))
				.isEqualTo(JourneyProfileController.DEFAULT_MAX_REQUEST_BYTES);
		});

		// 명시적 프로퍼티 바인딩 검증
		runner.withPropertyValues("easysubway.journey.profile.max-request-bytes=32768").run(context -> {
			assertThat(context).hasSingleBean(JourneyProfileController.class);
			var controller = context.getBean(JourneyProfileController.class);
			assertThat(ReflectionTestUtils.getField(controller, "maxRequestBytes"))
				.isEqualTo(32_768);
		});

		// 0 이하 비정상 값 바인딩 시 context startup failure
		runner.withPropertyValues("easysubway.journey.profile.max-request-bytes=0").run(context ->
			assertThat(context.getStartupFailure())
				.hasRootCauseInstanceOf(IllegalArgumentException.class)
				.hasRootCauseMessage("maxRequestBytes must be positive"));
	}

	@TestConfiguration
	@Import({JourneyProfileController.class, JourneySearchExceptionHandler.class})
	static class ProfileWebConfiguration {
	}
}

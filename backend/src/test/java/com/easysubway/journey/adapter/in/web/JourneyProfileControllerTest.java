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
import org.junit.jupiter.api.Test;

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
		when(executor.execute(any(), same(policy))).thenReturn(new JourneyProfileDeadlineExecutor.Completed(
			new JourneyProfileExecutionResult.Failure(JourneyProfileExecutionResult.Reason.RAPTOR_FAILED)));
		var mvc = mvc(4096);
		assertThatThrownBy(() -> mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer session")
			.content(request(ARRIVE_BY))))
			.hasRootCauseInstanceOf(IllegalStateException.class);
	}

	private MockMvc mvc(int maxBytes) {
		return MockMvcBuilders.standaloneSetup(new JourneyProfileController(sessions, executor, policy, maxBytes))
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
			mock(JourneyProfileResourcePolicy.class), 0))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("maxRequestBytes must be positive");
	}
}

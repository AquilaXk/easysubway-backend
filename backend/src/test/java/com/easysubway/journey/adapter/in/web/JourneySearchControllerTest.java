package com.easysubway.journey.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.journey.application.JourneyApplicationService;
import com.easysubway.journey.application.JourneyCandidate;
import com.easysubway.journey.application.JourneyExecutionFailure;
import com.easysubway.journey.application.JourneyExecutionFailure.Reason;
import com.easysubway.journey.application.JourneyExecutionResult;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.journey.application.JourneySessionException;
import com.easysubway.journey.application.JourneySessionService;
import com.easysubway.journey.application.JourneySessionService.AuthorizedSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@DisplayName("Journey V3 authenticated search HTTP boundary")
class JourneySearchControllerTest {

	private static final String REQUEST_ID = "01K1Y000000000000000000000";
	private static final Instant NOW = Instant.parse("2026-08-12T00:00:00Z");
	private static final String SEARCH_WEB_ENABLED = "easysubway.journey-v3.search-web.enabled=true";
	private static final ObjectMapper JSON = new ObjectMapper();

	private JourneySessionService sessionService;
	private JourneyApplicationService applicationService;
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		sessionService = mock(JourneySessionService.class);
		applicationService = mock(JourneyApplicationService.class);
		mockMvc = MockMvcBuilders.standaloneSetup(new JourneySearchController(sessionService, applicationService))
			.setControllerAdvice(new JourneySearchExceptionHandler(
				Clock.fixed(NOW, ZoneOffset.UTC),
				new SecureRandom(new byte[] {1, 2, 3, 4})
			))
			.build();
	}

	@Test
	@DisplayName("bearer를 한 번 authorize하고 NOW command를 exact success JSON으로 투영한다")
	void authorizesAndExecutesNowRequestOnce() throws Exception {
		assertConditionalRegistration();
		allowSession();
		when(applicationService.execute(any())).thenReturn(success());

		MvcResult result = perform(validRequest("{\"mode\":\"NOW\"}"))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
			.andExpect(jsonPath("$.contractVersion").value("JOURNEY_SEARCH_V3"))
			.andExpect(jsonPath("$.requestId").value(REQUEST_ID))
			.andExpect(jsonPath("$.sourceIdentity.realtimeSnapshotId").value(org.hamcrest.Matchers.nullValue()))
			.andExpect(jsonPath("$.journeys[0].journeyId").value("journey-1"))
			.andReturn();

		assertThat(fields(result)).containsExactlyInAnyOrder(
			"contractVersion", "requestId", "queryId", "calculatedAt", "validUntil",
			"effectiveDepartureTime", "serviceDate", "serviceTimezone", "sourceIdentity",
			"requestPolicy", "journeys"
		);
		verify(sessionService).authorize("session-token");
		var request = ArgumentCaptor.forClass(JourneyRequest.class);
		verify(applicationService).execute(request.capture());
		assertThat(request.getValue()).satisfies(command -> {
			assertThat(command.requestId()).isEqualTo(REQUEST_ID);
			assertThat(command.originStationId()).isEqualTo("station-origin");
			assertThat(command.destinationStationId()).isEqualTo("station-destination");
			assertThat(command.departure()).isEqualTo(new JourneyRequest.Departure.Now());
			assertThat(command.timePolicy()).isEqualTo(JourneyRequest.TimePolicy.TIMETABLE_REQUIRED);
			assertThat(command.mobilityProfile()).isEqualTo(JourneyRequest.MobilityProfile.STEP_FREE);
			assertThat(command.constraintMode()).isEqualTo(JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE);
			assertThat(command.maxTransfers()).isEqualTo(2);
			assertThat(command.alternativeCount()).isEqualTo(1);
			assertThat(command.isCancelled()).isFalse();
		});
	}

	@Test
	@DisplayName("SCHEDULED offset date-time을 exact instant command로 변환한다")
	void decodesScheduledDeparture() throws Exception {
		allowSession();
		when(applicationService.execute(any())).thenReturn(success());

		perform(validRequest("{\"mode\":\"SCHEDULED\",\"requestedAt\":\"2026-08-12T09:01:00+09:00\"}"))
			.andExpect(status().isOk());

		var request = ArgumentCaptor.forClass(JourneyRequest.class);
		verify(applicationService).execute(request.capture());
		assertThat(request.getValue().departure()).isEqualTo(
			new JourneyRequest.Departure.Scheduled(Instant.parse("2026-08-12T00:01:00Z"))
		);
	}

	@Test
	@DisplayName("missing·malformed·rejected bearer는 application 호출 없이 exact 401이다")
	void rejectsUnauthorizedRequestsBeforeExecution() throws Exception {
		assertError(post("/api/v3/journeys/search")
			.contentType(MediaType.APPLICATION_JSON), 401, "ROUTE_SESSION_REQUIRED", false);
		for (String authorization : List.of("", "Basic session-token", "Bearer", "Bearer token extra")) {
			var request = post("/api/v3/journeys/search")
				.contentType(MediaType.APPLICATION_JSON)
				.content(validRequest("{\"mode\":\"NOW\"}"));
			if (!authorization.isEmpty()) request.header(HttpHeaders.AUTHORIZATION, authorization);
			assertError(request, 401, "ROUTE_SESSION_REQUIRED", false);
		}
		when(sessionService.authorize("rejected-token"))
			.thenThrow(new JourneySessionException(JourneySessionException.Kind.SESSION_REQUIRED));
		assertError(post("/api/v3/journeys/search")
			.header(HttpHeaders.AUTHORIZATION, "Bearer rejected-token")
			.contentType(MediaType.APPLICATION_JSON)
			.content(validRequest("{\"mode\":\"NOW\"}")), 401, "ROUTE_SESSION_REQUIRED", false);

		verify(sessionService).authorize("rejected-token");
		verifyNoInteractions(applicationService);
	}

	@Test
	@DisplayName("Bearer scheme은 대소문자와 무관하게 authorize한다")
	void acceptsCaseInsensitiveBearerScheme() throws Exception {
		allowSession();
		when(applicationService.execute(any())).thenReturn(success());

		mockMvc.perform(post("/api/v3/journeys/search")
			.header(HttpHeaders.AUTHORIZATION, "bEaReR session-token")
			.contentType(MediaType.APPLICATION_JSON)
			.content(validRequest("{\"mode\":\"NOW\"}")))
			.andExpect(status().isOk());

		verify(sessionService).authorize("session-token");
		verify(applicationService).execute(any());
	}

	@Test
	@DisplayName("401 session failure는 exact Bearer challenge를 포함한다")
	void challengesUnauthorizedClientWithBearerScheme() throws Exception {
		assertError(post("/api/v3/journeys/search")
			.contentType(MediaType.APPLICATION_JSON)
			.content(validRequest("{\"mode\":\"NOW\"}")), 401, "ROUTE_SESSION_REQUIRED", false);

		verifyNoInteractions(applicationService);
	}

	@Test
	@DisplayName("malformed·duplicate·trailing·extra request는 authorize 뒤 execute 없이 exact 400이다")
	void rejectsNonContractRequestsBeforeExecution() throws Exception {
		allowSession();
		for (String body : List.of(
			"",
			"{not-json",
			validRequest("{\"mode\":\"NOW\"}") + " {}",
			validRequest("{\"mode\":\"NOW\",\"requestedAt\":\"2026-08-12T00:01:00Z\"}"),
			validRequest("{\"mode\":\"SCHEDULED\"}"),
			validRequest("{\"mode\":\"NOW\",\"extra\":true}"),
			validRequest("{\"mode\":\"NOW\"}").replace("\"alternativeCount\":1", "\"alternativeCount\":1,\"extra\":true"),
			validRequest("{\"mode\":\"NOW\"}").replace("\"requestId\":", "\"requestId\":\"duplicate\",\"requestId\":"),
			validRequest("{\"mode\":\"NOW\"}").replace("\"timePolicy\":\"TIMETABLE_REQUIRED\"", "\"timePolicy\":\"UNKNOWN\""),
			validRequest("{\"mode\":\"NOW\"}").replace("\"maxTransfers\":2", "\"maxTransfers\":4"),
			validRequest("{\"mode\":\"NOW\"}").replace("\"mobilityProfile\":\"STEP_FREE\"", "\"mobilityProfile\":\"NO_STAIRS\"")
				.replace("\"constraintMode\":\"REQUIRE_STEP_FREE\"", "\"constraintMode\":\"NONE\"")
		)) {
			assertError(post("/api/v3/journeys/search")
				.header(HttpHeaders.AUTHORIZATION, "Bearer session-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body), 400, "INVALID_JOURNEY_REQUEST", false);
		}
		verify(sessionService, times(11)).authorize("session-token");
		verifyNoInteractions(applicationService);
	}

	@Test
	@DisplayName("current typed failures는 exact public status/code로 fail closed한다")
	void mapsCurrentTypedFailures() throws Exception {
		allowSession();
		for (FailureCase failure : List.of(
			new FailureCase(Reason.ACTIVE_SNAPSHOT_UNAVAILABLE, 503, "ROUTING_BUNDLE_UNAVAILABLE"),
			new FailureCase(Reason.ACTIVE_SNAPSHOT_STALE, 503, "ROUTING_BUNDLE_STALE"),
			new FailureCase(Reason.REALTIME_UNAVAILABLE, 503, "REALTIME_REQUIRED_UNAVAILABLE"),
			new FailureCase(Reason.REALTIME_STALE, 503, "REALTIME_REQUIRED_UNAVAILABLE"),
			new FailureCase(Reason.REALTIME_IDENTITY_MISMATCH, 503, "ROUTING_IDENTITY_MISMATCH"),
			new FailureCase(Reason.RAPTOR_FAILED, 503, "ROUTE_SERVICE_UNAVAILABLE"),
			new FailureCase(Reason.NO_ROUTE, 422, "ROUTE_NOT_FOUND"),
			new FailureCase(Reason.CANCELLED, 503, "ROUTE_SERVICE_UNAVAILABLE")
		)) {
			when(applicationService.execute(any())).thenReturn(new JourneyExecutionFailure(failure.reason()));
			assertError(post("/api/v3/journeys/search")
				.header(HttpHeaders.AUTHORIZATION, "Bearer session-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(validRequest("{\"mode\":\"NOW\"}")), failure.status(), failure.code(), true);
		}
		verify(sessionService, times(8)).authorize("session-token");
		verify(applicationService, times(8)).execute(any());
	}

	private org.springframework.test.web.servlet.ResultActions perform(String body) throws Exception {
		return mockMvc.perform(post("/api/v3/journeys/search")
			.header(HttpHeaders.AUTHORIZATION, "Bearer session-token")
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private void assertError(
		org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
		int httpStatus,
		String code,
		boolean preservesRequestId
	) throws Exception {
		var response = mockMvc.perform(request)
			.andExpect(status().is(httpStatus))
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
			.andExpect(jsonPath("$.contractVersion").value("JOURNEY_ERROR_V1"))
			.andExpect(jsonPath("$.requestId").value(preservesRequestId
				? org.hamcrest.Matchers.equalTo(REQUEST_ID)
				: org.hamcrest.Matchers.matchesPattern("^[0-7][0-9A-HJKMNP-TV-Z]{25}$")))
			.andExpect(jsonPath("$.code").value(code))
			.andExpect(jsonPath("$.retryable").value(false))
			.andExpect(jsonPath("$.occurredAt").value("2026-08-12T00:00:00Z"));
		if (httpStatus == 401) {
			response.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
		}
		MvcResult result = response
			.andReturn();
		assertThat(fields(result)).containsExactlyInAnyOrder(
			"contractVersion", "requestId", "code", "retryable", "occurredAt"
		);
	}

	private void allowSession() {
		when(sessionService.authorize("session-token"))
			.thenReturn(new AuthorizedSession("journey:v3", NOW.plusSeconds(600)));
	}

	private static String validRequest(String departure) {
		return """
			{
			  "requestId":"%s",
			  "originStationId":"station-origin",
			  "destinationStationId":"station-destination",
			  "departure":%s,
			  "timePolicy":"TIMETABLE_REQUIRED",
			  "mobilityProfile":"STEP_FREE",
			  "constraintMode":"REQUIRE_STEP_FREE",
			  "maxTransfers":2,
			  "alternativeCount":1
			}
			""".formatted(REQUEST_ID, departure);
	}

	private static JourneyExecutionResult.Success success() {
		Instant departure = Instant.parse("2026-08-12T00:01:00Z");
		Instant arrival = departure.plusSeconds(300);
		var candidate = new JourneyCandidate(
			"journey-1", departure, arrival, null, null, 300, 0, 0,
			JourneyCandidate.TimeSource.TIMETABLE,
			new JourneyCandidate.Accessibility(true, List.of("STEP_FREE_PATH")),
			List.of(new JourneyCandidate.Ride(
				"line-1", "trip-1", "station-destination", "station-origin", "station-destination",
				departure, arrival, null, null
			))
		);
		return new JourneyExecutionResult.Success(
			REQUEST_ID, "query-1", NOW, NOW.plusSeconds(600), departure,
			LocalDate.parse("2026-08-12"),
			new JourneyExecutionResult.SourceIdentity(
				"bundle-1", "a".repeat(64), "timetable-1", "accessibility-1", null
			),
			new JourneyExecutionResult.RequestPolicy(
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
				JourneyRequest.MobilityProfile.STEP_FREE,
				JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE,
				2,
				1
			),
			List.of(candidate)
		);
	}

	private static List<String> fields(MvcResult result) throws Exception {
		JsonNode body = JSON.readTree(result.getResponse().getContentAsByteArray());
		var fields = new ArrayList<String>();
		body.fieldNames().forEachRemaining(fields::add);
		return fields;
	}

	private static void assertConditionalRegistration() {
		var runner = new ApplicationContextRunner()
			.withBean(JourneySessionService.class, () -> mock(JourneySessionService.class))
			.withBean(JourneyApplicationService.class, () -> mock(JourneyApplicationService.class))
			.withUserConfiguration(SearchWebConfiguration.class);
		runner.run(context -> assertThat(context)
			.doesNotHaveBean(JourneySearchController.class)
			.doesNotHaveBean(JourneySearchExceptionHandler.class));
		runner.withPropertyValues(SEARCH_WEB_ENABLED).run(context -> assertThat(context)
			.hasSingleBean(JourneySearchController.class)
			.hasSingleBean(JourneySearchExceptionHandler.class));
	}

	private record FailureCase(Reason reason, int status, String code) {
	}

	@TestConfiguration
	@Import({JourneySearchController.class, JourneySearchExceptionHandler.class})
	static class SearchWebConfiguration {
	}
}

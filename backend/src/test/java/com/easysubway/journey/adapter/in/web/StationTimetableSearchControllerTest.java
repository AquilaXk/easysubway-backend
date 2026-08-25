package com.easysubway.journey.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.journey.application.JourneySessionException;
import com.easysubway.journey.application.JourneySessionService;
import com.easysubway.journey.application.JourneySessionService.AuthorizedSession;
import com.easysubway.journey.application.StationTimetableSearchService;
import com.easysubway.route.application.model.PlannerIdentity;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayNode;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteAccessData;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetableSnapshot;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.ServiceCalendar;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitRoute;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitStopTime;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitTrip;
import java.security.SecureRandom;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class StationTimetableSearchControllerTest {
	private static final Instant NOW = Instant.parse("2026-08-24T00:00:00Z");
	private JourneySessionService sessions;
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		sessions = mock(JourneySessionService.class);
		mockMvc = MockMvcBuilders.standaloneSetup(new StationTimetableSearchController(sessions, service()))
			.setControllerAdvice(new JourneySearchExceptionHandler(Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom()))
			.build();
	}

	@Test
	void consumesOneSharedSessionUseAndWritesSeoulOffsetDeparture() throws Exception {
		when(sessions.authorize("session-token")).thenReturn(new AuthorizedSession("journey:v3", NOW.plusSeconds(600)));
		mockMvc.perform(post(StationTimetableSearchController.PATH).header(HttpHeaders.AUTHORIZATION, "Bearer session-token")
			.contentType(MediaType.APPLICATION_JSON).content(request()))
			.andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
			.andExpect(jsonPath("$.directionGroups[0].departures[0].departureAt").value("2026-08-24T09:00:00+09:00"));
		verify(sessions, times(1)).authorize("session-token");
	}

	@Test
	void rejectsMissingOrExtraRequestWithoutAnonymousSuccess() throws Exception {
		mockMvc.perform(post(StationTimetableSearchController.PATH).contentType(MediaType.APPLICATION_JSON).content(request()))
			.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("ROUTE_SESSION_REQUIRED"));
		when(sessions.authorize("session-token")).thenReturn(new AuthorizedSession("journey:v3", NOW.plusSeconds(600)));
		mockMvc.perform(post(StationTimetableSearchController.PATH).header(HttpHeaders.AUTHORIZATION, "Bearer session-token")
			.contentType(MediaType.APPLICATION_JSON).content(request().replace("}", ",\"extra\":true}")))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_JOURNEY_REQUEST"));
	}

	@Test
	void mapsSharedSessionRateLimitWithoutCallingASeparateQuota() throws Exception {
		when(sessions.authorize("session-token")).thenThrow(new JourneySessionException(JourneySessionException.Kind.RATE_LIMITED));
		mockMvc.perform(post(StationTimetableSearchController.PATH).header(HttpHeaders.AUTHORIZATION, "Bearer session-token")
			.contentType(MediaType.APPLICATION_JSON).content(request()))
			.andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("ROUTE_RATE_LIMITED"));
		verify(sessions, times(1)).authorize("session-token");
	}

	@Test
	void rejectsNonJsonMediaTypeBeforeTimetableSuccess() throws Exception {
		when(sessions.authorize("session-token")).thenReturn(new AuthorizedSession("journey:v3", NOW.plusSeconds(600)));
		mockMvc.perform(post(StationTimetableSearchController.PATH).header(HttpHeaders.AUTHORIZATION, "Bearer session-token")
			.contentType(MediaType.TEXT_PLAIN).content(request()))
			.andExpect(status().isUnsupportedMediaType());
	}

	@Test
	void mapsEveryStationTimetableFailureToItsPublicStatusAndCode() throws Exception {
		for (var expected : List.of(
			new FailureExpectation(StationTimetableSearchService.Failure.INVALID_JOURNEY_REQUEST, 400),
			new FailureExpectation(StationTimetableSearchService.Failure.STATION_LINE_NOT_FOUND, 404),
			new FailureExpectation(StationTimetableSearchService.Failure.TIMETABLE_NOT_COVERED, 404),
			new FailureExpectation(StationTimetableSearchService.Failure.TIMETABLE_UNAVAILABLE, 503),
			new FailureExpectation(StationTimetableSearchService.Failure.TIMETABLE_STALE, 503),
			new FailureExpectation(StationTimetableSearchService.Failure.TIMETABLE_IDENTITY_MISMATCH, 503))) {
			StationTimetableSearchService failing = mock(StationTimetableSearchService.class);
			when(failing.search(any())).thenThrow(new StationTimetableSearchService.FailureException(expected.failure()));
			mockMvc = mvc(failing);
			when(sessions.authorize("session-token")).thenReturn(new AuthorizedSession("journey:v3", NOW.plusSeconds(600)));
			mockMvc.perform(post(StationTimetableSearchController.PATH).header(HttpHeaders.AUTHORIZATION, "Bearer session-token")
				.contentType(MediaType.APPLICATION_JSON).content(request()))
				.andExpect(status().is(expected.status()))
				.andExpect(jsonPath("$.code").value(expected.failure().name()));
		}
	}

	@Test
	void writesDayTypeAndNextDeparturesSelectorsInSuccessJson() throws Exception {
		when(sessions.authorize("session-token")).thenReturn(new AuthorizedSession("journey:v3", NOW.plusSeconds(600)));
		mockMvc.perform(post(StationTimetableSearchController.PATH).header(HttpHeaders.AUTHORIZATION, "Bearer session-token")
			.contentType(MediaType.APPLICATION_JSON).content(request("{\"kind\":\"DAY_TYPE\",\"dayType\":\"SATURDAY\",\"referenceDate\":\"2026-08-29\"}")))
			.andExpect(status().isOk()).andExpect(jsonPath("$.selector.kind").value("DAY_TYPE"))
			.andExpect(jsonPath("$.selector.dayType").value("SATURDAY"));
		mockMvc.perform(post(StationTimetableSearchController.PATH).header(HttpHeaders.AUTHORIZATION, "Bearer session-token")
			.contentType(MediaType.APPLICATION_JSON).content(request("{\"kind\":\"NEXT_DEPARTURES\",\"asOf\":\"2026-08-24T00:00:00Z\",\"horizonDays\":1}")))
			.andExpect(status().isOk()).andExpect(jsonPath("$.selector.kind").value("NEXT_DEPARTURES"))
			.andExpect(jsonPath("$.selector.horizonDays").value(1));
	}

	@Test
	void rejectsMalformedUnknownAndIncompleteSelectors() throws Exception {
		when(sessions.authorize("session-token")).thenReturn(new AuthorizedSession("journey:v3", NOW.plusSeconds(600)));
		for (String selector : List.of(
			"{}", "{\"kind\":\"UNKNOWN\"}", "{\"kind\":\"SERVICE_DATE\"}",
			"{\"kind\":\"SERVICE_DATE\",\"serviceDate\":1}",
			"{\"kind\":\"DAY_TYPE\",\"dayType\":\"WEEKDAY\"}",
			"{\"kind\":\"DAY_TYPE\",\"dayType\":\"UNKNOWN\",\"referenceDate\":\"2026-08-24\"}",
			"{\"kind\":\"NEXT_DEPARTURES\",\"asOf\":\"bad\",\"horizonDays\":1}",
			"{\"kind\":\"NEXT_DEPARTURES\",\"asOf\":\"2026-08-24T00:00:00Z\",\"horizonDays\":0}",
			"{\"kind\":\"NEXT_DEPARTURES\",\"asOf\":\"2026-08-24T00:00:00Z\",\"horizonDays\":1,\"extra\":true}")) {
			mockMvc.perform(post(StationTimetableSearchController.PATH).header(HttpHeaders.AUTHORIZATION, "Bearer session-token")
				.contentType(MediaType.APPLICATION_JSON).content(request(selector)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_JOURNEY_REQUEST"));
		}
	}

	@Test
	void rejectsMalformedTopLevelJsonAndBearerVariants() throws Exception {
		for (String authorization : List.of("Basic session-token", "Bearer", "Bearer ***")) {
			mockMvc.perform(post(StationTimetableSearchController.PATH).header(HttpHeaders.AUTHORIZATION, authorization)
				.contentType(MediaType.APPLICATION_JSON).content(request()))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("ROUTE_SESSION_REQUIRED"));
		}
		when(sessions.authorize("session-token")).thenReturn(new AuthorizedSession("journey:v3", NOW.plusSeconds(600)));
		for (String body : List.of("{}", "{\"stationId\":1,\"lineId\":\"line\",\"selector\":{}}", "{bad", request() + " true")) {
			mockMvc.perform(post(StationTimetableSearchController.PATH).header(HttpHeaders.AUTHORIZATION, "Bearer session-token")
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_JOURNEY_REQUEST"));
		}
	}

	@Test
	void coversExactFieldAndSelectorTypeBranchesAndReadFailure() throws Exception {
		when(sessions.authorize("session-token")).thenReturn(new AuthorizedSession("journey:v3", NOW.plusSeconds(600)));
		for (String body : List.of(
			"{\"stationId\":\"station\",\"lineId\":1,\"selector\":{\"kind\":\"SERVICE_DATE\",\"serviceDate\":\"2026-08-24\"}}",
			"{\"stationId\":\"station\",\"lineId\":\"line\",\"extra\":true}",
			request("null"), request("[]"), request("{\"kind\":1}"),
			request("{\"kind\":\"DAY_TYPE\",\"dayType\":1,\"referenceDate\":\"2026-08-24\"}"),
			request("{\"kind\":\"DAY_TYPE\",\"dayType\":\"WEEKDAY\",\"referenceDate\":1}"),
			request("{\"kind\":\"DAY_TYPE\",\"dayType\":\"WEEKDAY\",\"other\":\"2026-08-24\"}"),
			request("{\"kind\":\"NEXT_DEPARTURES\",\"asOf\":1,\"horizonDays\":1}"),
			request("{\"kind\":\"NEXT_DEPARTURES\",\"asOf\":\"2026-08-24T00:00:00Z\",\"horizonDays\":\"1\"}"),
			request("{\"kind\":\"NEXT_DEPARTURES\",\"asOf\":\"2026-08-24T00:00:00Z\",\"other\":1}"))) {
			mockMvc.perform(post(StationTimetableSearchController.PATH).header(HttpHeaders.AUTHORIZATION, "Bearer session-token")
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_JOURNEY_REQUEST"));
		}
		var servletRequest = mock(jakarta.servlet.http.HttpServletRequest.class);
		when(servletRequest.getInputStream()).thenThrow(new IOException("read failure"));
		assertThatThrownBy(() -> new StationTimetableSearchController(sessions, service()).search("Bearer session-token", servletRequest))
			.isInstanceOf(StationTimetableSearchController.StationTimetableSearchWebException.class);
	}

	@Test
	void privateJsonDefensesRejectJavaNullAndNullNode() {
		assertThat(invokeStatic("exact", new Class<?>[]{com.fasterxml.jackson.databind.JsonNode.class, Set.class}, null, Set.of())).isEqualTo(false);
		assertThat(invokeStatic("exact", new Class<?>[]{com.fasterxml.jackson.databind.JsonNode.class, Set.class}, com.fasterxml.jackson.databind.node.NullNode.getInstance(), Set.of())).isEqualTo(false);
		assertThatThrownBy(() -> invokeStatic("decodeSelector", new Class<?>[]{com.fasterxml.jackson.databind.JsonNode.class}, (Object) null))
			.isInstanceOf(StationTimetableSearchController.StationTimetableSearchWebException.class);
		assertThatThrownBy(() -> invokeStatic("decodeSelector", new Class<?>[]{com.fasterxml.jackson.databind.JsonNode.class}, com.fasterxml.jackson.databind.node.NullNode.getInstance()))
			.isInstanceOf(StationTimetableSearchController.StationTimetableSearchWebException.class);
	}

	private static String request() {
		return request("{\"kind\":\"SERVICE_DATE\",\"serviceDate\":\"2026-08-24\"}");
	}
	private static String request(String selector) { return "{\"stationId\":\"station\",\"lineId\":\"line\",\"selector\":" + selector + "}"; }
	private MockMvc mvc(StationTimetableSearchService timetableService) {
		return MockMvcBuilders.standaloneSetup(new StationTimetableSearchController(sessions, timetableService))
			.setControllerAdvice(new JourneySearchExceptionHandler(Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom()))
			.build();
	}
	private record FailureExpectation(StationTimetableSearchService.Failure failure, int status) { }
	private static Object invokeStatic(String name, Class<?>[] parameters, Object... arguments) {
		try {
			Method method = StationTimetableSearchController.class.getDeclaredMethod(name, parameters);
			method.setAccessible(true);
			return method.invoke(null, arguments);
		} catch (InvocationTargetException exception) {
			Throwable cause = exception.getCause();
			if (cause instanceof RuntimeException runtime) throw runtime;
			throw new AssertionError(cause);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError(exception);
		}
	}

	private static StationTimetableSearchService service() {
		var timetable = new RouteTimetable(List.of(new ServiceCalendar("weekday", true, true, true, true, true, true, true,
			LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "Asia/Seoul")), List.of(),
			List.of(new TransitRoute("route", "line", "L", "line", "direction", "Asia/Seoul")),
			List.of(new TransitTrip("trip", "route", "weekday", "headsign", "0", "SUBWAY", "LOCAL", null, 0)),
			List.of(new TransitStopTime("trip", 1, "station", "line", 32_400, 32_400, 0, 0)), List.of(), List.of(), null,
			new RouteAccessData(List.of(new PathwayNode("platform", "station", "line", "PLATFORM")), List.of(), List.of(), List.of()));
		var snapshot = new RouteTimetableSnapshot("cache", "artifact", new PlannerIdentity("a".repeat(64), "b".repeat(64), "c".repeat(64),
			"sha256:" + "d".repeat(64), "d".repeat(64), "e".repeat(64), "f".repeat(64)), NOW.plusSeconds(60), timetable);
		LoadRouteTimetablePort port = new LoadRouteTimetablePort() {
			@Override public RouteTimetable loadRouteTimetable() { return timetable; }
			@Override public RouteTimetableSnapshot loadStationTimetableSnapshot() { return snapshot; }
		};
		return new StationTimetableSearchService(port, Clock.fixed(NOW, ZoneOffset.UTC));
	}
}

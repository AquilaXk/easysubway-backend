package com.easysubway.journey.adapter.in.web;

import static org.mockito.Mockito.mock;
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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
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

	private static String request() {
		return "{\"stationId\":\"station\",\"lineId\":\"line\",\"selector\":{\"kind\":\"SERVICE_DATE\",\"serviceDate\":\"2026-08-24\"}}";
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

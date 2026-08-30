package com.easysubway.journey.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.journey.application.JourneyApplicationDeadlineExecutor;
import com.easysubway.journey.application.JourneyApplicationDeadlineExecutor.MeasuredCompleted;
import com.easysubway.journey.application.JourneyCandidate;
import com.easysubway.journey.application.JourneyExecutionResult;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.journey.application.JourneyRaptorPort;
import com.easysubway.journey.readiness.JourneyReadinessService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class JourneyBenchmarkObservationControllerTest {

	private JourneyApplicationDeadlineExecutor executor;
	private JourneyReadinessService readinessService;
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		executor = mock(JourneyApplicationDeadlineExecutor.class);
		readinessService = mock(JourneyReadinessService.class);
		mockMvc = MockMvcBuilders.standaloneSetup(new JourneyBenchmarkObservationController(executor, readinessService)).build();
	}

	@Test
	void acceptsOnlyTheClosedJourneyRequestAndReturnsTheRequestBoundObservation() throws Exception {
		when(executor.executeMeasured(any())).thenReturn(new MeasuredCompleted(success(), 11, 12));
		when(readinessService.active()).thenReturn(activeReadiness());

		mockMvc.perform(post(JourneyBenchmarkObservationController.PATH)
				.contentType(MediaType.APPLICATION_JSON)
				.content(validRequest()))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.requestId").value("01K1Y000000000000000000000"))
			.andExpect(jsonPath("$.routeBundleSha256").value("a".repeat(64)))
			.andExpect(jsonPath("$.bundleGeneration").value(1))
			.andExpect(jsonPath("$.serviceDay.serviceDate").value("2026-08-12"))
			.andExpect(jsonPath("$.boundaryObservation.status").value("OBSERVED"))
			.andExpect(jsonPath("$.boundaryObservation.providerCalls").value(0))
			.andExpect(jsonPath("$.boundaryObservation.cacheHits").value(0))
			.andExpect(jsonPath("$.boundaryObservation.staleArtifactUses").value(0))
			.andExpect(jsonPath("$.boundaryObservation.fallbackUses").value(0));
		verify(executor).executeMeasured(any());
	}

	@Test
	void rejectsUnknownRequestFieldsBeforeExecution() throws Exception {
		mockMvc.perform(post(JourneyBenchmarkObservationController.PATH)
				.contentType(MediaType.APPLICATION_JSON)
				.content(validRequest().replace("}", ",\"unexpected\":true}")))
			.andExpect(status().isBadRequest())
			.andExpect(header().string("Cache-Control", "no-store"));

		org.mockito.Mockito.verifyNoInteractions(executor);
	}

	private static String validRequest() {
		return """
			{"requestId":"01K1Y000000000000000000000","originStationId":"station-origin",
			"destinationStationId":"station-destination","departure":{"mode":"SCHEDULED",
			"requestedAt":"2026-08-12T00:01:00Z"},"timePolicy":"TIMETABLE_REQUIRED",
			"walkingPace":"STANDARD","mobilityProfile":"STANDARD","constraintMode":"NONE",
			"maxTransfers":1,"alternativeCount":1}
			""";
	}

	private static JourneyExecutionResult.Success success() {
		Instant departure = Instant.parse("2026-08-12T00:01:00Z");
		var candidate = new JourneyCandidate("journey-1", departure, departure.plusSeconds(300), null, null,
			300, 0, 0, JourneyCandidate.TimeSource.TIMETABLE,
			new JourneyCandidate.Accessibility(true, List.of("STEP_FREE_PATH")), List.of(
				new JourneyCandidate.Ride("line-1", "trip-1", "station-destination", "station-origin",
					"station-destination", departure, departure.plusSeconds(300), null, null)));
		return new JourneyExecutionResult.Success("01K1Y000000000000000000000", "query-1", departure,
			departure.plusSeconds(600), departure, LocalDate.parse("2026-08-12"), 1,
			new JourneyRaptorPort.ScanMetrics(1, 2, 3), new JourneyExecutionResult.SourceIdentity("bundle-1",
				"a".repeat(64), "timetable-1", "accessibility-1", null),
			new JourneyExecutionResult.RequestPolicy(JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
				JourneyRequest.WalkingPace.STANDARD, JourneyRequest.MobilityProfile.STANDARD,
				JourneyRequest.ConstraintMode.NONE, 1, 1), List.of(candidate),
			new JourneyExecutionResult.BoundaryObservation(
				JourneyExecutionResult.BoundaryObservation.Status.OBSERVED, 0L, 0L, 0L, 0L));
	}

	private static JourneyReadinessService.ActiveReadiness activeReadiness() {
		String sha = "a".repeat(64);
		return new JourneyReadinessService.ActiveReadiness(1, "journey-v3-active-readiness", "backend-a", sha,
			"sha256:" + sha, sha, sha, sha, "bundle-a", 1, 1, "Asia/Seoul", "03:00", 1,
			true, false, Instant.parse("2026-08-13T00:00:00Z"), Instant.parse("2026-08-12T00:00:00Z"), sha);
	}
}

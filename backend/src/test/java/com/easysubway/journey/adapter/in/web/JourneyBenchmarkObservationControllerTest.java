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
import com.easysubway.journey.application.JourneyApplicationDeadlineExecutor.TimedOut;
import com.easysubway.journey.application.JourneyCandidate;
import com.easysubway.journey.application.JourneyExecutionFailure;
import com.easysubway.journey.application.JourneyExecutionResult;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.journey.application.JourneyRaptorPort;
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
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		executor = mock(JourneyApplicationDeadlineExecutor.class);
		mockMvc = MockMvcBuilders.standaloneSetup(new JourneyBenchmarkObservationController(executor)).build();
	}

	@Test
	void acceptsOnlyTheClosedJourneyRequestAndReturnsTheRequestBoundObservation() throws Exception {
		when(executor.executeMeasured(any())).thenReturn(new MeasuredCompleted(success(), 11, 12));

		mockMvc.perform(post(JourneyBenchmarkObservationController.PATH)
				.contentType(MediaType.APPLICATION_JSON)
				.content(validRequest()))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.requestId").value("01K1Y000000000000000000000"))
			.andExpect(jsonPath("$.routeBundleSha256").value("a".repeat(64)))
			.andExpect(jsonPath("$.bundleGeneration").value(1))
			.andExpect(jsonPath("$.outcome.kind").value("SUCCESS"))
			.andExpect(jsonPath("$.outcome.transferCount").value(0))
			.andExpect(jsonPath("$.serviceDay.serviceDate").value("2026-08-12"))
			.andExpect(jsonPath("$.boundaryObservation.status").value("OBSERVED"))
			.andExpect(jsonPath("$.boundaryObservation.providerCalls").value(0))
			.andExpect(jsonPath("$.boundaryObservation.cacheHits").value(0))
			.andExpect(jsonPath("$.boundaryObservation.staleArtifactUses").value(0))
			.andExpect(jsonPath("$.boundaryObservation.fallbackUses").value(0))
			.andExpect(jsonPath("$.activeServingIdentity.status").value("OBSERVED"))
			.andExpect(jsonPath("$.activeServingIdentity.descriptorSha256").value("b".repeat(64)))
			.andExpect(jsonPath("$.activeServingIdentity.receiptSha256").value("c".repeat(64)))
			.andExpect(jsonPath("$.activeServingIdentity.deploymentIdentity")
				.value("sha256:" + "d".repeat(64)))
			.andExpect(jsonPath("$.activeServingIdentity.deploymentRevision").value("e".repeat(40)))
			.andExpect(jsonPath("$.activeServingIdentity.serviceDayCutoff").value("03:00"))
			.andExpect(jsonPath("$.activeReadiness.releaseTupleSha256").value("d".repeat(64)))
			.andExpect(jsonPath("$.activeReadiness.routeBundleManifestSha256").value("a".repeat(64)))
			.andExpect(jsonPath("$.activeReadiness.servingReady").value(true))
			.andExpect(jsonPath("$.activeReadiness.draining").value(false));
		verify(executor).executeMeasured(any());
	}

	@Test
	void exposesOnlyObservedNoRouteAsARequestBoundFailureOutcome() throws Exception {
		var executionObservation = success().executionObservation();
		when(executor.executeMeasured(any())).thenReturn(new MeasuredCompleted(
			new JourneyExecutionFailure(JourneyExecutionFailure.Reason.NO_ROUTE, executionObservation), 11, 12));

		mockMvc.perform(post(JourneyBenchmarkObservationController.PATH)
				.contentType(MediaType.APPLICATION_JSON)
				.content(validRequest()))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.outcome.kind").value("FAILURE"))
			.andExpect(jsonPath("$.outcome.reason").value("NO_ROUTE"));
	}

	@Test
	void keepsUnobservedNoRouteUnavailable() throws Exception {
		when(executor.executeMeasured(any())).thenReturn(new MeasuredCompleted(
			new JourneyExecutionFailure(JourneyExecutionFailure.Reason.NO_ROUTE), 11, 12));

		mockMvc.perform(post(JourneyBenchmarkObservationController.PATH)
				.contentType(MediaType.APPLICATION_JSON)
				.content(validRequest()))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.reason").value("UNOBSERVABLE"));
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

	@Test
	void rejectsInvalidDepartureBeforeExecution() throws Exception {
		mockMvc.perform(post(JourneyBenchmarkObservationController.PATH)
				.contentType(MediaType.APPLICATION_JSON)
				.content(validRequest().replace("\"mode\":\"SCHEDULED\",\n\"requestedAt\":\"2026-08-12T00:01:00Z\"", "\"mode\":\"SCHEDULED\"")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("INVALID_REQUEST"));

		org.mockito.Mockito.verifyNoInteractions(executor);
	}

	@Test
	void mapsTypedTimeoutAndExecutionFailureToNonSuccessResponses() throws Exception {
		when(executor.executeMeasured(any())).thenReturn(new TimedOut());

		mockMvc.perform(post(JourneyBenchmarkObservationController.PATH)
				.contentType(MediaType.APPLICATION_JSON)
				.content(validRequest()))
			.andExpect(status().isGatewayTimeout())
			.andExpect(jsonPath("$.reason").value("TIMEOUT"));

		when(executor.executeMeasured(any())).thenThrow(new IllegalStateException("measurement unavailable"));
		mockMvc.perform(post(JourneyBenchmarkObservationController.PATH)
				.contentType(MediaType.APPLICATION_JSON)
				.content(validRequest()))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.reason").value("UNAVAILABLE"));
	}

	@Test
	void rejectsRequestMismatchAndUnobservableMeasurement() throws Exception {
		when(executor.executeMeasured(any())).thenReturn(new MeasuredCompleted(success("01K1Y000000000000000000001"), 11, 12));

		mockMvc.perform(post(JourneyBenchmarkObservationController.PATH)
				.contentType(MediaType.APPLICATION_JSON)
				.content(validRequest()))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.reason").value("IDENTITY_MISMATCH"));

		when(executor.executeMeasured(any())).thenReturn(new MeasuredCompleted(unobservableSuccess(), 11, 12));

		mockMvc.perform(post(JourneyBenchmarkObservationController.PATH)
				.contentType(MediaType.APPLICATION_JSON)
				.content(validRequest()))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.reason").value("UNOBSERVABLE"));
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
		return success("01K1Y000000000000000000000");
	}

	private static JourneyExecutionResult.Success success(String requestId) {
		var activeServing = new JourneyExecutionResult.ActiveServingIdentity(
			JourneyExecutionResult.ActiveServingIdentity.Status.OBSERVED,
			"b".repeat(64), "c".repeat(64), "sha256:" + "d".repeat(64), "e".repeat(40), "03:00");
		var activeReadiness = activeReadiness();
		var identity = new com.easysubway.journey.application.ActiveJourneySnapshotPort.RequestExecutionIdentity(
			requestId, "a".repeat(64), 1, activeReadiness, activeServing);
		return success(requestId, JourneyExecutionResult.RequestMeasurement.observed(identity,
			JourneyExecutionResult.BoundaryObservation.observed(0, 0, 0, 0)));
	}

	private static JourneyExecutionResult.Success unobservableSuccess() {
		return success("01K1Y000000000000000000000",
			JourneyExecutionResult.RequestMeasurement.unobservable());
	}

	private static JourneyExecutionResult.Success success(
		String requestId,
		JourneyExecutionResult.RequestMeasurement requestMeasurement
	) {
		Instant departure = Instant.parse("2026-08-12T00:01:00Z");
		var candidate = new JourneyCandidate("journey-1", departure, departure.plusSeconds(300), null, null,
			300, 0, 0, JourneyCandidate.TimeSource.TIMETABLE,
			new JourneyCandidate.Accessibility(true, List.of("STEP_FREE_PATH")), List.of(
				new JourneyCandidate.Ride("line-1", "trip-1", "station-destination", "station-origin",
					"station-destination", departure, departure.plusSeconds(300), null, null)));
		return new JourneyExecutionResult.Success(requestId, "query-1", departure,
			departure.plusSeconds(600), departure, LocalDate.parse("2026-08-12"), 1,
			new JourneyRaptorPort.ScanMetrics(1, 2, 3), new JourneyExecutionResult.SourceIdentity("bundle-1",
				"a".repeat(64), "timetable-1", "accessibility-1", null),
			new JourneyExecutionResult.RequestPolicy(JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
				JourneyRequest.WalkingPace.STANDARD, JourneyRequest.MobilityProfile.STANDARD,
				JourneyRequest.ConstraintMode.NONE, 1, 1), List.of(candidate),
			JourneyExecutionResult.BoundaryObservation.observed(0, 0, 0, 0), requestMeasurement);
	}

	private static JourneyExecutionResult.ActiveReadinessIdentity activeReadiness() {
		return new JourneyExecutionResult.ActiveReadinessIdentity(
			1, "journey-v3-active-readiness", "backend-a", "d".repeat(64),
			"sha256:" + "f".repeat(64), "1".repeat(64), "2".repeat(64), "a".repeat(64),
			"bundle-1", 1, 1, "Asia/Seoul", "03:00", 1, true, false,
			Instant.parse("2026-08-12T01:00:00Z"), Instant.parse("2026-08-11T23:00:00Z"),
			"3".repeat(64));
	}
}

package com.easysubway.journey.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class JourneyProfileExecutionDispositionTest {

	@Test
	void mapsOnlyFailuresWithExactCurrentPublicProfileCodes() {
		var expected = Map.of(
			JourneyProfileExecutionResult.Reason.ACTIVE_SNAPSHOT_UNAVAILABLE,
			JourneyProfileExecutionDisposition.MachineCode.ROUTING_BUNDLE_UNAVAILABLE,
			JourneyProfileExecutionResult.Reason.ACTIVE_SNAPSHOT_STALE,
			JourneyProfileExecutionDisposition.MachineCode.ROUTING_BUNDLE_STALE,
			JourneyProfileExecutionResult.Reason.REALTIME_UNAVAILABLE,
			JourneyProfileExecutionDisposition.MachineCode.REALTIME_REQUIRED_UNAVAILABLE,
			JourneyProfileExecutionResult.Reason.TEMPORAL_QUERY_TOO_COMPLEX,
			JourneyProfileExecutionDisposition.MachineCode.TEMPORAL_QUERY_TOO_COMPLEX,
			JourneyProfileExecutionResult.Reason.RAPTOR_FRONTIER_CAPACITY_EXCEEDED,
			JourneyProfileExecutionDisposition.MachineCode.RAPTOR_FRONTIER_CAPACITY_EXCEEDED);

		expected.forEach((reason, machineCode) -> {
			var disposition = JourneyProfileExecutionDisposition.from(
				new JourneyProfileExecutionResult.Failure(reason));

			int status = machineCode == JourneyProfileExecutionDisposition.MachineCode.TEMPORAL_QUERY_TOO_COMPLEX
				? 422 : 503;
			assertThat(disposition).isEqualTo(new JourneyProfileExecutionDisposition.PublicFailure(status, machineCode, false));
		});
	}

	@Test
	void keepsCancellationAndUnclassifiedPlannerFailureOutOfThePublicErrorSurface() {
		assertThat(JourneyProfileExecutionDisposition.from(new JourneyProfileExecutionResult.Failure(
			JourneyProfileExecutionResult.Reason.CANCELLED)))
			.isEqualTo(new JourneyProfileExecutionDisposition.Cancelled());
		assertThat(JourneyProfileExecutionDisposition.from(new JourneyProfileExecutionResult.Failure(
			JourneyProfileExecutionResult.Reason.RAPTOR_FAILED)))
			.isEqualTo(new JourneyProfileExecutionDisposition.InternalFailure(
				JourneyProfileExecutionResult.Reason.RAPTOR_FAILED));
	}

	@Test
	void rejectsMismatchedPublicStatusAndMachineCode() {
		assertThatThrownBy(() -> new JourneyProfileExecutionDisposition.PublicFailure(
			422, JourneyProfileExecutionDisposition.MachineCode.ROUTING_BUNDLE_UNAVAILABLE, false))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("profile failure status must match machine code");
		assertThatThrownBy(() -> new JourneyProfileExecutionDisposition.PublicFailure(
			503, JourneyProfileExecutionDisposition.MachineCode.TEMPORAL_QUERY_TOO_COMPLEX, false))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("profile failure status must match machine code");
	}
}

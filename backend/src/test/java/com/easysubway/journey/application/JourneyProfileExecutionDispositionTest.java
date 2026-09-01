package com.easysubway.journey.application;

import static org.assertj.core.api.Assertions.assertThat;

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
			JourneyProfileExecutionDisposition.MachineCode.REALTIME_REQUIRED_UNAVAILABLE);

		expected.forEach((reason, machineCode) -> {
			var disposition = JourneyProfileExecutionDisposition.from(
				new JourneyProfileExecutionResult.Failure(reason));

			assertThat(disposition).isEqualTo(new JourneyProfileExecutionDisposition.PublicFailure(
				503, machineCode, false));
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
}

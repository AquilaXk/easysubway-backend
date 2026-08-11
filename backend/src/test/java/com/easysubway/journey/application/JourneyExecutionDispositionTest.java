package com.easysubway.journey.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class JourneyExecutionDispositionTest {

	@Test
	void mapsEveryPublicFailureReasonToItsClosedPublicDisposition() {
		assertPublicFailure(JourneyExecutionFailure.Reason.ACTIVE_SNAPSHOT_UNAVAILABLE, 503,
			JourneyExecutionDisposition.MachineCode.ROUTING_BUNDLE_UNAVAILABLE);
		assertPublicFailure(JourneyExecutionFailure.Reason.ACTIVE_SNAPSHOT_STALE, 503,
			JourneyExecutionDisposition.MachineCode.ROUTING_BUNDLE_STALE);
		assertPublicFailure(JourneyExecutionFailure.Reason.REALTIME_UNAVAILABLE, 503,
			JourneyExecutionDisposition.MachineCode.REALTIME_REQUIRED_UNAVAILABLE);
		assertPublicFailure(JourneyExecutionFailure.Reason.REALTIME_STALE, 503,
			JourneyExecutionDisposition.MachineCode.REALTIME_REQUIRED_UNAVAILABLE);
		assertPublicFailure(JourneyExecutionFailure.Reason.REALTIME_IDENTITY_MISMATCH, 503,
			JourneyExecutionDisposition.MachineCode.ROUTING_IDENTITY_MISMATCH);
		assertPublicFailure(JourneyExecutionFailure.Reason.RAPTOR_FAILED, 503,
			JourneyExecutionDisposition.MachineCode.ROUTE_SERVICE_UNAVAILABLE);
		assertPublicFailure(JourneyExecutionFailure.Reason.NO_ROUTE, 422,
			JourneyExecutionDisposition.MachineCode.ROUTE_NOT_FOUND);
	}

	@Test
	void mapsCancellationToInternalCancelledWithoutPublicFailureFields() {
		JourneyExecutionDisposition disposition = JourneyExecutionDisposition.from(
			new JourneyExecutionFailure(JourneyExecutionFailure.Reason.CANCELLED));

		assertThat(disposition).isInstanceOf(JourneyExecutionDisposition.Cancelled.class)
			.isNotInstanceOf(JourneyExecutionDisposition.PublicFailure.class);
	}

	@Test
	void rejectsNullFailureAndPublicFailureMachineCode() {
		assertThatThrownBy(() -> JourneyExecutionDisposition.from(null))
			.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new JourneyExecutionDisposition.PublicFailure(503, null, false))
			.isInstanceOf(NullPointerException.class);
	}

	@Test
	void rejectsPublicFailureValuesOutsideTheClosedContract() {
		assertThatThrownBy(() -> new JourneyExecutionDisposition.PublicFailure(503,
			JourneyExecutionDisposition.MachineCode.ROUTING_BUNDLE_UNAVAILABLE, true))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new JourneyExecutionDisposition.PublicFailure(503,
			JourneyExecutionDisposition.MachineCode.ROUTE_NOT_FOUND, false))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new JourneyExecutionDisposition.PublicFailure(422,
			JourneyExecutionDisposition.MachineCode.ROUTE_SERVICE_UNAVAILABLE, false))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void exposesNoCancellationDataThroughThePublicApi() {
		assertThat(JourneyExecutionDisposition.Cancelled.class.getRecordComponents()).isEmpty();
		assertThat(Arrays.stream(JourneyExecutionDisposition.Cancelled.class.getDeclaredFields())
			.filter(field -> !Modifier.isStatic(field.getModifiers())))
			.isEmpty();
		assertThat(Arrays.stream(JourneyExecutionDisposition.Cancelled.class.getMethods())
			.filter(method -> !Modifier.isStatic(method.getModifiers()))
			.filter(method -> method.getParameterCount() == 0)
			.map(method -> method.getName()))
			.doesNotContain("httpStatus", "machineCode", "retryable", "payload", "detail", "message", "identity");
	}

	private static void assertPublicFailure(JourneyExecutionFailure.Reason reason, int httpStatus,
		JourneyExecutionDisposition.MachineCode machineCode) {
		JourneyExecutionDisposition disposition = JourneyExecutionDisposition.from(new JourneyExecutionFailure(reason));

		assertThat(disposition).isEqualTo(new JourneyExecutionDisposition.PublicFailure(httpStatus, machineCode, false));
	}
}

package com.easysubway.journey.bundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.journey.application.JourneyExecutionResult;
import com.easysubway.journey.application.JourneyBoundaryObserver;
import com.easysubway.journey.application.JourneyRaptorRuntimeView;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RouteBundleActiveJourneySnapshotAdapterTest {

	private static final Instant NOW = Instant.parse("2026-08-12T02:30:00Z");
	private static final Instant ACTIVE_FROM = Instant.parse("2026-08-12T02:00:00Z");
	private static final Instant FRESH_UNTIL = Instant.parse("2026-08-12T04:00:00Z");
	private static final String MANIFEST_SHA = "a".repeat(64);
	private static final String TIMETABLE_SHA = "b".repeat(64);
	private static final String ACCESSIBILITY_SHA = "c".repeat(64);

	@Test
	void mapsOneExactActiveRegistrySnapshotToTheJourneyPort() {
		var clock = new CountingClock(NOW);
		var runtime = new TestRuntime(MANIFEST_SHA, 1);
		var registry = activeRegistry(clock, runtime);
		var adapter = new RouteBundleActiveJourneySnapshotAdapter(registry);

		var observer = new JourneyBoundaryObserver();
		var snapshot = adapter.requireActive(NOW, observer);
		observer.providerBypassedForTimetable();

		assertThat(snapshot.identity()).isEqualTo(MANIFEST_SHA + ":1");
		assertThat(snapshot.routeBundleId()).isEqualTo("capital-v1");
		assertThat(snapshot.routeBundleSha256()).isEqualTo(MANIFEST_SHA);
		assertThat(snapshot.timetableSnapshotId()).isEqualTo(TIMETABLE_SHA);
		assertThat(snapshot.accessibilitySnapshotId()).isEqualTo(ACCESSIBILITY_SHA);
		assertThat(snapshot.generation()).isEqualTo(1);
		assertThat(snapshot.runtimeView()).isSameAs(runtime);
		assertThat(snapshot.validUntil()).isEqualTo(FRESH_UNTIL);
		assertThat(snapshot.fresh()).isTrue();
		assertThat(observer.completeTimetable()).isEqualTo(JourneyExecutionResult.BoundaryObservation.unobservable());
		assertThat(clock.instantCalls()).isEqualTo(3);
	}

	@Test
	void usesInclusiveActiveFromAndExclusiveFreshUntilForTheEffectiveInstant() {
		var registry = activeRegistry(Clock.fixed(NOW, ZoneOffset.UTC), new TestRuntime(MANIFEST_SHA, 1));
		var adapter = new RouteBundleActiveJourneySnapshotAdapter(registry);

		assertThat(adapter.requireActive(ACTIVE_FROM).fresh()).isTrue();
		assertThat(adapter.requireActive(FRESH_UNTIL).fresh()).isFalse();
		assertThat(adapter.requireActive(ACTIVE_FROM.minusNanos(1)).fresh()).isFalse();
	}

	@Test
	void failsClosedWhenNoActiveSnapshotExists() {
		var adapter = new RouteBundleActiveJourneySnapshotAdapter(
			new RouteBundleActivationRegistry(Clock.fixed(NOW, ZoneOffset.UTC)));

		assertThatThrownBy(() -> adapter.requireActive(NOW))
			.isInstanceOf(RouteBundleActivationException.class)
			.extracting(error -> ((RouteBundleActivationException) error).reason())
			.isEqualTo(RouteBundleActivationException.Reason.BUNDLE_UNAVAILABLE);
	}

	@Test
	void failsClosedWhenTheActiveRuntimeIsNotAJourneyRaptorRuntime() {
		var registry = activeRegistry(Clock.fixed(NOW, ZoneOffset.UTC), new OtherRuntime());

		assertThatThrownBy(() -> new RouteBundleActiveJourneySnapshotAdapter(registry).requireActive(NOW))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("active route-bundle runtime is not a Journey RAPTOR runtime");
	}

	@Test
	void failsClosedWhenTheRuntimeDigestOrGenerationDoesNotMatchTheActiveSnapshot() {
		for (var runtime : new TestRuntime[] {
			new TestRuntime("d".repeat(64), 1),
			new TestRuntime(MANIFEST_SHA, 2)
		}) {
			var registry = activeRegistry(Clock.fixed(NOW, ZoneOffset.UTC), runtime);

			assertThatThrownBy(() -> new RouteBundleActiveJourneySnapshotAdapter(registry).requireActive(NOW))
				.isInstanceOf(IllegalArgumentException.class);
		}
	}

	private static RouteBundleActivationRegistry activeRegistry(Clock clock, RouteBundleRuntimeView runtime) {
		var registry = new RouteBundleActivationRegistry(clock);
		registry.stage(new VerifiedRouteBundleCandidate(
			identity(),
			new RouteBundleAdmissionEvidence(
				MANIFEST_SHA,
				"final-evidence",
				"promotion-evidence",
				"publication-receipt",
				"activation-request"),
			runtime,
			NOW.minusSeconds(1)), 0);
		registry.activate(MANIFEST_SHA, 0);
		return registry;
	}

	private static RouteBundleIdentity identity() {
		return new RouteBundleIdentity(
			1,
			"server-route-bundle",
			"capital-v1",
			1,
			"0".repeat(64),
			"1".repeat(64),
			"2".repeat(64),
			TIMETABLE_SHA,
			ACCESSIBILITY_SHA,
			"3".repeat(64),
			"4".repeat(64),
			"5".repeat(64),
			"Asia/Seoul",
			"2026-08-12T11:00:00.000+09:00",
			"2026-08-12T13:00:00.000+09:00",
			new RouteBundleIdentity.SchemaCompatibility(3, 3),
			"launch-key",
			new RouteBundleIdentity.Signature("rsa-sha256-server-route-bundle-v1", "AQID"));
	}

	private record TestRuntime(String routeBundleSha256, long generation)
		implements RouteBundleRuntimeView, JourneyRaptorRuntimeView {
	}

	private record OtherRuntime() implements RouteBundleRuntimeView {
	}

	private static final class CountingClock extends Clock {
		private final Instant instant;
		private int instantCalls;

		private CountingClock(Instant instant) {
			this.instant = instant;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			instantCalls++;
			return instant;
		}

		private int instantCalls() {
			return instantCalls;
		}
	}
}

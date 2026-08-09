package com.easysubway.journey.bundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class RouteBundleActivationRegistryTest {

	private static final Instant T0 = Instant.parse("2026-08-09T00:00:00Z");

	@Test
	void identityRequiresTheCompleteCurrentFixtureIdentity() {
		assertThatThrownBy(() -> identity("manifest", "UTC", "SHA-256", 3, T0, T0.plusSeconds(60)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> identity("MANIFEST", "Asia/Seoul", "SHA-256", 3, T0, T0.plusSeconds(60)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> identity("manifest", "Asia/Seoul", "sha-256", 3, T0, T0.plusSeconds(60)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> identity("manifest", "Asia/Seoul", "SHA-256", 2, T0, T0.plusSeconds(60)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> identity("manifest", "Asia/Seoul", "SHA-256", 3, T0, T0))
			.isInstanceOf(IllegalArgumentException.class);

		var identity = identity("manifest", "Asia/Seoul", "SHA-256", 3, T0, T0.plusSeconds(60));

		assertThat(identity.manifestSha256()).isEqualTo("m".repeat(64));
		assertThat(identity.payloadSha256()).isEqualTo("p".repeat(64));
		assertThat(identity.topologySha256()).isEqualTo("t".repeat(64));
		assertThat(identity.timetableSha256()).isEqualTo("i".repeat(64));
		assertThat(identity.accessibilitySha256()).isEqualTo("a".repeat(64));
		assertThat(identity.fareSha256()).isEqualTo("f".repeat(64));
		assertThat(identity.provenanceSha256()).isEqualTo("r".repeat(64));
		assertThat(identity.compatibilitySha256()).isEqualTo("c".repeat(64));
	}

	@Test
	void stageKeepsCandidateSeparateFromTheActiveSnapshot() {
		var registry = registryAt(T0);
		var candidate = candidate("a", T0.minusSeconds(1), T0.plusSeconds(60));

		registry.stage(candidate, 0);

		assertFailure(RouteBundleActivationException.Reason.BUNDLE_UNAVAILABLE, registry::activeSnapshot);
		var active = registry.activate(candidate.identity().manifestSha256(), 0);
		assertThat(active.generation()).isOne();
		assertThat(active.identity()).isEqualTo(candidate.identity());
		assertThat(active.runtimeView()).isEqualTo(candidate.runtimeView());
		assertFailure(RouteBundleActivationException.Reason.CANDIDATE_ALREADY_ACTIVE,
			() -> registry.stage(candidate, 1));
	}

	@Test
	void activationRechecksCandidateFreshnessWithoutMutatingStateOnFailure() {
		var clock = new MutableClock(T0);
		var registry = new RouteBundleActivationRegistry(clock);
		var candidate = candidate("a", T0.minusSeconds(1), T0.plusSeconds(10));
		registry.stage(candidate, 0);
		clock.set(T0.plusSeconds(10));

		assertFailure(RouteBundleActivationException.Reason.BUNDLE_STALE,
			() -> registry.activate(candidate.identity().manifestSha256(), 0));
		assertFailure(RouteBundleActivationException.Reason.BUNDLE_UNAVAILABLE, registry::activeSnapshot);
		clock.set(T0.plusSeconds(9));
		assertThat(registry.activate(candidate.identity().manifestSha256(), 0).generation()).isOne();
	}

	@Test
	void typedFailuresLeaveTheExistingActiveSnapshotAndStagedCandidateUntouched() {
		var registry = registryAt(T0);
		var first = candidate("a", T0.minusSeconds(1), T0.plusSeconds(60));
		registry.stage(first, 0);
		var firstSnapshot = registry.activate(first.identity().manifestSha256(), 0);
		var second = candidate("b", T0.minusSeconds(1), T0.plusSeconds(60));
		registry.stage(second, 1);

		assertFailure(RouteBundleActivationException.Reason.ACTIVATION_CONFLICT,
			() -> registry.stage(candidate("c", T0.minusSeconds(1), T0.plusSeconds(60)), 0));
		assertFailure(RouteBundleActivationException.Reason.CANDIDATE_IDENTITY_MISMATCH,
			() -> registry.activate("z".repeat(64), 1));
		assertThat(registry.activeSnapshot()).isEqualTo(firstSnapshot);
		assertThat(registry.activate(second.identity().manifestSha256(), 1).generation()).isEqualTo(2);
	}

	@Test
	void sameGenerationConcurrentActivationHasExactlyOneSuccess() throws Exception {
		var registry = registryAt(T0);
		var candidate = candidate("a", T0.minusSeconds(1), T0.plusSeconds(60));
		registry.stage(candidate, 0);
		var barrier = new CyclicBarrier(2);

		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<ActivationResult> first = executor.submit(activation(registry, candidate, barrier));
			Future<ActivationResult> second = executor.submit(activation(registry, candidate, barrier));
			var firstResult = first.get();
			var secondResult = second.get();

			assertThat((firstResult.success() ? 1 : 0) + (secondResult.success() ? 1 : 0)).isOne();
			assertThat(firstResult.success() ? secondResult.reason() : firstResult.reason())
				.isEqualTo(RouteBundleActivationException.Reason.ACTIVATION_CONFLICT);
		}

		assertThat(registry.activeSnapshot().generation()).isOne();
	}

	@Test
	void requestPinnedSnapshotSurvivesALaterActivation() {
		var registry = registryAt(T0);
		var first = candidate("a", T0.minusSeconds(1), T0.plusSeconds(60));
		registry.stage(first, 0);
		var requestSnapshot = registry.activate(first.identity().manifestSha256(), 0);
		var second = candidate("b", T0.minusSeconds(1), T0.plusSeconds(60));
		registry.stage(second, 1);
		var currentSnapshot = registry.activate(second.identity().manifestSha256(), 1);

		assertThat(requestSnapshot.generation()).isOne();
		assertThat(requestSnapshot.identity()).isEqualTo(first.identity());
		assertThat(requestSnapshot.runtimeView()).isEqualTo(first.runtimeView());
		assertThat(currentSnapshot.generation()).isEqualTo(2);
		assertThat(currentSnapshot.identity()).isEqualTo(second.identity());
	}

	private static Callable<ActivationResult> activation(
		RouteBundleActivationRegistry registry, VerifiedRouteBundleCandidate candidate, CyclicBarrier barrier) {
		return () -> {
			barrier.await();
			try {
				registry.activate(candidate.identity().manifestSha256(), 0);
				return ActivationResult.success();
			} catch (RouteBundleActivationException exception) {
				return ActivationResult.failure(exception.reason());
			}
		};
	}

	private static RouteBundleActivationRegistry registryAt(Instant instant) {
		return new RouteBundleActivationRegistry(Clock.fixed(instant, ZoneOffset.UTC));
	}

	private static VerifiedRouteBundleCandidate candidate(String manifestMarker, Instant activeFrom, Instant freshUntil) {
		return new VerifiedRouteBundleCandidate(
			identity(manifestMarker, "Asia/Seoul", "SHA-256", 3, activeFrom, freshUntil),
			new RouteBundleRuntimeView("compiled-" + manifestMarker), T0);
	}

	private static RouteBundleIdentity identity(
		String manifestMarker, String timezone, String digestAlgorithm, int backendSchemaVersion,
		Instant activeFrom, Instant freshUntil) {
		return new RouteBundleIdentity(
			"server-route-bundle", "v1", "station-catalog-v1", timezone,
			manifestMarker.substring(0, 1).repeat(64), "p".repeat(64), "t".repeat(64), "i".repeat(64),
			"a".repeat(64), "f".repeat(64), "r".repeat(64), "c".repeat(64),
			activeFrom, freshUntil, backendSchemaVersion, digestAlgorithm, "fixture-signing-key-v1",
			"u".repeat(64), "n".repeat(64), "o".repeat(64), "e".repeat(64));
	}

	private static void assertFailure(RouteBundleActivationException.Reason reason, ThrowingAction action) {
		assertThatThrownBy(action::run)
			.isInstanceOf(RouteBundleActivationException.class)
			.extracting(error -> ((RouteBundleActivationException) error).reason())
			.isEqualTo(reason);
	}

	private interface ThrowingAction {
		void run();
	}

	private record ActivationResult(boolean success, RouteBundleActivationException.Reason reason) {
		private static ActivationResult success() {
			return new ActivationResult(true, null);
		}

		private static ActivationResult failure(RouteBundleActivationException.Reason reason) {
			return new ActivationResult(false, reason);
		}
	}

	private static final class MutableClock extends Clock {
		private Instant instant;

		private MutableClock(Instant instant) {
			this.instant = instant;
		}

		void set(Instant instant) {
			this.instant = instant;
		}

		@Override
		public ZoneOffset getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return instant;
		}
	}
}

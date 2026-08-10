package com.easysubway.journey.bundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RouteBundleActivationRegistryTest {

	private static final Instant T0 = Instant.parse("2026-08-09T00:00:00Z");

	@Test
	void identityAndAdmissionEvidenceRequireTheirExactSeparateContracts() {
		assertThatThrownBy(() -> identity("a", 2, "server-route-bundle", 1, T0, T0.plusSeconds(60)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> identity("a", 1, "mobile-route-bundle", 1, T0, T0.plusSeconds(60)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> identity("a", 1, "server-route-bundle", 0, T0, T0.plusSeconds(60)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> identity(
			"a", 1, "server-route-bundle", 9_007_199_254_740_992L, T0, T0.plusSeconds(60)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> identityWithRawValues(
			" bundle-a", 1, "route-bundle-key", kstMillis(T0), kstMillis(T0.plusSeconds(60))))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> identityWithRawValues(
			"", 1, "route-bundle-key", kstMillis(T0), kstMillis(T0.plusSeconds(60))))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> identityWithRawValues(
			"bundle-a", 1, " route-bundle-key", kstMillis(T0), kstMillis(T0.plusSeconds(60))))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> identityWithRawValues(
			"bundle-a", 1, "", kstMillis(T0), kstMillis(T0.plusSeconds(60))))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> identityWithRawValues(
			"\u00a0bundle-a", 1, "route-bundle-key", kstMillis(T0), kstMillis(T0.plusSeconds(60))))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> identityWithRawValues(
			"bundle-a", 1, "route-bundle-key\ufeff", kstMillis(T0), kstMillis(T0.plusSeconds(60))))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> identityWithRawValues(
			"bundle-a\u2028", 1, "route-bundle-key", kstMillis(T0), kstMillis(T0.plusSeconds(60))))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(identityWithRawValues(
			"\u001cbundle-a", 1, "route-bundle-key\u001c", kstMillis(T0), kstMillis(T0.plusSeconds(60)))
			.bundleId()).isEqualTo("\u001cbundle-a");
		assertThatThrownBy(() -> identityWithRawValues(
			"bundle-a", 1, "route-bundle-key", "2026-08-09T00:00:00.000+00:00", kstMillis(T0.plusSeconds(60))))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> identityWithRawValues(
			"bundle-a", 1, "route-bundle-key", "2026-02-29T09:00:00.000+09:00", kstMillis(T0.plusSeconds(60))))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RouteBundleAdmissionEvidence(
			"a".repeat(63), "final", "promotion", "publication", "activation"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RouteBundleAdmissionEvidence(
			"a".repeat(64), " final", "promotion", "publication", "activation"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RouteBundleAdmissionEvidence(
			"a".repeat(64), "", "promotion", "publication", "activation"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RouteBundleIdentity.SchemaCompatibility(3, 4))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RouteBundleIdentity.Signature(
			"rsa-sha256-server-route-bundle-v1", "AQID="))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> identity("a", 1, "server-route-bundle", 1, T0, T0))
			.isInstanceOf(IllegalArgumentException.class);

		var identity = identity("a", 1, "server-route-bundle", 7, T0, T0.plusSeconds(60));
		var evidence = evidence("a");

		assertThat(identity.manifestVersion()).isOne();
		assertThat(identity.artifactKind()).isEqualTo("server-route-bundle");
		assertThat(identity.bundleId()).isEqualTo("bundle-a");
		assertThat(identity.releaseSequence()).isEqualTo(7);
		assertThat(identity.stationSetSha256()).isEqualTo("0".repeat(64));
		assertThat(identity.payloadSha256()).isEqualTo("1".repeat(64));
		assertThat(identity.topologySha256()).isEqualTo("2".repeat(64));
		assertThat(identity.timetableSha256()).isEqualTo("3".repeat(64));
		assertThat(identity.accessibilitySha256()).isEqualTo("4".repeat(64));
		assertThat(identity.fareSha256()).isEqualTo("5".repeat(64));
		assertThat(identity.provenanceSha256()).isEqualTo("6".repeat(64));
		assertThat(identity.compatibilitySha256()).isEqualTo("7".repeat(64));
		assertThat(identity.serviceTimezone()).isEqualTo("Asia/Seoul");
		assertThat(identity.schemaCompatibility()).isEqualTo(new RouteBundleIdentity.SchemaCompatibility(3, 3));
		assertThat(identity.signature().algorithm()).isEqualTo("rsa-sha256-server-route-bundle-v1");
		assertThat(identity.signature().value()).isEqualTo("AQID");
		assertThat(identity.activeFrom()).isEqualTo("2026-08-09T09:00:00.000+09:00");
		assertThat(identity.activeFromInstant()).isEqualTo(T0);
		assertThat(evidence.manifestSha256()).isEqualTo("a".repeat(64));
		assertThat(evidence.finalEvidenceReference()).isEqualTo("final-reference-a");
		assertThat(evidence.promotionEvidenceReference()).isEqualTo("promotion-reference-a");
		assertThat(evidence.immutablePublicationReceiptIdentity()).isEqualTo("publication-receipt-a");
		assertThat(evidence.activationRequestIdentity()).isEqualTo("activation-request-a");
	}

	@Test
	void stageKeepsCandidateSeparateFromTheActiveSnapshot() {
		var registry = registryAt(T0);
		var candidate = candidate("a", T0.minusSeconds(1), T0.plusSeconds(60));

		registry.stage(candidate, 0);

		assertFailure(RouteBundleActivationException.Reason.BUNDLE_UNAVAILABLE, registry::activeSnapshot);
		var active = registry.activate(candidate.admissionEvidence().manifestSha256(), 0);
		assertThat(active.generation()).isOne();
		assertThat(active.identity()).isEqualTo(candidate.identity());
		assertThat(active.runtimeView()).isEqualTo(candidate.runtimeView());
		assertFailure(RouteBundleActivationException.Reason.CANDIDATE_ALREADY_ACTIVE,
			() -> registry.stage(candidate, 1));
	}

	@Test
	void stageAndActivationRejectFutureAndStaleCandidatesWithoutMutatingState() {
		var clock = new MutableClock(T0);
		var registry = new RouteBundleActivationRegistry(clock);
		assertFailure(RouteBundleActivationException.Reason.BUNDLE_FUTURE,
			() -> registry.stage(candidate("f", T0.plusSeconds(1), T0.plusSeconds(10)), 0));
		assertFailure(RouteBundleActivationException.Reason.BUNDLE_UNAVAILABLE, registry::activeSnapshot);
		var candidate = candidate("a", T0.minusSeconds(1), T0.plusSeconds(10));
		registry.stage(candidate, 0);
		clock.set(T0.plusSeconds(10));

		assertFailure(RouteBundleActivationException.Reason.BUNDLE_STALE,
			() -> registry.activate(candidate.admissionEvidence().manifestSha256(), 0));
		assertFailure(RouteBundleActivationException.Reason.BUNDLE_UNAVAILABLE, registry::activeSnapshot);
		clock.set(T0.plusSeconds(9));
		assertFailure(RouteBundleActivationException.Reason.CANDIDATE_ALREADY_STAGED,
			() -> registry.stage(candidate, 0));
	}

	@Test
	void staleStagedCandidateWithoutAnActiveSnapshotIsReplacedByAFreshCandidate() {
		var clock = new MutableClock(T0);
		var registry = new RouteBundleActivationRegistry(clock);
		var stale = candidate("a", T0.minusSeconds(1), T0.plusSeconds(10));
		registry.stage(stale, 0);
		clock.set(T0.plusSeconds(10));

		assertFailure(RouteBundleActivationException.Reason.BUNDLE_STALE,
			() -> registry.activate(stale.admissionEvidence().manifestSha256(), 0));
		var fresh = candidate("b", T0.minusSeconds(1), T0.plusSeconds(60));
		registry.stage(fresh, 0);

		var activated = registry.activate(fresh.admissionEvidence().manifestSha256(), 0);
		assertThat(activated.generation()).isOne();
		assertThat(activated.identity()).isEqualTo(fresh.identity());
	}

	@Test
	void staleStagedCandidateIsReplacedWithoutChangingTheExistingActiveSnapshot() {
		var clock = new MutableClock(T0);
		var registry = new RouteBundleActivationRegistry(clock);
		var activeCandidate = candidate("a", T0.minusSeconds(1), T0.plusSeconds(60));
		registry.stage(activeCandidate, 0);
		var active = registry.activate(activeCandidate.admissionEvidence().manifestSha256(), 0);
		var stale = candidate("b", T0.minusSeconds(1), T0.plusSeconds(10));
		registry.stage(stale, 1);
		clock.set(T0.plusSeconds(10));

		assertFailure(RouteBundleActivationException.Reason.BUNDLE_STALE,
			() -> registry.activate(stale.admissionEvidence().manifestSha256(), 1));
		var fresh = candidate("c", T0.minusSeconds(1), T0.plusSeconds(60));
		registry.stage(fresh, 1);

		assertThat(registry.activeSnapshot()).isSameAs(active);
		assertThat(registry.activeSnapshot().generation()).isOne();
		var activated = registry.activate(fresh.admissionEvidence().manifestSha256(), 1);
		assertThat(activated.generation()).isEqualTo(2);
		assertThat(activated.identity()).isEqualTo(fresh.identity());
	}

	@Test
	void currentOrFutureStagedCandidateCannotBeReplaced() {
		var currentRegistry = registryAt(T0);
		var current = candidate("a", T0.minusSeconds(1), T0.plusSeconds(60));
		currentRegistry.stage(current, 0);
		assertFailure(RouteBundleActivationException.Reason.CANDIDATE_ALREADY_STAGED,
			() -> currentRegistry.stage(candidate("b", T0.minusSeconds(1), T0.plusSeconds(60)), 0));

		var clock = new MutableClock(T0);
		var futureRegistry = new RouteBundleActivationRegistry(clock);
		var future = candidate("c", T0, T0.plusSeconds(60));
		futureRegistry.stage(future, 0);
		clock.set(T0.minusSeconds(1));
		var incoming = candidate("d", T0.minusSeconds(2), T0.plusSeconds(60), T0.minusSeconds(2));
		assertFailure(RouteBundleActivationException.Reason.CANDIDATE_ALREADY_STAGED,
			() -> futureRegistry.stage(incoming, 0));
	}

	@Test
	void invalidIncomingCandidateDoesNotClearAnExistingStagedCandidate() {
		var clock = new MutableClock(T0);
		var registry = new RouteBundleActivationRegistry(clock);
		var staged = candidate("a", T0.minusSeconds(1), T0.plusSeconds(60));
		registry.stage(staged, 0);
		clock.set(T0.plusSeconds(61));

		assertFailure(RouteBundleActivationException.Reason.BUNDLE_STALE,
			() -> registry.stage(candidate("b", T0.minusSeconds(1), T0.plusSeconds(60)), 0));
		clock.set(T0.plusSeconds(1));
		assertFailure(RouteBundleActivationException.Reason.CANDIDATE_ALREADY_STAGED,
			() -> registry.stage(candidate("c", T0.minusSeconds(1), T0.plusSeconds(60)), 0));
	}

	@Test
	void activeSnapshotFailsClosedWhenTheClockMovesOutsideItsValidityInterval() {
		var clock = new MutableClock(T0);
		var registry = new RouteBundleActivationRegistry(clock);
		var candidate = candidate("a", T0.minusSeconds(1), T0.plusSeconds(10));
		registry.stage(candidate, 0);
		var pinned = registry.activate(candidate.admissionEvidence().manifestSha256(), 0);

		clock.set(T0.minusSeconds(2));
		assertFailure(RouteBundleActivationException.Reason.BUNDLE_FUTURE, registry::activeSnapshot);
		clock.set(T0.plusSeconds(10));
		assertFailure(RouteBundleActivationException.Reason.BUNDLE_STALE, registry::activeSnapshot);
		assertThat(pinned.identity()).isEqualTo(candidate.identity());
	}

	@Test
	void typedFailuresLeaveTheExistingActiveSnapshotAndStagedCandidateUntouched() {
		var registry = registryAt(T0);
		var first = candidate("a", T0.minusSeconds(1), T0.plusSeconds(60));
		registry.stage(first, 0);
		var firstSnapshot = registry.activate(first.admissionEvidence().manifestSha256(), 0);
		var second = candidate("b", T0.minusSeconds(1), T0.plusSeconds(60));
		registry.stage(second, 1);

		assertFailure(RouteBundleActivationException.Reason.CANDIDATE_ALREADY_STAGED,
			() -> registry.stage(candidate("c", T0.minusSeconds(1), T0.plusSeconds(60)), 1));
		assertFailure(RouteBundleActivationException.Reason.ACTIVATION_CONFLICT,
			() -> registry.stage(candidate("d", T0.minusSeconds(1), T0.plusSeconds(60)), 0));
		assertFailure(RouteBundleActivationException.Reason.CANDIDATE_IDENTITY_MISMATCH,
			() -> registry.activate("e".repeat(64), 1));
		assertThat(registry.activeSnapshot()).isEqualTo(firstSnapshot);
		assertThat(registry.activate(second.admissionEvidence().manifestSha256(), 1).generation()).isEqualTo(2);
		assertFailure(RouteBundleActivationException.Reason.CANDIDATE_NOT_STAGED,
			() -> registry.activate("e".repeat(64), 2));
	}

	@Test
	void sameGenerationConcurrentActivationHasExactlyOneSuccess() throws Exception {
		var registry = new RouteBundleActivationRegistry(new ActivationBarrierClock(T0));
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
		var requestSnapshot = registry.activate(first.admissionEvidence().manifestSha256(), 0);
		var second = candidate("b", T0.minusSeconds(1), T0.plusSeconds(60));
		registry.stage(second, 1);
		var currentSnapshot = registry.activate(second.admissionEvidence().manifestSha256(), 1);

		assertThat(requestSnapshot.generation()).isOne();
		assertThat(requestSnapshot.identity()).isEqualTo(first.identity());
		assertThat(requestSnapshot.admissionEvidence()).isEqualTo(first.admissionEvidence());
		assertThat(requestSnapshot.runtimeView()).isEqualTo(first.runtimeView());
		assertThat(currentSnapshot.generation()).isEqualTo(2);
		assertThat(currentSnapshot.identity()).isEqualTo(second.identity());
	}

	private static Callable<ActivationResult> activation(
		RouteBundleActivationRegistry registry, VerifiedRouteBundleCandidate candidate, CyclicBarrier barrier) {
		return () -> {
			barrier.await();
			try {
				registry.activate(candidate.admissionEvidence().manifestSha256(), 0);
				return ActivationResult.passed();
			} catch (RouteBundleActivationException exception) {
				return ActivationResult.failure(exception.reason());
			}
		};
	}

	private static RouteBundleActivationRegistry registryAt(Instant instant) {
		return new RouteBundleActivationRegistry(Clock.fixed(instant, ZoneOffset.UTC));
	}

	private static VerifiedRouteBundleCandidate candidate(String manifestMarker, Instant activeFrom, Instant freshUntil) {
		return candidate(manifestMarker, activeFrom, freshUntil, T0);
	}

	private static VerifiedRouteBundleCandidate candidate(
		String manifestMarker, Instant activeFrom, Instant freshUntil, Instant verifiedAt) {
		return new VerifiedRouteBundleCandidate(
			identity(manifestMarker, 1, "server-route-bundle", 1, activeFrom, freshUntil),
			evidence(manifestMarker),
			new CompiledRuntimeView("compiled-" + manifestMarker), verifiedAt);
	}

	private static RouteBundleIdentity identity(
		String manifestMarker, int manifestVersion, String artifactKind, long releaseSequence,
		Instant activeFrom, Instant freshUntil) {
		return identityWithRawValues(
			"bundle-" + manifestMarker, releaseSequence, "route-bundle-key",
			kstMillis(activeFrom), kstMillis(freshUntil), manifestVersion, artifactKind);
	}

	private static RouteBundleIdentity identityWithRawValues(
		String bundleId, long releaseSequence, String keyId, String activeFrom, String freshUntil) {
		return identityWithRawValues(
			bundleId, releaseSequence, keyId, activeFrom, freshUntil, 1, "server-route-bundle");
	}

	private static RouteBundleIdentity identityWithRawValues(
		String bundleId, long releaseSequence, String keyId, String activeFrom, String freshUntil,
		int manifestVersion, String artifactKind) {
		return new RouteBundleIdentity(
			manifestVersion, artifactKind, bundleId, releaseSequence,
			"0".repeat(64), "1".repeat(64), "2".repeat(64), "3".repeat(64),
			"4".repeat(64), "5".repeat(64), "6".repeat(64), "7".repeat(64),
			"Asia/Seoul", activeFrom, freshUntil,
			new RouteBundleIdentity.SchemaCompatibility(3, 3),
			keyId, new RouteBundleIdentity.Signature("rsa-sha256-server-route-bundle-v1", "AQID"));
	}

	private static RouteBundleAdmissionEvidence evidence(String manifestMarker) {
		return new RouteBundleAdmissionEvidence(
			manifestMarker.repeat(64), "final-reference-" + manifestMarker,
			"promotion-reference-" + manifestMarker, "publication-receipt-" + manifestMarker,
			"activation-request-" + manifestMarker);
	}

	private static String kstMillis(Instant instant) {
		return DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSXXX")
			.format(instant.atOffset(ZoneOffset.ofHours(9)));
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

	private record CompiledRuntimeView(String marker) implements RouteBundleRuntimeView {
	}

	private record ActivationResult(boolean success, RouteBundleActivationException.Reason reason) {
		private static ActivationResult passed() {
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

	private static final class ActivationBarrierClock extends Clock {
		private final Instant instant;
		private final AtomicInteger calls = new AtomicInteger();
		private final CyclicBarrier activationBarrier = new CyclicBarrier(2);

		private ActivationBarrierClock(Instant instant) {
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
			int call = calls.incrementAndGet();
			if (call == 2 || call == 3) {
				try {
					activationBarrier.await();
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new AssertionError("activation barrier was interrupted", exception);
				} catch (BrokenBarrierException exception) {
					throw new AssertionError("activation barrier failed", exception);
				}
			}
			return instant;
		}
	}
}

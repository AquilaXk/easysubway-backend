package com.easysubway.journey.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.journey.application.JourneyExecutionPorts.BundleSnapshot;
import com.easysubway.journey.application.JourneyExecutionPorts.JourneyPlan;
import com.easysubway.journey.application.JourneyExecutionPorts.JourneyRuntimeSnapshot;
import com.easysubway.journey.application.JourneyExecutionPorts.RaptorQuery;
import com.easysubway.journey.application.JourneyExecutionPorts.RealtimeOverlay;
import com.easysubway.journey.application.JourneyExecutionPorts.RealtimeSnapshot;
import com.easysubway.journey.application.JourneySearchException.Code;
import com.easysubway.journey.application.JourneySearchUseCase.ConstraintMode;
import com.easysubway.journey.application.JourneySearchUseCase.DepartureNow;
import com.easysubway.journey.application.JourneySearchUseCase.DepartureScheduled;
import com.easysubway.journey.application.JourneySearchUseCase.JourneyCandidate;
import com.easysubway.journey.application.JourneySearchUseCase.JourneySearchCommand;
import com.easysubway.journey.application.JourneySearchUseCase.MobilityProfile;
import com.easysubway.journey.application.JourneySearchUseCase.TimePolicy;
import com.easysubway.journey.application.JourneySearchUseCase.TimeSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class JourneySearchServiceTest {

	private static final Instant ACCEPTED_AT = Instant.parse("2026-08-11T00:00:00Z");
	private static final String REQUEST_ID = "01K1Y000000000000000000000";
	private static final String SHA_A = "a".repeat(64);
	private static final String SHA_B = "b".repeat(64);
	private static final String SHA_C = "c".repeat(64);
	private static final JourneyRuntimeSnapshot RUNTIME = new JourneyRuntimeSnapshot() { };
	private static final RealtimeOverlay OVERLAY = new RealtimeOverlay() { };

	@Test
	void timetableOnlyCapturesOneInstantOneSnapshotAndOneRaptorExecution() {
		var clock = new CountingClock(ACCEPTED_AT);
		var snapshotCalls = new AtomicInteger();
		var realtimeCalls = new AtomicInteger();
		var raptorCalls = new AtomicInteger();
		var capturedQuery = new AtomicReference<RaptorQuery>();
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			var service = new JourneySearchService(
				clock,
				acceptedAt -> {
					snapshotCalls.incrementAndGet();
					assertThat(acceptedAt).isEqualTo(ACCEPTED_AT);
					return bundle();
				},
				query -> {
					realtimeCalls.incrementAndGet();
					throw new AssertionError("timetable-only must not call realtime");
				},
				query -> {
					raptorCalls.incrementAndGet();
					capturedQuery.set(query);
					return timetablePlan(query.bundle());
				},
				executor,
				Duration.ofSeconds(1)
			);

			var result = service.search(command(TimePolicy.TIMETABLE_REQUIRED, new DepartureNow()));

			assertThat(clock.calls).hasValue(1);
			assertThat(snapshotCalls).hasValue(1);
			assertThat(realtimeCalls).hasValue(0);
			assertThat(raptorCalls).hasValue(1);
			assertThat(capturedQuery.get().acceptedAt()).isEqualTo(ACCEPTED_AT);
			assertThat(capturedQuery.get().effectiveDeparture()).isEqualTo(ACCEPTED_AT);
			assertThat(capturedQuery.get().realtime()).isNull();
			assertThat(result.requestId()).isEqualTo(REQUEST_ID);
			assertThat(result.calculatedAt()).isEqualTo(ACCEPTED_AT);
			assertThat(result.effectiveDepartureTime())
				.isEqualTo(OffsetDateTime.parse("2026-08-11T09:00:00+09:00"));
			assertThat(result.serviceDate()).hasToString("2026-08-11");
			assertThat(result.sourceIdentity().routeBundleSha256()).isEqualTo(SHA_A);
			assertThat(result.sourceIdentity().realtimeSnapshotId()).isNull();
			assertThat(result.journeys()).singleElement().satisfies(candidate -> {
				assertThat(candidate.planSource()).hasToString("SERVER_TIMETABLE_RAPTOR");
				assertThat(candidate.timeSource()).isEqualTo(TimeSource.TIMETABLE);
				assertThat(candidate.realtimeDepartureTime()).isNull();
				assertThat(candidate.realtimeArrivalTime()).isNull();
			});
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void scheduledDepartureIsNormalizedOnceWithThePinnedBundleTimezone() {
		var clock = new CountingClock(ACCEPTED_AT);
		var capturedDeparture = new AtomicReference<Instant>();
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			var service = service(clock, executor, query -> {
				capturedDeparture.set(query.effectiveDeparture());
				return timetablePlan(query.bundle());
			});
			var requested = OffsetDateTime.parse("2026-08-10T19:30:00-04:00");

			var result = service.search(command(
				TimePolicy.TIMETABLE_REQUIRED,
				new DepartureScheduled(requested)
			));

			assertThat(clock.calls).hasValue(1);
			assertThat(capturedDeparture).hasValue(requested.toInstant());
			assertThat(result.effectiveDepartureTime())
				.isEqualTo(OffsetDateTime.parse("2026-08-11T08:30:00+09:00"));
			assertThat(result.serviceDate()).hasToString("2026-08-11");
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void realtimeRequiredFailureCannotInvokeRaptorOrReturnTimetableSuccess() {
		var raptorCalls = new AtomicInteger();
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			var service = new JourneySearchService(
				Clock.fixed(ACCEPTED_AT, ZoneOffset.UTC),
				acceptedAt -> bundle(),
				query -> {
					throw new JourneySearchException(Code.REALTIME_REQUIRED_UNAVAILABLE);
				},
				query -> {
					raptorCalls.incrementAndGet();
					return timetablePlan(query.bundle());
				},
				executor,
				Duration.ofSeconds(1)
			);

			assertThatThrownBy(() -> service.search(command(TimePolicy.REALTIME_REQUIRED, new DepartureNow())))
				.isInstanceOfSatisfying(JourneySearchException.class,
					exception -> assertThat(exception.code()).isEqualTo(Code.REALTIME_REQUIRED_UNAVAILABLE));
			assertThat(raptorCalls).hasValue(0);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void realtimePortCannotEmitAnUnrelatedApplicationFailureCode() {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			var service = new JourneySearchService(
				Clock.fixed(ACCEPTED_AT, ZoneOffset.UTC),
				acceptedAt -> bundle(),
				query -> {
					throw new JourneySearchException(Code.ROUTE_NOT_FOUND);
				},
				query -> {
					throw new AssertionError("failed realtime must not invoke RAPTOR");
				},
				executor,
				Duration.ofSeconds(1)
			);

			assertThatThrownBy(() -> service.search(command(TimePolicy.REALTIME_REQUIRED, new DepartureNow())))
				.isInstanceOfSatisfying(JourneySearchException.class,
					exception -> assertThat(exception.code()).isEqualTo(Code.REALTIME_REQUIRED_UNAVAILABLE));
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void mismatchedRealtimeIdentityFailsBeforeRaptor() {
		var raptorCalls = new AtomicInteger();
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			var service = new JourneySearchService(
				Clock.fixed(ACCEPTED_AT, ZoneOffset.UTC),
				acceptedAt -> bundle(),
				query -> realtime(SHA_B),
				query -> {
					raptorCalls.incrementAndGet();
					return realtimePlan(query.bundle(), query.realtime());
				},
				executor,
				Duration.ofSeconds(1)
			);

			assertThatThrownBy(() -> service.search(command(TimePolicy.REALTIME_REQUIRED, new DepartureNow())))
				.isInstanceOfSatisfying(JourneySearchException.class,
					exception -> assertThat(exception.code()).isEqualTo(Code.ROUTING_IDENTITY_MISMATCH));
			assertThat(raptorCalls).hasValue(0);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void realtimeRequiredUsesOneMatchedObservationAndOneRaptorExecution() {
		var realtimeCalls = new AtomicInteger();
		var raptorCalls = new AtomicInteger();
		var observation = realtime(SHA_A);
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			var service = new JourneySearchService(
				Clock.fixed(ACCEPTED_AT, ZoneOffset.UTC),
				acceptedAt -> bundle(),
				query -> {
					realtimeCalls.incrementAndGet();
					assertThat(query.bundle().generation()).isEqualTo(7);
					return observation;
				},
				query -> {
					raptorCalls.incrementAndGet();
					assertThat(query.realtime()).isSameAs(observation);
					return realtimePlan(query.bundle(), query.realtime());
				},
				executor,
				Duration.ofSeconds(1)
			);

			var result = service.search(command(TimePolicy.REALTIME_REQUIRED, new DepartureNow()));

			assertThat(realtimeCalls).hasValue(1);
			assertThat(raptorCalls).hasValue(1);
			assertThat(result.validUntil()).isEqualTo(observation.freshUntil());
			assertThat(result.sourceIdentity().realtimeSnapshotId()).isEqualTo(observation.snapshotId());
			assertThat(result.journeys()).singleElement().satisfies(candidate -> {
				assertThat(candidate.timeSource()).isEqualTo(TimeSource.REALTIME);
				assertThat(candidate.realtimeDepartureTime()).isNotNull();
				assertThat(candidate.realtimeArrivalTime()).isNotNull();
			});
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void plannerIdentityMismatchCannotPublishCandidates() {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			var service = service(
				Clock.fixed(ACCEPTED_AT, ZoneOffset.UTC),
				executor,
				query -> new JourneyPlan(
					SHA_B,
					query.bundle().timetableSnapshotId(),
					query.bundle().accessibilitySnapshotId(),
					null,
					List.of(timetableCandidate())
				)
			);

			assertThatThrownBy(() -> service.search(command(TimePolicy.TIMETABLE_REQUIRED, new DepartureNow())))
				.isInstanceOfSatisfying(JourneySearchException.class,
					exception -> assertThat(exception.code()).isEqualTo(Code.ROUTING_IDENTITY_MISMATCH));
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void candidateAboveRequestedTransferLimitCannotPublish() {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			var service = service(
				Clock.fixed(ACCEPTED_AT, ZoneOffset.UTC),
				executor,
				query -> new JourneyPlan(
					query.bundle().routeBundleSha256(),
					query.bundle().timetableSnapshotId(),
					query.bundle().accessibilitySnapshotId(),
					null,
					List.of(timetableCandidate("journey-too-many-transfers", 3))
				)
			);

			assertThatThrownBy(() -> service.search(command(TimePolicy.TIMETABLE_REQUIRED, new DepartureNow())))
				.isInstanceOfSatisfying(JourneySearchException.class,
					exception -> assertThat(exception.code()).isEqualTo(Code.ROUTE_SERVICE_UNAVAILABLE));
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void duplicateServerJourneyIdsCannotPublish() {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			var service = service(
				Clock.fixed(ACCEPTED_AT, ZoneOffset.UTC),
				executor,
				query -> new JourneyPlan(
					query.bundle().routeBundleSha256(),
					query.bundle().timetableSnapshotId(),
					query.bundle().accessibilitySnapshotId(),
					null,
					List.of(timetableCandidate("duplicate", 1), timetableCandidate("duplicate", 1))
				)
			);

			assertThatThrownBy(() -> service.search(command(TimePolicy.TIMETABLE_REQUIRED, new DepartureNow())))
				.isInstanceOfSatisfying(JourneySearchException.class,
					exception -> assertThat(exception.code()).isEqualTo(Code.ROUTE_SERVICE_UNAVAILABLE));
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void timeoutCancelsAndRejectsAPlannerThatCompletesLate() throws InterruptedException {
		var started = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		var completed = new CountDownLatch(1);
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			var service = new JourneySearchService(
				Clock.fixed(ACCEPTED_AT, ZoneOffset.UTC),
				acceptedAt -> bundle(),
				query -> {
					throw new AssertionError("timetable-only must not call realtime");
				},
				query -> {
					started.countDown();
					awaitIgnoringInterrupt(release);
					completed.countDown();
					return timetablePlan(query.bundle());
				},
				executor,
				Duration.ofMillis(50)
			);

			assertThatThrownBy(() -> service.search(command(TimePolicy.TIMETABLE_REQUIRED, new DepartureNow())))
				.isInstanceOfSatisfying(JourneySearchException.class,
					exception -> assertThat(exception.code()).isEqualTo(Code.JOURNEY_SEARCH_TIMEOUT));
			assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
			release.countDown();
			assertThat(completed.await(1, TimeUnit.SECONDS)).isTrue();
		} finally {
			release.countDown();
			executor.shutdownNow();
		}
	}

	@Test
	void invalidRequestIsRejectedBeforeAnyExecutionPort() {
		assertThatThrownBy(() -> new JourneySearchCommand(
			REQUEST_ID,
			"same",
			"same",
			new DepartureNow(),
			TimePolicy.TIMETABLE_REQUIRED,
			MobilityProfile.NO_STAIRS,
			ConstraintMode.NONE,
			4,
			0
		)).isInstanceOf(IllegalArgumentException.class);
	}

	private static JourneySearchService service(
		Clock clock,
		ExecutorService executor,
		JourneyExecutionPorts.RaptorExecutor raptor
	) {
		return new JourneySearchService(
			clock,
			acceptedAt -> bundle(),
			query -> {
				throw new AssertionError("timetable-only must not call realtime");
			},
			raptor,
			executor,
			Duration.ofSeconds(1)
		);
	}

	private static JourneySearchCommand command(TimePolicy timePolicy, JourneySearchUseCase.Departure departure) {
		return new JourneySearchCommand(
			REQUEST_ID,
			"station-origin",
			"station-destination",
			departure,
			timePolicy,
			MobilityProfile.STEP_FREE,
			ConstraintMode.REQUIRE_STEP_FREE,
			2,
			2
		);
	}

	private static BundleSnapshot bundle() {
		return new BundleSnapshot(
			7,
			"bundle-7",
			SHA_A,
			SHA_B,
			"timetable-7",
			"accessibility-7",
			"Asia/Seoul",
			ACCEPTED_AT.plusSeconds(300),
			RUNTIME
		);
	}

	private static RealtimeSnapshot realtime(String routeBundleSha256) {
		return new RealtimeSnapshot(
			"realtime-7",
			routeBundleSha256,
			ACCEPTED_AT,
			ACCEPTED_AT.plusSeconds(20),
			OVERLAY
		);
	}

	private static JourneyPlan timetablePlan(BundleSnapshot bundle) {
		return new JourneyPlan(
			bundle.routeBundleSha256(),
			bundle.timetableSnapshotId(),
			bundle.accessibilitySnapshotId(),
			null,
			List.of(timetableCandidate())
		);
	}

	private static JourneyPlan realtimePlan(BundleSnapshot bundle, RealtimeSnapshot realtime) {
		return new JourneyPlan(
			bundle.routeBundleSha256(),
			bundle.timetableSnapshotId(),
			bundle.accessibilitySnapshotId(),
			realtime.snapshotId(),
			List.of(realtimeCandidate())
		);
	}

	private static JourneyCandidate timetableCandidate() {
		return timetableCandidate("journey-1", 1);
	}

	private static JourneyCandidate timetableCandidate(String journeyId, int transferCount) {
		return new JourneyCandidate(
			journeyId,
			ACCEPTED_AT.plusSeconds(60),
			ACCEPTED_AT.plusSeconds(600),
			null,
			null,
			540,
			transferCount,
			250,
			TimeSource.TIMETABLE,
			true,
			List.of("STEP_FREE_VERIFIED")
		);
	}

	private static JourneyCandidate realtimeCandidate() {
		return new JourneyCandidate(
			"journey-1",
			ACCEPTED_AT.plusSeconds(60),
			ACCEPTED_AT.plusSeconds(600),
			ACCEPTED_AT.plusSeconds(70),
			ACCEPTED_AT.plusSeconds(610),
			540,
			1,
			250,
			TimeSource.REALTIME,
			true,
			List.of("STEP_FREE_VERIFIED")
		);
	}

	private static void awaitIgnoringInterrupt(CountDownLatch latch) {
		boolean interrupted = false;
		while (true) {
			try {
				latch.await();
				break;
			} catch (InterruptedException exception) {
				interrupted = true;
			}
		}
		if (interrupted) {
			Thread.currentThread().interrupt();
		}
	}

	private static final class CountingClock extends Clock {
		private final Instant instant;
		private final AtomicInteger calls = new AtomicInteger();

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
			calls.incrementAndGet();
			return instant;
		}
	}
}

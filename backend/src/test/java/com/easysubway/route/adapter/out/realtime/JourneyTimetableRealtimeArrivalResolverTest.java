package com.easysubway.route.adapter.out.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableRealtimeQuery;
import com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableRealtimeUpdate;
import com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableRealtimeUpdates;
import com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableTripDeparture;
import com.easysubway.route.application.port.out.RealtimeArrivalResolver;
import com.easysubway.route.domain.ArrivalCandidate;
import com.easysubway.route.domain.ArrivalFreshness;
import com.easysubway.route.domain.EtaConfidence;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

class JourneyTimetableRealtimeArrivalResolverTest {

	private static final Instant READY_AT = Instant.parse("2026-08-12T10:00:00Z");
	private static final Instant SNAPSHOT_RECEIVED_AT = Instant.parse("2026-08-12T09:59:58Z");
	private static final String SNAPSHOT_ID = "topis:2026-08-12T09:59:58Z";
	private static final String UNAVAILABLE = "REALTIME_REQUIRED_UNAVAILABLE";

	@Test
	void projectsOneFreshSnapshotDeterministicallyWithoutLegacyService() {
		var gateway = new FakeRealtimeArrivalResolver(new RealtimeArrivalResolver.Resolution(
			ArrivalFreshness.FRESH_REALTIME,
			null,
			SNAPSHOT_ID,
			SNAPSHOT_RECEIVED_AT,
			List.of(
				candidate("T2", "2026-08-12T10:08:00Z", "2026-08-12T09:59:56Z"),
				candidate("T2", "2026-08-12T10:07:00Z", "2026-08-12T09:59:55Z"),
				candidate("T4", "2026-08-12T09:59:30Z", "2026-08-12T09:59:57Z"),
				candidate("T1", "2026-08-12T09:58:59Z", "2026-08-12T09:59:54Z"),
				candidate("UNKNOWN", "2026-08-12T10:04:00Z", "2026-08-12T09:59:53Z"),
				candidate("T3", "2026-08-12T10:12:00Z", "2026-08-12T09:59:52Z")
			),
			List.of("T3")
		));
		var resolver = new JourneyTimetableRealtimeArrivalResolver(gateway);

		TimetableRealtimeUpdates result = resolver.resolve(List.of(query(
			departure("trip-b", "T2", "2026-08-12T10:05:00Z", "2026-08-12T10:06:00Z"),
			departure("trip-d", "T4", "2026-08-12T09:59:00Z", "2026-08-12T10:01:00Z"),
			departure("trip-a", "T1", "2026-08-12T10:02:00Z", "2026-08-12T10:03:00Z"),
			departure("trip-c", "T3", "2026-08-12T10:10:00Z", "2026-08-12T10:11:00Z")
		)));

		assertThat(JourneyTimetableRealtimeArrivalResolver.class.isAnnotationPresent(Component.class)).isTrue();
		assertThat(gateway.calls).hasValue(1);
		assertThat(gateway.query.get()).isEqualTo(new RealtimeArrivalResolver.Query(
			"station-a", "line-4", null, null, null, "", READY_AT));
		assertThat(result.version()).isEqualTo(SNAPSHOT_ID);
		assertThat(result.available()).isTrue();
		assertThat(result.fallbackCode()).isNull();
		assertThat(result.updates()).containsExactly(
			new TimetableRealtimeUpdate(
				"trip-b", 120, 120, false, SNAPSHOT_ID,
				Instant.parse("2026-08-12T09:59:55Z")),
			new TimetableRealtimeUpdate(
				"trip-c", 0, 0, true, SNAPSHOT_ID, SNAPSHOT_RECEIVED_AT),
			new TimetableRealtimeUpdate(
				"trip-d", 30, 30, false, SNAPSHOT_ID,
				Instant.parse("2026-08-12T09:59:57Z"))
		);
	}

	@Test
	void mapsEveryProviderOrProjectionFailureToOneClosedUnavailableResult() {
		TimetableRealtimeQuery validQuery = query(
			departure("trip", "T2", "2026-08-12T10:05:00Z", "2026-08-12T10:06:00Z"));
		ArrivalCandidate matching = candidate(
			"T2", "2026-08-12T10:07:00Z", "2026-08-12T09:59:55Z");

		assertProviderUnavailable(validQuery, null);
		assertProviderUnavailable(validQuery, new RealtimeArrivalResolver.Resolution(
			ArrivalFreshness.STALE_REALTIME, "STALE", SNAPSHOT_ID,
			SNAPSHOT_RECEIVED_AT, List.of(matching), List.of()));
		assertProviderUnavailable(validQuery, new RealtimeArrivalResolver.Resolution(
			ArrivalFreshness.FRESH_REALTIME, null, " ",
			SNAPSHOT_RECEIVED_AT, List.of(matching), List.of()));
		assertProviderUnavailable(validQuery, new RealtimeArrivalResolver.Resolution(
			ArrivalFreshness.FRESH_REALTIME, null, SNAPSHOT_ID + "+other",
			SNAPSHOT_RECEIVED_AT, List.of(matching), List.of()));
		assertProviderUnavailable(validQuery, new RealtimeArrivalResolver.Resolution(
			ArrivalFreshness.FRESH_REALTIME, null, SNAPSHOT_ID,
			null, List.of(matching), List.of()));
		assertProviderUnavailable(validQuery, new RealtimeArrivalResolver.Resolution(
			ArrivalFreshness.FRESH_REALTIME, null, SNAPSHOT_ID,
			SNAPSHOT_RECEIVED_AT, List.of(candidate(
				"UNKNOWN", "2026-08-12T10:07:00Z", "2026-08-12T09:59:55Z")), List.of()));
		assertProviderUnavailable(validQuery, new RealtimeArrivalResolver.Resolution(
			ArrivalFreshness.FRESH_REALTIME, null, SNAPSHOT_ID,
			SNAPSHOT_RECEIVED_AT, List.of(new ArrivalCandidate(
				"T2", "line-4", "", "", 420,
				Instant.parse("2026-08-12T10:07:00Z"), null,
				ArrivalFreshness.FRESH_REALTIME, EtaConfidence.HIGH)), List.of()));

		TimetableRealtimeQuery conflicting = query(
			departure("same-trip", "T1", "2026-08-12T10:03:00Z", "2026-08-12T10:04:00Z"),
			departure("same-trip", "T2", "2026-08-12T10:05:00Z", "2026-08-12T10:06:00Z"));
		assertProviderUnavailable(conflicting, new RealtimeArrivalResolver.Resolution(
			ArrivalFreshness.FRESH_REALTIME, null, SNAPSHOT_ID, SNAPSHOT_RECEIVED_AT,
			List.of(
				candidate("T1", "2026-08-12T10:04:00Z", "2026-08-12T09:59:54Z"),
				candidate("T2", "2026-08-12T10:07:00Z", "2026-08-12T09:59:55Z")),
			List.of()));

		var failedGateway = new FakeRealtimeArrivalResolver(new IllegalStateException("provider-secret-detail"));
		assertUnavailable(new JourneyTimetableRealtimeArrivalResolver(failedGateway).resolve(List.of(validQuery)));
		assertThat(failedGateway.calls).hasValue(1);
	}

	@Test
	void rejectsInvalidQueryInventoryBeforeCallingTheProvider() {
		var gateway = new FakeRealtimeArrivalResolver(new AssertionError("provider must not be called"));
		var resolver = new JourneyTimetableRealtimeArrivalResolver(gateway);
		TimetableRealtimeQuery valid = query(
			departure("trip", "T1", "2026-08-12T10:03:00Z", "2026-08-12T10:04:00Z"));
		TimetableRealtimeQuery duplicateTrain = query(
			departure("trip-1", "T1", "2026-08-12T10:03:00Z", "2026-08-12T10:04:00Z"),
			departure("trip-2", "T1", "2026-08-12T10:05:00Z", "2026-08-12T10:06:00Z"));

		assertUnavailable(resolver.resolve(null));
		assertUnavailable(resolver.resolve(List.of()));
		assertUnavailable(resolver.resolve(List.of(valid, valid)));
		assertUnavailable(resolver.resolve(Arrays.asList((TimetableRealtimeQuery) null)));
		assertUnavailable(resolver.resolve(List.of(query())));
		assertUnavailable(resolver.resolve(List.of(duplicateTrain)));
		assertThat(gateway.calls).hasValue(0);
	}

	private static void assertProviderUnavailable(
		TimetableRealtimeQuery query,
		RealtimeArrivalResolver.Resolution resolution
	) {
		var gateway = new FakeRealtimeArrivalResolver(resolution);
		assertUnavailable(new JourneyTimetableRealtimeArrivalResolver(gateway).resolve(List.of(query)));
		assertThat(gateway.calls).hasValue(1);
	}

	private static void assertUnavailable(TimetableRealtimeUpdates result) {
		assertThat(result).isEqualTo(TimetableRealtimeUpdates.unavailable(UNAVAILABLE));
	}

	private static TimetableRealtimeQuery query(TimetableTripDeparture... departures) {
		return new TimetableRealtimeQuery("station-a", "line-4", READY_AT, List.of(departures));
	}

	private static TimetableTripDeparture departure(
		String tripId,
		String trainNo,
		String scheduledArrivalAt,
		String scheduledDepartureAt
	) {
		return new TimetableTripDeparture(
			tripId, trainNo, null, Instant.parse(scheduledArrivalAt), Instant.parse(scheduledDepartureAt));
	}

	private static ArrivalCandidate candidate(String trainNo, String arrivalAt, String observedAt) {
		return new ArrivalCandidate(
			trainNo,
			"line-4",
			"",
			"",
			0,
			Instant.parse(arrivalAt),
			Instant.parse(observedAt),
			ArrivalFreshness.FRESH_REALTIME,
			EtaConfidence.HIGH
		);
	}

	private static final class FakeRealtimeArrivalResolver implements RealtimeArrivalResolver {

		private final Resolution resolution;
		private final Throwable failure;
		private final AtomicInteger calls = new AtomicInteger();
		private final AtomicReference<Query> query = new AtomicReference<>();

		private FakeRealtimeArrivalResolver(Resolution resolution) {
			this.resolution = resolution;
			this.failure = null;
		}

		private FakeRealtimeArrivalResolver(Throwable failure) {
			this.resolution = null;
			this.failure = failure;
		}

		@Override
		public Resolution resolve(Query query) {
			calls.incrementAndGet();
			this.query.set(query);
			if (failure instanceof RuntimeException runtimeException) {
				throw runtimeException;
			}
			if (failure instanceof Error error) {
				throw error;
			}
			return resolution;
		}
	}
}

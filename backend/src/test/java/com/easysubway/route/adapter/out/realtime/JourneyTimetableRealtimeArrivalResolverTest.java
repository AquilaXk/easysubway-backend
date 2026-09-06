package com.easysubway.route.adapter.out.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.route.application.port.out.RealtimeArrivalResolver;
import com.easysubway.route.application.service.JourneyTimetableRealtimeResolver.Departure;
import com.easysubway.route.application.service.JourneyTimetableRealtimeResolver.Query;
import com.easysubway.route.application.service.JourneyTimetableRealtimeResolver.Update;
import com.easysubway.route.application.service.JourneyTimetableRealtimeResolver.Updates;
import com.easysubway.route.domain.ArrivalCandidate;
import com.easysubway.route.domain.ArrivalFreshness;
import com.easysubway.route.domain.EtaConfidence;
import java.time.Instant;
import java.time.LocalDate;
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

		Updates result = resolver.resolve(List.of(query(
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
		assertThat(result.unavailableReason()).isNull();
		assertThat(result.updates()).containsExactly(
			new Update(
				departure("trip-b", "T2", "2026-08-12T10:05:00Z", "2026-08-12T10:06:00Z"),
				120, 120, false, SNAPSHOT_ID,
				Instant.parse("2026-08-12T09:59:55Z")),
			new Update(
				departure("trip-c", "T3", "2026-08-12T10:10:00Z", "2026-08-12T10:11:00Z"),
				0, 0, true, SNAPSHOT_ID, SNAPSHOT_RECEIVED_AT),
			new Update(
				departure("trip-d", "T4", "2026-08-12T09:59:00Z", "2026-08-12T10:01:00Z"),
				30, 30, false, SNAPSHOT_ID,
				Instant.parse("2026-08-12T09:59:57Z"))
		);
		assertThat(result.updates().get(1).departure()).isEqualTo(
			departure("trip-c", "T3", "2026-08-12T10:10:00Z", "2026-08-12T10:11:00Z"));
	}

	@Test
	void mapsEveryProviderOrProjectionFailureToOneClosedUnavailableResult() {
		Query validQuery = query(
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

		Query conflicting = query(
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
		Query valid = query(
			departure("trip", "T1", "2026-08-12T10:03:00Z", "2026-08-12T10:04:00Z"));
		Query duplicateTrain = query(
			departure("trip-1", "T1", "2026-08-12T10:03:00Z", "2026-08-12T10:04:00Z"),
			departure("trip-2", "T1", "2026-08-12T10:05:00Z", "2026-08-12T10:06:00Z"));
		Query mismatchedOccurrence = new Query("station-a", "line-4", READY_AT, List.of(
			new Departure("station-other", "line-4", "trip", "T2", "LOCAL", LocalDate.of(2026, 8, 12), 7,
				Instant.parse("2026-08-12T10:03:00Z"), Instant.parse("2026-08-12T10:04:00Z"))));

		assertUnavailable(resolver.resolve(null));
		assertUnavailable(resolver.resolve(List.of()));
		assertUnavailable(resolver.resolve(List.of(valid, valid)));
		assertUnavailable(resolver.resolve(Arrays.asList((Query) null)));
		assertUnavailable(resolver.resolve(List.of(query())));
		assertUnavailable(resolver.resolve(List.of(duplicateTrain)));
		assertUnavailable(resolver.resolve(List.of(mismatchedOccurrence)));
		assertThat(gateway.calls).hasValue(0);
	}

	private static void assertProviderUnavailable(
		Query query,
		RealtimeArrivalResolver.Resolution resolution
	) {
		var gateway = new FakeRealtimeArrivalResolver(resolution);
		assertUnavailable(new JourneyTimetableRealtimeArrivalResolver(gateway).resolve(List.of(query)));
		assertThat(gateway.calls).hasValue(1);
	}

	private static void assertUnavailable(Updates result) {
		assertThat(result).isEqualTo(Updates.unavailable(UNAVAILABLE));
	}

	private static Query query(Departure... departures) {
		return new Query("station-a", "line-4", READY_AT, List.of(departures));
	}

	private static Departure departure(
		String tripId,
		String trainNo,
		String scheduledArrivalAt,
		String scheduledDepartureAt
	) {
		return new Departure(
			"station-a", "line-4", tripId, trainNo, "LOCAL", LocalDate.of(2026, 8, 12), 7,
			Instant.parse(scheduledArrivalAt), Instant.parse(scheduledDepartureAt));
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
		private final AtomicReference<RealtimeArrivalResolver.Query> query = new AtomicReference<>();

		private FakeRealtimeArrivalResolver(Resolution resolution) {
			this.resolution = resolution;
			this.failure = null;
		}

		private FakeRealtimeArrivalResolver(Throwable failure) {
			this.resolution = null;
			this.failure = failure;
		}

		@Override
		public Resolution resolve(RealtimeArrivalResolver.Query query) {
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

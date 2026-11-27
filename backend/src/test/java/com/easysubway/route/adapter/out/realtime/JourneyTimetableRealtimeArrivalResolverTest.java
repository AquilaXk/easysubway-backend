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
		Query duplicateOccurrence = query(
			departure("trip-1", "T1", "2026-08-12T10:03:00Z", "2026-08-12T10:04:00Z"),
			departure("trip-1", "T1", "2026-08-12T10:03:00Z", "2026-08-12T10:04:00Z"));
		Query mismatchedOccurrence = new Query("station-a", "line-4", READY_AT, List.of(
			new Departure("station-other", "line-4", "trip", "T2", "LOCAL", LocalDate.of(2026, 8, 12), 7,
				Instant.parse("2026-08-12T10:03:00Z"), Instant.parse("2026-08-12T10:04:00Z"))));

		assertUnavailable(resolver.resolve(null));
		assertUnavailable(resolver.resolve(List.of()));
		assertUnavailable(resolver.resolve(List.of(valid, valid)));
		assertUnavailable(resolver.resolve(Arrays.asList((Query) null)));
		assertUnavailable(resolver.resolve(List.of(query())));
		assertUnavailable(resolver.resolve(List.of(duplicateOccurrence)));
		assertUnavailable(resolver.resolve(List.of(mismatchedOccurrence)));
		assertUnavailable(resolver.resolve(List.of(new Query(" ", "line-4", READY_AT, List.of()))));
		assertUnavailable(resolver.resolve(List.of(new Query(null, "line-4", READY_AT, List.of()))));
		assertUnavailable(resolver.resolve(List.of(new Query("station-a", null, READY_AT, List.of()))));
		assertUnavailable(resolver.resolve(List.of(new Query("station-a", " ", READY_AT, List.of()))));
		assertUnavailable(resolver.resolve(List.of(new Query("station-a", "line-4", null, List.of()))));
		assertUnavailable(resolver.resolve(List.of(
			valid,
			new Query("station-b", "line-2", READY_AT, List.of(
				new Departure("station-b", "line-2", "trip-b", "T9", "LOCAL", LocalDate.of(2026, 8, 12), 1,
					READY_AT, READY_AT))))));
		assertUnavailable(resolver.resolve(List.of(new Query("station-a", "line-4", READY_AT, List.of(
			new Departure("station-a", "line-x", "trip", "T1", "LOCAL", LocalDate.of(2026, 8, 12), 1, READY_AT, READY_AT))))));
		assertUnavailable(resolver.resolve(List.of(new Query("station-a", "line-4", READY_AT, List.of(
			new Departure("station-a", "line-4", null, "T1", "LOCAL", LocalDate.of(2026, 8, 12), 1, READY_AT, READY_AT))))));
		assertUnavailable(resolver.resolve(List.of(new Query("station-a", "line-4", READY_AT, List.of(
			new Departure("station-a", "line-4", " ", "T1", "LOCAL", LocalDate.of(2026, 8, 12), 1, READY_AT, READY_AT))))));
		assertUnavailable(resolver.resolve(List.of(new Query("station-a", "line-4", READY_AT, List.of(
			new Departure("station-a", "line-4", "trip", null, "LOCAL", LocalDate.of(2026, 8, 12), 1, READY_AT, READY_AT))))));
		assertUnavailable(resolver.resolve(List.of(new Query("station-a", "line-4", READY_AT, List.of(
			new Departure("station-a", "line-4", "trip", " ", "LOCAL", LocalDate.of(2026, 8, 12), 1, READY_AT, READY_AT))))));
		assertUnavailable(resolver.resolve(List.of(new Query("station-a", "line-4", READY_AT, List.of(
			new Departure("station-a", "line-4", "trip", "T1", null, LocalDate.of(2026, 8, 12), 1, READY_AT, READY_AT))))));
		assertUnavailable(resolver.resolve(List.of(new Query("station-a", "line-4", READY_AT, List.of(
			new Departure("station-a", "line-4", "trip", "T1", " ", LocalDate.of(2026, 8, 12), 1, READY_AT, READY_AT))))));
		assertUnavailable(resolver.resolve(List.of(new Query("station-a", "line-4", READY_AT, List.of(
			new Departure("station-a", "line-4", "trip", "T1", "LOCAL", null, 1, READY_AT, READY_AT))))));
		assertUnavailable(resolver.resolve(List.of(new Query("station-a", "line-4", READY_AT, List.of(
			new Departure("station-a", "line-4", "trip", "T1", "LOCAL", LocalDate.of(2026, 8, 12), 0, READY_AT, READY_AT))))));
		assertUnavailable(resolver.resolve(List.of(new Query("station-a", "line-4", READY_AT, List.of(
			new Departure("station-a", "line-4", "trip", "T1", "LOCAL", LocalDate.of(2026, 8, 12), 1, null, READY_AT))))));
		assertUnavailable(resolver.resolve(List.of(new Query("station-a", "line-4", READY_AT, List.of(
			new Departure("station-a", "line-4", "trip", "T1", "LOCAL", LocalDate.of(2026, 8, 12), 1, READY_AT, null))))));
		assertUnavailable(resolver.resolve(List.of(new Query("station-a", "line-4", READY_AT, List.of(
			new Departure("station-a", "line-4", "trip", "T1", "LOCAL", LocalDate.of(2026, 8, 12), 1,
				READY_AT.plusSeconds(60), READY_AT))))));
		assertThat(gateway.calls).hasValue(0);
	}

	@Test
	void coversCancelledTieBreakingDeltaOverflowAndMergeConflictBranches() {
		// 1) deltaSeconds Integer 범위 초과 시 REALTIME_PROVIDER_UNAVAILABLE 처리
		Query validQuery = query(
			departure("trip-1", "T1", "2026-08-12T10:05:00Z", "2026-08-12T10:06:00Z"));
		assertProviderUnavailable(validQuery, new RealtimeArrivalResolver.Resolution(
			ArrivalFreshness.FRESH_REALTIME, null, SNAPSHOT_ID, SNAPSHOT_RECEIVED_AT,
			List.of(candidate("T1", "2100-08-12T10:05:00Z", "2026-08-12T09:59:55Z")),
			List.of()));

		// 2) 결항 열차 선택: 미존재 열차, readyAt 이전/이후 다건 후보 비교
		Query cancelQuery = query(
			departure("trip-before-old", "T1", "2026-08-12T09:50:00Z", "2026-08-12T09:51:00Z"),
			departure("trip-before-late", "T1", "2026-08-12T09:57:00Z", "2026-08-12T09:58:00Z"),
			departure("trip-after-late", "T2", "2026-08-12T10:20:00Z", "2026-08-12T10:21:00Z"),
			departure("trip-after-early", "T2", "2026-08-12T10:05:00Z", "2026-08-12T10:06:00Z"),
			departure("trip-after-before", "T2", "2026-08-12T09:55:00Z", "2026-08-12T09:56:00Z"));
		Updates cancelUpdates = new JourneyTimetableRealtimeArrivalResolver(new FakeRealtimeArrivalResolver(
			new RealtimeArrivalResolver.Resolution(
				ArrivalFreshness.FRESH_REALTIME, null, SNAPSHOT_ID, SNAPSHOT_RECEIVED_AT,
				List.of(
					new ArrivalCandidate("T1", "line-4", "", "", 0, READY_AT, SNAPSHOT_RECEIVED_AT,
						ArrivalFreshness.STALE_REALTIME, EtaConfidence.HIGH),
					new ArrivalCandidate(null, "line-4", "", "", 0, READY_AT, SNAPSHOT_RECEIVED_AT,
						ArrivalFreshness.FRESH_REALTIME, EtaConfidence.HIGH),
					new ArrivalCandidate(" ", "line-4", "", "", 0, READY_AT, SNAPSHOT_RECEIVED_AT,
						ArrivalFreshness.FRESH_REALTIME, EtaConfidence.HIGH)),
				List.of("T1", "T2", "MISSING-TRAIN"))))
			.resolve(List.of(cancelQuery));
		assertThat(cancelUpdates.updates()).extracting(u -> u.departure().tripId())
			.containsExactlyInAnyOrder("trip-before-late", "trip-after-early");

		// 3) 동률 비교(scheduledArrivalAt, serviceDate, stopSequence), earlierCandidate 동일시각 비교, merge 덮어쓰기/충돌
		Query tieQuery = new Query("station-a", "line-4", READY_AT, List.of(
			new Departure("station-a", "line-4", "trip-seq1", "T1", "LOCAL", LocalDate.of(2026, 8, 12), 1,
				Instant.parse("2026-08-12T10:05:00Z"), Instant.parse("2026-08-12T10:06:00Z")),
			new Departure("station-a", "line-4", "trip-seq2", "T1", "LOCAL", LocalDate.of(2026, 8, 12), 2,
				Instant.parse("2026-08-12T10:05:00Z"), Instant.parse("2026-08-12T10:06:00Z")),
			new Departure("station-a", "line-4", "trip-date-next", "T1", "LOCAL", LocalDate.of(2026, 8, 13), 1,
				Instant.parse("2026-08-12T10:05:00Z"), Instant.parse("2026-08-12T10:06:00Z")),
			new Departure("station-a", "line-4", "trip-later-arr", "T2", "LOCAL", LocalDate.of(2026, 8, 12), 1,
				Instant.parse("2026-08-12T10:07:00Z"), Instant.parse("2026-08-12T10:08:00Z")),
			new Departure("station-a", "line-4", "trip-earlier-arr", "T2", "LOCAL", LocalDate.of(2026, 8, 12), 1,
				Instant.parse("2026-08-12T10:03:00Z"), Instant.parse("2026-08-12T10:04:00Z"))));
		Updates tieUpdates = new JourneyTimetableRealtimeArrivalResolver(new FakeRealtimeArrivalResolver(
			new RealtimeArrivalResolver.Resolution(
				ArrivalFreshness.FRESH_REALTIME, null, SNAPSHOT_ID, SNAPSHOT_RECEIVED_AT,
				List.of(
					candidate("T1", "2026-08-12T10:05:00Z", "2026-08-12T09:59:51Z"),
					candidate("T1", "2026-08-12T10:05:00Z", "2026-08-12T09:59:55Z"),
					candidate("T1", "2026-08-12T10:05:00Z", "2026-08-12T09:59:53Z"),
					candidate("T2", "2026-08-12T10:05:00Z", "2026-08-12T09:59:54Z")),
				List.of())))
			.resolve(List.of(tieQuery));
		assertThat(tieUpdates.updates()).extracting(u -> u.departure().tripId(), Update::providerObservedAt)
			.containsExactlyInAnyOrder(
				org.assertj.core.groups.Tuple.tuple("trip-seq1", Instant.parse("2026-08-12T09:59:55Z")),
				org.assertj.core.groups.Tuple.tuple("trip-earlier-arr", Instant.parse("2026-08-12T09:59:54Z")));

		// 4) 다중 노선에서 동일 tripId 충돌(cancelled, delta, snapshotId) 및 동일 업데이트 최신 observedAt 갱신
		Query hubLine1 = new Query("station-a", "line-1", READY_AT, List.of(
			new Departure("station-a", "line-1", "shared-trip", "T1", "LOCAL", LocalDate.of(2026, 8, 12), 1,
				Instant.parse("2026-08-12T10:05:00Z"), Instant.parse("2026-08-12T10:06:00Z"))));
		Query hubLine2 = new Query("station-a", "line-2", READY_AT, List.of(
			new Departure("station-a", "line-2", "shared-trip", "T1", "LOCAL", LocalDate.of(2026, 8, 12), 1,
				Instant.parse("2026-08-12T10:05:00Z"), Instant.parse("2026-08-12T10:06:00Z"))));
		RealtimeArrivalResolver conflictSnapshotResolver = q -> "line-1".equals(q.lineId())
			? new RealtimeArrivalResolver.Resolution(
				ArrivalFreshness.FRESH_REALTIME, null, "snap-1", SNAPSHOT_RECEIVED_AT,
				List.of(), List.of("T1"))
			: new RealtimeArrivalResolver.Resolution(
				ArrivalFreshness.FRESH_REALTIME, null, "snap-2", SNAPSHOT_RECEIVED_AT,
				List.of(), List.of("T1"));
		assertUnavailable(new JourneyTimetableRealtimeArrivalResolver(conflictSnapshotResolver)
			.resolve(List.of(hubLine1, hubLine2)));

		RealtimeArrivalResolver conflictDeltaResolver = q -> new RealtimeArrivalResolver.Resolution(
			ArrivalFreshness.FRESH_REALTIME, null, "snap-1", SNAPSHOT_RECEIVED_AT,
			List.of(new ArrivalCandidate(
				"T1", q.lineId(), "", "", 0,
				"line-1".equals(q.lineId())
					? Instant.parse("2026-08-12T10:06:00Z")
					: Instant.parse("2026-08-12T10:07:00Z"),
				SNAPSHOT_RECEIVED_AT, ArrivalFreshness.FRESH_REALTIME, EtaConfidence.HIGH)),
			List.of());
		assertUnavailable(new JourneyTimetableRealtimeArrivalResolver(conflictDeltaResolver)
			.resolve(List.of(hubLine1, hubLine2)));

		Query sameArrivalLine1 = new Query("station-a", "line-1", READY_AT, List.of(
			new Departure("station-a", "line-1", "shared-trip", "T1", "LOCAL", LocalDate.of(2026, 8, 12), 1,
				Instant.parse("2026-08-12T10:05:00Z"), Instant.parse("2026-08-12T10:06:00Z"))));
		Query sameArrivalLine2 = new Query("station-a", "line-1", READY_AT, List.of(
			new Departure("station-a", "line-1", "shared-trip", "T1", "LOCAL", LocalDate.of(2026, 8, 12), 1,
				Instant.parse("2026-08-12T10:05:00Z"), Instant.parse("2026-08-12T10:06:00Z"))));
		// sameArrivalLine1 + hubLine2 (different lineId, identical departure/delta/snapshot, newer observedAt on line-2)
		Query identicalDepLine2 = new Query("station-a", "line-2", READY_AT, List.of(
			new Departure("station-a", "line-1", "shared-trip", "T1", "LOCAL", LocalDate.of(2026, 8, 12), 1,
				Instant.parse("2026-08-12T10:05:00Z"), Instant.parse("2026-08-12T10:06:00Z"))));
		assertThat(identicalDepLine2.lineId()).isEqualTo("line-2");
		RealtimeArrivalResolver sameLineMergeResolver = q -> new RealtimeArrivalResolver.Resolution(
			ArrivalFreshness.FRESH_REALTIME, null, "snap-1",
			"line-1".equals(q.lineId()) ? SNAPSHOT_RECEIVED_AT : SNAPSHOT_RECEIVED_AT.plusSeconds(1),
			List.of(), List.of("T1"));
		// identicalDepLine2 has departure.lineId()="line-1" while query.lineId()="line-2" -> exactPlannedDepartures returns null
		assertUnavailable(new JourneyTimetableRealtimeArrivalResolver(sameLineMergeResolver)
			.resolve(List.of(sameArrivalLine1, identicalDepLine2)));
		assertUnavailable(new JourneyTimetableRealtimeArrivalResolver(sameLineMergeResolver)
			.resolve(List.of(sameArrivalLine2, new Query("station-a", "line-2", READY_AT, List.of(
				new Departure("station-a", "line-2", "shared-trip", "T1", "LOCAL", LocalDate.of(2026, 8, 12), 1,
					Instant.parse("2026-08-12T10:05:00Z"), Instant.parse("2026-08-12T10:06:00Z")))))));
		// cancel vs non-cancel conflict on same tripId
		RealtimeArrivalResolver cancelConflictResolver = q -> "line-1".equals(q.lineId())
			? new RealtimeArrivalResolver.Resolution(
				ArrivalFreshness.FRESH_REALTIME, null, "snap-1", SNAPSHOT_RECEIVED_AT, List.of(), List.of("T1"))
			: new RealtimeArrivalResolver.Resolution(
				ArrivalFreshness.FRESH_REALTIME, null, "snap-1", SNAPSHOT_RECEIVED_AT,
				List.of(new ArrivalCandidate("T1", "line-2", "", "", 0,
					Instant.parse("2026-08-12T10:06:00Z"), SNAPSHOT_RECEIVED_AT,
					ArrivalFreshness.FRESH_REALTIME, EtaConfidence.HIGH)),
				List.of());
		assertUnavailable(new JourneyTimetableRealtimeArrivalResolver(cancelConflictResolver)
			.resolve(List.of(hubLine1, hubLine2)));
	}

	@Test
	void resolvesMultiLineHubCircularAndAdjacentServiceDayDepartures() {
		Query line2Query = new Query("station-a", "line-2", READY_AT, List.of(
			new Departure("station-a", "line-2", "trip-loop", "2001", "LOCAL", LocalDate.of(2026, 8, 12), 2,
				Instant.parse("2026-08-12T10:03:00Z"), Instant.parse("2026-08-12T10:04:00Z")),
			new Departure("station-a", "line-2", "trip-loop", "2001", "LOCAL", LocalDate.of(2026, 8, 12), 20,
				Instant.parse("2026-08-12T11:33:00Z"), Instant.parse("2026-08-12T11:34:00Z"))));
		Query line4Query = query(
			new Departure("station-a", "line-4", "trip-night", "T1", "LOCAL", LocalDate.of(2026, 8, 11), 7,
				Instant.parse("2026-08-11T17:55:00Z"), Instant.parse("2026-08-11T17:56:00Z")),
			new Departure("station-a", "line-4", "trip-night", "T1", "LOCAL", LocalDate.of(2026, 8, 12), 7,
				Instant.parse("2026-08-12T17:55:00Z"), Instant.parse("2026-08-12T17:56:00Z")));

		RealtimeArrivalResolver perLineGateway = q -> switch (q.lineId()) {
			case "line-2" -> new RealtimeArrivalResolver.Resolution(
				ArrivalFreshness.FRESH_REALTIME, null, "snap-2", SNAPSHOT_RECEIVED_AT,
				List.of(new ArrivalCandidate(
					"2001", "line-2", "", "", 0,
					Instant.parse("2026-08-12T10:04:00Z"), SNAPSHOT_RECEIVED_AT,
					ArrivalFreshness.FRESH_REALTIME, EtaConfidence.HIGH)),
				List.of());
			case "line-4" -> new RealtimeArrivalResolver.Resolution(
				ArrivalFreshness.FRESH_REALTIME, null, "snap-4", SNAPSHOT_RECEIVED_AT,
				List.of(candidate("T1", "2026-08-12T17:56:00Z", "2026-08-12T09:59:58Z")),
				List.of());
			default -> throw new AssertionError("unexpected line: " + q.lineId());
		};

		Updates updates = new JourneyTimetableRealtimeArrivalResolver(perLineGateway).resolve(List.of(line2Query, line4Query));
		assertThat(updates.available()).isTrue();
		assertThat(updates.version()).isEqualTo("snap-2|snap-4");
		assertThat(updates.updates()).hasSize(2);
		assertThat(updates.updates())
			.extracting(u -> u.departure().tripId(), u -> u.departure().stopSequence(),
				u -> u.departure().serviceDate(), Update::arrivalDeltaSeconds, Update::providerSnapshotId)
			.containsExactlyInAnyOrder(
				org.assertj.core.groups.Tuple.tuple("trip-loop", 2, LocalDate.of(2026, 8, 12), 60, "snap-2"),
				org.assertj.core.groups.Tuple.tuple("trip-night", 7, LocalDate.of(2026, 8, 12), 60, "snap-4"));

		RealtimeArrivalResolver partialGateway = q -> "line-2".equals(q.lineId())
			? new RealtimeArrivalResolver.Resolution(
				ArrivalFreshness.FRESH_REALTIME, null, "snap-2", SNAPSHOT_RECEIVED_AT,
				List.of(new ArrivalCandidate(
					"2001", "line-2", "", "", 0,
					Instant.parse("2026-08-12T10:04:00Z"), SNAPSHOT_RECEIVED_AT,
					ArrivalFreshness.FRESH_REALTIME, EtaConfidence.HIGH)),
				List.of())
			: new RealtimeArrivalResolver.Resolution(
				ArrivalFreshness.UNAVAILABLE, "STALE", null, null, List.of(), List.of());

		Updates partial = new JourneyTimetableRealtimeArrivalResolver(partialGateway).resolve(List.of(line2Query, line4Query));
		assertThat(partial.available()).isTrue();
		assertThat(partial.version()).isEqualTo("snap-2");
		assertThat(partial.updates()).hasSize(1);
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

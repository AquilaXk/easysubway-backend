package com.easysubway.journey.bundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.journey.application.StationTimetableSearchService.Failure;
import com.easysubway.journey.application.StationTimetableSearchService.FailureException;
import com.easysubway.journey.application.StationTimetableSearchService.SourceIdentity;
import com.easysubway.journey.application.StationTimetableSnapshotPort.StationLine;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** #476: 역 시간표는 활성 서버 경로 번들 한 세대에서만 읽고, 없거나 만료되면 명시적으로 실패한다. */
class RouteBundleStationTimetableAdapterTest {

	private static final Instant NOW = Instant.parse("2026-10-03T06:00:00Z");
	private static final Instant FRESH_UNTIL = Instant.parse("2026-10-10T00:05:31.571Z");
	private static final String MANIFEST_SHA = "a".repeat(64);
	private static final String STATION_SET_SHA = "0".repeat(64);
	private static final String TIMETABLE_SHA = "b".repeat(64);
	private static final String PROVENANCE_SHA = "4".repeat(64);

	@Test
	void readsTheActiveBundleTimetableCanonicalStationLinesAndIdentity() {
		var clock = new MutableClock(NOW);
		var runtime = new StationRuntime(RouteTimetable.empty(), Set.of(new StationLine("station-a", "line-1")));
		var adapter = new RouteBundleStationTimetableAdapter(activeRegistry(clock, runtime));

		var snapshot = adapter.loadStationTimetableSnapshot();

		assertThat(snapshot.timetable()).isSameAs(runtime.stationTimetable());
		assertThat(snapshot.canonicalStationLines()).containsExactly(new StationLine("station-a", "line-1"));
		assertThat(snapshot.sourceIdentity()).isEqualTo(new SourceIdentity(
			"nationwide-route-bundle-1", TIMETABLE_SHA, "sha256:" + STATION_SET_SHA, STATION_SET_SHA, PROVENANCE_SHA,
			MANIFEST_SHA, FRESH_UNTIL));
	}

	@Test
	void absentActiveBundleIsTimetableUnavailable() {
		var adapter = new RouteBundleStationTimetableAdapter(new RouteBundleActivationRegistry(new MutableClock(NOW)));

		assertFailure(adapter, Failure.TIMETABLE_UNAVAILABLE);
	}

	@Test
	void stagedButNotActivatedBundleIsTimetableUnavailable() {
		var clock = new MutableClock(NOW);
		var registry = new RouteBundleActivationRegistry(clock);
		registry.stage(candidate(new StationRuntime(RouteTimetable.empty(), Set.of())), 0);

		assertFailure(new RouteBundleStationTimetableAdapter(registry), Failure.TIMETABLE_UNAVAILABLE);
	}

	@Test
	void expiredActiveBundleIsTimetableStaleNeverTheExpiredRows() {
		var clock = new MutableClock(NOW);
		var adapter = new RouteBundleStationTimetableAdapter(activeRegistry(clock,
			new StationRuntime(RouteTimetable.empty(), Set.of(new StationLine("station-a", "line-1")))));
		clock.set(FRESH_UNTIL);

		assertFailure(adapter, Failure.TIMETABLE_STALE);
	}

	@Test
	void activeRuntimeWithoutStationTimetableIsTimetableUnavailable() {
		var adapter = new RouteBundleStationTimetableAdapter(activeRegistry(new MutableClock(NOW), new OtherRuntime()));

		assertFailure(adapter, Failure.TIMETABLE_UNAVAILABLE);
	}

	private static void assertFailure(RouteBundleStationTimetableAdapter adapter, Failure expected) {
		assertThatThrownBy(adapter::loadStationTimetableSnapshot)
			.isInstanceOf(FailureException.class)
			.extracting(error -> ((FailureException) error).failure())
			.isEqualTo(expected);
	}

	private static RouteBundleActivationRegistry activeRegistry(Clock clock, RouteBundleRuntimeView runtime) {
		var registry = new RouteBundleActivationRegistry(clock);
		registry.stage(candidate(runtime), 0);
		registry.activate(MANIFEST_SHA, 0);
		return registry;
	}

	private static VerifiedRouteBundleCandidate candidate(RouteBundleRuntimeView runtime) {
		return new VerifiedRouteBundleCandidate(
			identity(),
			new RouteBundleAdmissionEvidence(
				MANIFEST_SHA, "final-evidence", "promotion-evidence", "publication-receipt", "activation-request"),
			RouteBundleServingEvidence.unobservable(),
			runtime,
			NOW.minusSeconds(1));
	}

	private static RouteBundleIdentity identity() {
		return new RouteBundleIdentity(
			1,
			"server-route-bundle",
			"nationwide-route-bundle-1",
			126,
			STATION_SET_SHA,
			"1".repeat(64),
			"2".repeat(64),
			TIMETABLE_SHA,
			"c".repeat(64),
			"3".repeat(64),
			PROVENANCE_SHA,
			"5".repeat(64),
			"Asia/Seoul",
			"2026-10-03T14:27:51.411+09:00",
			"2026-10-10T09:05:31.571+09:00",
			new RouteBundleIdentity.SchemaCompatibility(3, 3),
			"launch-key",
			new RouteBundleIdentity.Signature("rsa-sha256-server-route-bundle-v1", "AQID"));
	}

	private record StationRuntime(RouteTimetable stationTimetable, Set<StationLine> canonicalStationLines)
		implements RouteBundleRuntimeView, RouteBundleStationTimetableSource {
	}

	private record OtherRuntime() implements RouteBundleRuntimeView {
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
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return instant;
		}
	}
}

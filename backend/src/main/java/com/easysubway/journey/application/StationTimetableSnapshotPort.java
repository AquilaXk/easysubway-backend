package com.easysubway.journey.application;

import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import java.util.Objects;
import java.util.Set;

/**
 * Supplies the timetable of the one active server route bundle for station timetable search.
 *
 * <p>Implementations throw {@link StationTimetableSearchService.FailureException} with
 * {@code TIMETABLE_UNAVAILABLE} when no bundle is active and {@code TIMETABLE_STALE} when the active bundle
 * expired. They never substitute another timetable source.</p>
 */
@FunctionalInterface
public interface StationTimetableSnapshotPort {

	StationTimetableSnapshot loadStationTimetableSnapshot();

	/** One bundle generation: its identity, its per-generation timetable index and its canonical station-line set. */
	record StationTimetableSnapshot(
		StationTimetableSearchService.SourceIdentity sourceIdentity,
		StationTimetableIndex index,
		Set<StationLine> canonicalStationLines) {

		public StationTimetableSnapshot {
			canonicalStationLines = canonicalStationLines == null ? null : Set.copyOf(canonicalStationLines);
		}

		/** 색인을 따로 들고 있지 않은 호출자(테스트 등)용. 호출마다 새 색인을 만들므로 운영 경로는 세대 색인을 넘긴다. */
		public StationTimetableSnapshot(
			StationTimetableSearchService.SourceIdentity sourceIdentity,
			RouteTimetable timetable,
			Set<StationLine> canonicalStationLines) {
			this(sourceIdentity, timetable == null ? null : StationTimetableIndex.of(timetable), canonicalStationLines);
		}
	}

	record StationLine(String stationId, String lineId) {
		public StationLine {
			Objects.requireNonNull(stationId, "stationId");
			Objects.requireNonNull(lineId, "lineId");
		}
	}
}

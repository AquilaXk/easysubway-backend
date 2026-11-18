package com.easysubway.journey.bundle;

import com.easysubway.journey.application.StationTimetableIndex;
import com.easysubway.journey.application.StationTimetableSnapshotPort.StationLine;
import java.util.Set;

/** Station timetable rows of one compiled route-bundle generation. */
public interface RouteBundleStationTimetableSource {

	/**
	 * The station timetable index of this generation, built once over the bundle timetable component exactly as compiled
	 * for the planner of the same generation and reused by every request served from it.
	 */
	StationTimetableIndex stationTimetableIndex();

	/** The bundle {@code station_lines} rows, identical across the four admitted payload components. */
	Set<StationLine> canonicalStationLines();
}

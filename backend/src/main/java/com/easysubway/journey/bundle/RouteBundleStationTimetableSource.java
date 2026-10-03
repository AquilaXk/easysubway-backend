package com.easysubway.journey.bundle;

import com.easysubway.journey.application.StationTimetableSnapshotPort.StationLine;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import java.util.Set;

/** Station timetable rows of one compiled route-bundle generation. */
public interface RouteBundleStationTimetableSource {

	/** The bundle timetable component exactly as compiled for the planner of the same generation. */
	RouteTimetable stationTimetable();

	/** The bundle {@code station_lines} rows, identical across the four admitted payload components. */
	Set<StationLine> canonicalStationLines();
}

package com.easysubway.transit.application.port.out;

import com.easysubway.transit.domain.StationLayoutSource;

public interface SaveStationLayoutSourcePort {

	void saveStationLayoutSource(StationLayoutSource source);

	default void saveStationLayoutSource(StationLayoutSource source, String updatedBy) {
		saveStationLayoutSource(source);
	}
}

package com.easysubway.transit.domain;

import java.util.ArrayList;
import java.util.List;

public record StationWithLines(Station station, List<StationLineSummary> lines) {

	public StationWithLines {
		lines = lines == null ? null : new ArrayList<>(lines);
	}

	@Override
	public List<StationLineSummary> lines() {
		return lines == null ? null : new ArrayList<>(lines);
	}
}

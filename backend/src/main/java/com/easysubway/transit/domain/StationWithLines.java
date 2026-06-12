package com.easysubway.transit.domain;

import java.util.List;

public record StationWithLines(Station station, List<StationLineSummary> lines) {
}

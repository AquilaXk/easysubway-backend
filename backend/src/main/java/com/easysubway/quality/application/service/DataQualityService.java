package com.easysubway.quality.application.service;

import com.easysubway.quality.application.port.in.DataQualityUseCase;
import com.easysubway.quality.domain.DataQualitySummary;
import com.easysubway.quality.domain.RegionDataQualitySummary;
import com.easysubway.transit.application.port.out.LoadTransitMasterPort;
import com.easysubway.transit.domain.AccessibilityFacility;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import com.easysubway.transit.domain.DataConfidenceLevel;
import com.easysubway.transit.domain.DataQualityLevel;
import com.easysubway.transit.domain.Station;
import com.easysubway.transit.domain.StationExit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class DataQualityService implements DataQualityUseCase {

	private final LoadTransitMasterPort loadTransitMasterPort;

	public DataQualityService(LoadTransitMasterPort loadTransitMasterPort) {
		this.loadTransitMasterPort = loadTransitMasterPort;
	}

	@Override
	public DataQualitySummary summarizeDataQuality() {
		List<Station> stations = loadTransitMasterPort.loadStations();
		List<StationExit> exits = loadTransitMasterPort.loadStationExits();
		List<AccessibilityFacility> facilities = loadTransitMasterPort.loadAccessibilityFacilities();

		return new DataQualitySummary(
			stations.size(),
			exits.size(),
			facilities.size(),
			countStationQuality(stations),
			summarizeRegionQuality(stations),
			countExitConfidence(exits),
			countFacilityConfidence(facilities),
			countNeedsVerificationFacilities(facilities),
			countMissingStationVerificationDate(stations)
		);
	}

	private Map<DataQualityLevel, Long> countStationQuality(List<Station> stations) {
		var counts = emptyQualityCounts();
		for (Station station : stations) {
			DataQualityLevel level = requireQualityLevel(station);
			counts.put(level, counts.get(level) + 1);
		}
		return counts;
	}

	private List<RegionDataQualitySummary> summarizeRegionQuality(List<Station> stations) {
		return stations.stream()
			.filter(station -> station.region() != null && !station.region().isBlank())
			.collect(Collectors.groupingBy(Station::region, TreeMap::new, Collectors.toList()))
			.entrySet()
			.stream()
			.map(entry -> new RegionDataQualitySummary(
				entry.getKey(),
				entry.getValue().size(),
				countStationQuality(entry.getValue())
			))
			.toList();
	}

	private Map<DataConfidenceLevel, Long> countExitConfidence(List<StationExit> exits) {
		var counts = emptyConfidenceCounts();
		for (StationExit exit : exits) {
			DataConfidenceLevel level = requireConfidenceLevel(exit);
			counts.put(level, counts.get(level) + 1);
		}
		return counts;
	}

	private Map<DataConfidenceLevel, Long> countFacilityConfidence(List<AccessibilityFacility> facilities) {
		var counts = emptyConfidenceCounts();
		for (AccessibilityFacility facility : facilities) {
			DataConfidenceLevel level = requireConfidenceLevel(facility);
			counts.put(level, counts.get(level) + 1);
		}
		return counts;
	}

	// 마스터 데이터 오류는 요약 집계에서 조용히 누락하지 않고 운영자가 바로 알 수 있게 실패시킨다.
	private DataQualityLevel requireQualityLevel(Station station) {
		if (station.dataQualityLevel() == null) {
			throw new IllegalStateException("역 " + station.id() + "의 dataQualityLevel이 비어 있습니다.");
		}
		return station.dataQualityLevel();
	}

	private DataConfidenceLevel requireConfidenceLevel(StationExit exit) {
		if (exit.dataConfidence() == null) {
			throw new IllegalStateException("출구 " + exit.id() + "의 dataConfidence가 비어 있습니다.");
		}
		return exit.dataConfidence();
	}

	private DataConfidenceLevel requireConfidenceLevel(AccessibilityFacility facility) {
		if (facility.dataConfidence() == null) {
			throw new IllegalStateException("시설 " + facility.id() + "의 dataConfidence가 비어 있습니다.");
		}
		return facility.dataConfidence();
	}

	private long countNeedsVerificationFacilities(List<AccessibilityFacility> facilities) {
		return facilities.stream()
			.filter(facility -> facility.dataConfidence() == DataConfidenceLevel.NEEDS_VERIFICATION
				|| facility.status() == AccessibilityFacilityStatus.UNKNOWN
				|| facility.status() == AccessibilityFacilityStatus.USER_REPORTED)
			.count();
	}

	private long countMissingStationVerificationDate(List<Station> stations) {
		return stations.stream()
			.filter(station -> station.lastVerifiedAt() == null)
			.count();
	}

	private EnumMap<DataQualityLevel, Long> emptyQualityCounts() {
		var counts = new EnumMap<DataQualityLevel, Long>(DataQualityLevel.class);
		for (DataQualityLevel level : DataQualityLevel.values()) {
			counts.put(level, 0L);
		}
		return counts;
	}

	private EnumMap<DataConfidenceLevel, Long> emptyConfidenceCounts() {
		var counts = new EnumMap<DataConfidenceLevel, Long>(DataConfidenceLevel.class);
		for (DataConfidenceLevel level : DataConfidenceLevel.values()) {
			counts.put(level, 0L);
		}
		return counts;
	}
}

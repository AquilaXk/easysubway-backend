package com.easysubway.quality.application.service;

import com.easysubway.quality.application.port.in.DataQualityUseCase;
import com.easysubway.quality.domain.AccessibilityImprovementPriority;
import com.easysubway.quality.domain.DataQualitySummary;
import com.easysubway.quality.domain.RegionDataQualitySummary;
import com.easysubway.quality.domain.StationAccessibilityScore;
import com.easysubway.transit.application.port.out.LoadTransitMasterPort;
import com.easysubway.transit.domain.AccessibilityFacility;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import com.easysubway.transit.domain.AccessibilityFacilityType;
import com.easysubway.transit.domain.DataConfidenceLevel;
import com.easysubway.transit.domain.DataQualityLevel;
import com.easysubway.transit.domain.Station;
import com.easysubway.transit.domain.StationExit;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class DataQualityService implements DataQualityUseCase {

	private static final int FACILITY_STATUS_DELAY_DAYS = 30;
	private static final int IMPROVEMENT_PRIORITY_LIMIT = 5;

	private final LoadTransitMasterPort loadTransitMasterPort;
	private final Clock clock;

	@Autowired
	public DataQualityService(LoadTransitMasterPort loadTransitMasterPort, ObjectProvider<Clock> clockProvider) {
		this(loadTransitMasterPort, clockProvider.getIfAvailable(Clock::systemDefaultZone));
	}

	public DataQualityService(LoadTransitMasterPort loadTransitMasterPort) {
		this(loadTransitMasterPort, Clock.systemDefaultZone());
	}

	DataQualityService(LoadTransitMasterPort loadTransitMasterPort, Clock clock) {
		this.loadTransitMasterPort = loadTransitMasterPort;
		this.clock = clock;
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
			countDelayedFacilityStatus(facilities),
			countDelayedFacilityStatusByStatus(facilities),
			countMissingStationVerificationDate(stations),
			scoreStationAccessibility(stations, exits, facilities),
			prioritizeAccessibilityImprovements(facilities)
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

	private long countDelayedFacilityStatus(List<AccessibilityFacility> facilities) {
		return facilities.stream()
			.filter(this::isDelayedFacilityStatus)
			.count();
	}

	private Map<AccessibilityFacilityStatus, Long> countDelayedFacilityStatusByStatus(
		List<AccessibilityFacility> facilities
	) {
		var counts = emptyFacilityStatusCounts();
		facilities.stream()
			.filter(this::isDelayedFacilityStatus)
			.forEach(facility -> counts.put(facility.status(), counts.get(facility.status()) + 1));
		return counts;
	}

	private boolean isDelayedFacilityStatus(AccessibilityFacility facility) {
		LocalDate lastUpdatedAt = facility.lastUpdatedAt();
		return lastUpdatedAt == null || lastUpdatedAt.isBefore(LocalDate.now(clock).minusDays(FACILITY_STATUS_DELAY_DAYS));
	}

	private long countMissingStationVerificationDate(List<Station> stations) {
		return stations.stream()
			.filter(station -> station.lastVerifiedAt() == null)
			.count();
	}

	private List<StationAccessibilityScore> scoreStationAccessibility(
		List<Station> stations,
		List<StationExit> exits,
		List<AccessibilityFacility> facilities
	) {
		Map<String, List<StationExit>> exitsByStationId = exits.stream()
			.collect(Collectors.groupingBy(StationExit::stationId));
		Map<String, List<AccessibilityFacility>> facilitiesByStationId = facilities.stream()
			.collect(Collectors.groupingBy(AccessibilityFacility::stationId));
		return stations.stream()
			.map(station -> stationAccessibilityScore(
				station,
				exitsByStationId.getOrDefault(station.id(), List.of()),
				facilitiesByStationId.getOrDefault(station.id(), List.of())
			))
			.sorted(Comparator.comparingInt(StationAccessibilityScore::score)
				.thenComparing(StationAccessibilityScore::region)
				.thenComparing(StationAccessibilityScore::stationName)
				.thenComparing(StationAccessibilityScore::stationId))
			.toList();
	}

	private StationAccessibilityScore stationAccessibilityScore(
		Station station,
		List<StationExit> exits,
		List<AccessibilityFacility> facilities
	) {
		List<String> reasons = new ArrayList<>();
		int score = qualityBaseScore(requireQualityLevel(station), reasons)
			+ exitAccessibilityAdjustment(exits, reasons)
			+ facilityAccessibilityAdjustment(facilities, reasons);
		return new StationAccessibilityScore(
			station.id(),
			station.nameKo(),
			station.region(),
			Math.max(0, Math.min(100, score)),
			reasons.isEmpty() ? List.of("주요 접근성 정보 확인됨") : reasons
		);
	}

	private int qualityBaseScore(DataQualityLevel level, List<String> reasons) {
		if (!level.scoreReason().isBlank()) {
			return addReason(reasons, level.scoreReason(), level.accessibilityScore());
		}
		return level.accessibilityScore();
	}

	private int exitAccessibilityAdjustment(List<StationExit> exits, List<String> reasons) {
		int adjustment = 0;
		if (exits.isEmpty() || exits.stream().noneMatch(this::isStepFreeExit)) {
			adjustment -= addReason(reasons, "계단 없는 출구 부족", 20);
		}
		if (exits.stream().anyMatch(exit -> exit.dataConfidence() == DataConfidenceLevel.LOW
			|| exit.dataConfidence() == DataConfidenceLevel.NEEDS_VERIFICATION)) {
			adjustment -= addReason(reasons, "출구 신뢰도 보강 필요", 10);
		}
		return adjustment;
	}

	private boolean isStepFreeExit(StationExit exit) {
		return exit.hasElevatorConnection() && !exit.hasStairOnlyPath();
	}

	private int facilityAccessibilityAdjustment(List<AccessibilityFacility> facilities, List<String> reasons) {
		int adjustment = 0;
		if (facilities.stream().noneMatch(this::isUsableStepFreeFacility)) {
			adjustment -= addReason(reasons, "정상 접근성 시설 부족", 20);
		}
		if (facilities.stream().anyMatch(this::isAttentionNeededFacilityStatus)) {
			adjustment -= addReason(reasons, "시설 상태 확인 필요", 15);
		}
		if (facilities.stream().anyMatch(facility -> facility.dataConfidence() == DataConfidenceLevel.LOW
			|| facility.dataConfidence() == DataConfidenceLevel.NEEDS_VERIFICATION)) {
			adjustment -= addReason(reasons, "시설 신뢰도 보강 필요", 10);
		}
		if (facilities.stream().anyMatch(this::isDelayedFacilityStatus)) {
			adjustment -= addReason(reasons, "시설 갱신 지연", 5);
		}
		return adjustment;
	}

	private boolean isUsableStepFreeFacility(AccessibilityFacility facility) {
		return switch (facility.type()) {
			case ELEVATOR, WHEELCHAIR_LIFT, RAMP -> facility.status() == AccessibilityFacilityStatus.NORMAL
				|| facility.status() == AccessibilityFacilityStatus.ADMIN_VERIFIED;
			case ESCALATOR, ACCESSIBLE_TOILET, TOILET, NURSING_ROOM, CUSTOMER_CENTER -> false;
		};
	}

	private boolean isAttentionNeededFacilityStatus(AccessibilityFacility facility) {
		return switch (facility.status()) {
			case BROKEN, CLOSED, UNDER_CONSTRUCTION, UNKNOWN, USER_REPORTED -> true;
			case NORMAL, ADMIN_VERIFIED -> false;
		};
	}

	private List<AccessibilityImprovementPriority> prioritizeAccessibilityImprovements(
		List<AccessibilityFacility> facilities
	) {
		return facilities.stream()
			.map(this::accessibilityImprovementPriority)
			.flatMap(Optional::stream)
			.sorted(Comparator.comparingInt(AccessibilityImprovementPriority::priorityScore)
				.reversed()
				.thenComparing(AccessibilityImprovementPriority::stationId)
				.thenComparing(AccessibilityImprovementPriority::facilityId))
			.limit(IMPROVEMENT_PRIORITY_LIMIT)
			.toList();
	}

	private Optional<AccessibilityImprovementPriority> accessibilityImprovementPriority(
		AccessibilityFacility facility
	) {
		List<String> reasons = new ArrayList<>();
		int priorityScore = statusPriorityScore(facility.status(), reasons)
			+ confidencePriorityScore(facility.dataConfidence(), reasons)
			+ delayedPriorityScore(facility, reasons);
		if (priorityScore == 0) {
			return Optional.empty();
		}
		return Optional.of(new AccessibilityImprovementPriority(
			facility.stationId(),
			facility.id(),
			priorityScore,
			reasons
		));
	}

	private int statusPriorityScore(AccessibilityFacilityStatus status, List<String> reasons) {
		return switch (status) {
			case BROKEN -> addReason(reasons, "고장 상태", 50);
			case CLOSED -> addReason(reasons, "폐쇄 상태", 45);
			case UNDER_CONSTRUCTION -> addReason(reasons, "공사 중", 40);
			case UNKNOWN -> addReason(reasons, "확인 필요 상태", 30);
			case USER_REPORTED -> addReason(reasons, "사용자 제보 상태", 30);
			case NORMAL, ADMIN_VERIFIED -> 0;
		};
	}

	private int confidencePriorityScore(DataConfidenceLevel confidenceLevel, List<String> reasons) {
		return switch (confidenceLevel) {
			case NEEDS_VERIFICATION -> addReason(reasons, "신뢰도 확인 필요", 30);
			case LOW -> addReason(reasons, "낮은 신뢰도", 20);
			case HIGH, MEDIUM -> 0;
		};
	}

	private int delayedPriorityScore(AccessibilityFacility facility, List<String> reasons) {
		if (!isDelayedFacilityStatus(facility)) {
			return 0;
		}
		return addReason(reasons, "갱신 지연", 15);
	}

	private int addReason(List<String> reasons, String reason, int score) {
		reasons.add(reason);
		return score;
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

	private EnumMap<AccessibilityFacilityStatus, Long> emptyFacilityStatusCounts() {
		var counts = new EnumMap<AccessibilityFacilityStatus, Long>(AccessibilityFacilityStatus.class);
		for (AccessibilityFacilityStatus status : AccessibilityFacilityStatus.values()) {
			counts.put(status, 0L);
		}
		return counts;
	}
}

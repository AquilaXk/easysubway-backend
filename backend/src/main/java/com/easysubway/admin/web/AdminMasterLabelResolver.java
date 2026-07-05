package com.easysubway.admin.web;

import com.easysubway.transit.application.port.out.LoadTransitMasterPort;
import com.easysubway.transit.domain.AccessibilityFacility;
import com.easysubway.transit.domain.Station;
import com.easysubway.transit.domain.StationExit;
import com.easysubway.transit.domain.StationLine;
import com.easysubway.transit.domain.SubwayLine;
import com.easysubway.transit.domain.TransitOperator;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 관리자 화면 표기 규칙(#1737): 역·시설은 원시 ID 단독이 아니라 항상 "이름(코드)"로 보여준다.
 *
 * <p>화면군 이슈(#1739~)가 재사용한다. 목록의 서로 다른 ID를 모아 한 번에 해석해 N+1을 피한다
 * (마스터 로드는 화면당 1회). 마스터에 없는 코드는 코드 그대로 fallback해 정보를 잃지 않는다.
 */
@Component
public class AdminMasterLabelResolver {

	private final LoadTransitMasterPort loadTransitMasterPort;

	public AdminMasterLabelResolver(LoadTransitMasterPort loadTransitMasterPort) {
		this.loadTransitMasterPort = loadTransitMasterPort;
	}

	/** 마스터 조회 없이 모든 코드를 코드 그대로 반환하는 resolver(경량 단위 테스트용). */
	public static AdminMasterLabelResolver empty() {
		return new AdminMasterLabelResolver(EmptyTransitMasterPort.INSTANCE);
	}

	public Map<String, String> stationLabels(Collection<String> stationIds) {
		Set<String> wanted = normalize(stationIds);
		if (wanted.isEmpty()) {
			return Map.of();
		}
		Map<String, String> labels = new HashMap<>();
		for (Station station : loadTransitMasterPort.loadStations()) {
			if (wanted.contains(station.id())) {
				labels.put(station.id(), label(station.nameKo(), station.id()));
			}
		}
		fillFallback(wanted, labels);
		return labels;
	}

	public Map<String, String> facilityLabels(Collection<String> facilityIds) {
		Set<String> wanted = normalize(facilityIds);
		if (wanted.isEmpty()) {
			return Map.of();
		}
		Map<String, String> labels = new HashMap<>();
		for (AccessibilityFacility facility : loadTransitMasterPort.loadAccessibilityFacilities()) {
			if (wanted.contains(facility.id())) {
				labels.put(facility.id(), label(facility.name(), facility.id()));
			}
		}
		fillFallback(wanted, labels);
		return labels;
	}

	/** "이름(코드)". 이름이 비면 코드만, 코드도 없으면 빈 문자열. */
	public static String label(String name, String code) {
		if (name == null || name.isBlank()) {
			return code == null ? "" : code;
		}
		return name + "(" + code + ")";
	}

	private Set<String> normalize(Collection<String> ids) {
		Set<String> wanted = new HashSet<>();
		for (String id : ids) {
			if (id != null && !id.isBlank()) {
				wanted.add(id);
			}
		}
		return wanted;
	}

	private void fillFallback(Set<String> wanted, Map<String, String> labels) {
		for (String id : wanted) {
			labels.putIfAbsent(id, id);
		}
	}

	private enum EmptyTransitMasterPort implements LoadTransitMasterPort {
		INSTANCE;

		@Override
		public List<TransitOperator> loadOperators() {
			return List.of();
		}

		@Override
		public List<SubwayLine> loadLines() {
			return List.of();
		}

		@Override
		public List<Station> loadStations() {
			return List.of();
		}

		@Override
		public List<StationLine> loadStationLines() {
			return List.of();
		}

		@Override
		public List<StationExit> loadStationExits() {
			return List.of();
		}

		@Override
		public List<AccessibilityFacility> loadAccessibilityFacilities() {
			return List.of();
		}
	}
}

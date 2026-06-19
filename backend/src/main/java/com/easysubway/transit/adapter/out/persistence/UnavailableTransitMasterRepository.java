package com.easysubway.transit.adapter.out.persistence;

import com.easysubway.transit.application.port.out.LoadTransitMasterPort;
import com.easysubway.transit.application.port.out.SaveAccessibilityFacilityStatusPort;
import com.easysubway.transit.application.port.out.SaveRouteEdgePort;
import com.easysubway.transit.application.port.out.SaveRouteNodePort;
import com.easysubway.transit.application.port.out.SaveSimplifiedStationLayoutStatusPort;
import com.easysubway.transit.application.port.out.SaveStationLayoutSourcePort;
import com.easysubway.transit.domain.AccessibilityFacility;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import com.easysubway.transit.domain.RouteEdge;
import com.easysubway.transit.domain.RouteNode;
import com.easysubway.transit.domain.SimplifiedStationLayoutStatus;
import com.easysubway.transit.domain.Station;
import com.easysubway.transit.domain.StationExit;
import com.easysubway.transit.domain.StationLayoutSource;
import com.easysubway.transit.domain.StationLine;
import com.easysubway.transit.domain.SubwayLine;
import com.easysubway.transit.domain.TransitOperator;
import java.time.LocalDate;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("prod")
public class UnavailableTransitMasterRepository implements
	LoadTransitMasterPort,
	SaveAccessibilityFacilityStatusPort,
	SaveStationLayoutSourcePort,
	SaveSimplifiedStationLayoutStatusPort,
	SaveRouteNodePort,
	SaveRouteEdgePort {

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

	@Override
	public void saveFacilityStatus(String facilityId, AccessibilityFacilityStatus status, LocalDate updatedAt) {
		throw unsupportedWriteOperation("saveFacilityStatus");
	}

	@Override
	public void saveAccessibilityFacility(AccessibilityFacility facility) {
		throw unsupportedWriteOperation("saveAccessibilityFacility");
	}

	@Override
	public void saveStationLayoutSource(StationLayoutSource source) {
		throw unsupportedWriteOperation("saveStationLayoutSource");
	}

	@Override
	public void saveSimplifiedStationLayoutStatus(
		String layoutId,
		SimplifiedStationLayoutStatus status,
		String reviewedBy,
		LocalDate updatedAt
	) {
		throw unsupportedWriteOperation("saveSimplifiedStationLayoutStatus");
	}

	@Override
	public void saveRouteNode(RouteNode routeNode) {
		throw unsupportedWriteOperation("saveRouteNode");
	}

	@Override
	public void saveRouteEdge(RouteEdge routeEdge) {
		throw unsupportedWriteOperation("saveRouteEdge");
	}

	private UnsupportedOperationException unsupportedWriteOperation(String operation) {
		return new UnsupportedOperationException(
			"운영 프로필에서는 도시철도 마스터 데이터 쓰기를 지원하지 않습니다: " + operation
		);
	}
}

package com.easysubway.transit.adapter.out.persistence;

import com.easysubway.transit.application.port.out.LoadTransitMasterPort;
import com.easysubway.transit.application.port.out.MasterDataCapability;
import com.easysubway.transit.application.port.out.MasterDataCapabilityPort;
import com.easysubway.transit.application.port.out.MasterDataCapabilityStatus;
import com.easysubway.transit.application.port.out.SaveAccessibilityFacilityStatusPort;
import com.easysubway.transit.application.port.out.SaveRouteEdgePort;
import com.easysubway.transit.application.port.out.SaveRouteNodePort;
import com.easysubway.transit.application.port.out.SaveSimplifiedStationLayoutStatusPort;
import com.easysubway.transit.application.port.out.SaveStationLayoutSourcePort;
import com.easysubway.transit.domain.AccessibilityFacility;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import com.easysubway.transit.domain.RouteEdge;
import com.easysubway.transit.domain.RouteNode;
import com.easysubway.transit.domain.SimplifiedStationLayout;
import com.easysubway.transit.domain.SimplifiedStationLayoutStatus;
import com.easysubway.transit.domain.Station;
import com.easysubway.transit.domain.StationExit;
import com.easysubway.transit.domain.StationLayoutSource;
import com.easysubway.transit.domain.StationLine;
import com.easysubway.transit.domain.SubwayLine;
import com.easysubway.transit.domain.TransitOperator;
import java.time.LocalDate;
import java.util.List;

public class UnavailableTransitMasterRepository implements
	LoadTransitMasterPort,
	MasterDataCapabilityPort,
	SaveAccessibilityFacilityStatusPort,
	SaveStationLayoutSourcePort,
	SaveSimplifiedStationLayoutStatusPort,
	SaveRouteNodePort,
	SaveRouteEdgePort {

	// ponytail: reuse the existing static seed until a real data-pack adapter exists.
	private final InMemoryTransitMasterRepository seedRepository = new InMemoryTransitMasterRepository();

	@Override
	public MasterDataCapability masterDataCapability() {
		return new MasterDataCapability(MasterDataCapabilityStatus.READ_ONLY, true, false, "static-seed", "unavailable", null);
	}

	@Override
	public List<TransitOperator> loadOperators() {
		return seedRepository.loadOperators();
	}

	@Override
	public List<SubwayLine> loadLines() {
		return seedRepository.loadLines();
	}

	@Override
	public List<Station> loadStations() {
		return seedRepository.loadStations();
	}

	@Override
	public List<StationLine> loadStationLines() {
		return seedRepository.loadStationLines();
	}

	@Override
	public List<StationExit> loadStationExits() {
		return seedRepository.loadStationExits();
	}

	@Override
	public List<AccessibilityFacility> loadAccessibilityFacilities() {
		return seedRepository.loadAccessibilityFacilities();
	}

	@Override
	public List<StationLayoutSource> loadStationLayoutSources() {
		return seedRepository.loadStationLayoutSources();
	}

	@Override
	public List<SimplifiedStationLayout> loadSimplifiedStationLayouts() {
		return seedRepository.loadSimplifiedStationLayouts();
	}

	@Override
	public List<RouteNode> loadRouteNodes() {
		return seedRepository.loadRouteNodes();
	}

	@Override
	public List<RouteEdge> loadRouteEdges() {
		return seedRepository.loadRouteEdges();
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

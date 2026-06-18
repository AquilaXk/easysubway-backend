package com.easysubway.transit.adapter.out.persistence;

import com.easysubway.transit.application.port.out.LoadTransitMasterPort;
import com.easysubway.transit.application.port.out.SaveAccessibilityFacilityStatusPort;
import com.easysubway.transit.application.port.out.SaveRouteNodePort;
import com.easysubway.transit.application.port.out.SaveSimplifiedStationLayoutStatusPort;
import com.easysubway.transit.domain.AccessibilityFacility;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import com.easysubway.transit.domain.AccessibilityFacilityType;
import com.easysubway.transit.domain.DataConfidenceLevel;
import com.easysubway.transit.domain.DataQualityLevel;
import com.easysubway.transit.domain.DataSourceType;
import com.easysubway.transit.domain.RouteEdge;
import com.easysubway.transit.domain.RouteEdgeType;
import com.easysubway.transit.domain.RouteNode;
import com.easysubway.transit.domain.RouteNodeType;
import com.easysubway.transit.domain.Station;
import com.easysubway.transit.domain.StationExit;
import com.easysubway.transit.domain.StationLayoutSource;
import com.easysubway.transit.domain.StationLayoutSourceType;
import com.easysubway.transit.domain.StationLine;
import com.easysubway.transit.domain.SimplifiedStationLayout;
import com.easysubway.transit.domain.SimplifiedStationLayoutConfidence;
import com.easysubway.transit.domain.SimplifiedStationLayoutStatus;
import com.easysubway.transit.domain.SubwayLine;
import com.easysubway.transit.domain.TransitOperator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!prod")
public class InMemoryTransitMasterRepository implements
	LoadTransitMasterPort,
	SaveAccessibilityFacilityStatusPort,
	SaveSimplifiedStationLayoutStatusPort,
	SaveRouteNodePort {

	private static final List<TransitOperator> OPERATORS = List.of(
		new TransitOperator(
			"seoul-metro",
			"서울교통공사",
			"수도권",
			"https://www.seoulmetro.co.kr",
			"https://www.seoulmetro.co.kr/kr/customerMain.do",
			DataSourceType.OFFICIAL_FILE,
			true
		),
		new TransitOperator(
			"korail",
			"한국철도공사",
			"수도권",
			"https://www.letskorail.com",
			"https://info.korail.com",
			DataSourceType.OFFICIAL_FILE,
			true
		)
	);

	private static final List<SubwayLine> LINES = List.of(
		new SubwayLine("seoul-4", "seoul-metro", "수도권 4호선", "#00A5DE", "수도권", "4", true),
		new SubwayLine("suin-bundang", "korail", "수인분당선", "#F5A200", "수도권", "K1", true)
	);

	private static final List<Station> STATIONS = List.of(
		new Station(
			"station-sangnoksu",
			"상록수",
			"Sangnoksu",
			"수도권",
			new BigDecimal("37.302795"),
			new BigDecimal("126.866489"),
			DataQualityLevel.LEVEL_1,
			DataSourceType.OFFICIAL_FILE,
			LocalDate.of(2026, 6, 12),
			true
		),
		new Station(
			"station-sadang",
			"사당",
			"Sadang",
			"수도권",
			new BigDecimal("37.476530"),
			new BigDecimal("126.981685"),
			DataQualityLevel.LEVEL_1,
			DataSourceType.OFFICIAL_FILE,
			LocalDate.of(2026, 6, 12),
			true
		)
	);

	private static final List<StationLine> STATION_LINES = List.of(
		new StationLine("station-sangnoksu", "seoul-4", "448", 48, "당고개 방면 / 오이도 방면"),
		new StationLine("station-sadang", "seoul-4", "433", 33, "당고개 방면 / 오이도 방면")
	);

	private static final List<StationExit> STATION_EXITS = List.of(
		new StationExit(
			"exit-sangnoksu-1",
			"station-sangnoksu",
			"1",
			"1번 출구",
			new BigDecimal("37.302421"),
			new BigDecimal("126.866221"),
			true,
			false,
			DataConfidenceLevel.HIGH,
			DataSourceType.OFFICIAL_FILE
		),
		new StationExit(
			"exit-sangnoksu-2",
			"station-sangnoksu",
			"2",
			"2번 출구",
			new BigDecimal("37.303041"),
			new BigDecimal("126.866768"),
			false,
			true,
			DataConfidenceLevel.MEDIUM,
			DataSourceType.OFFICIAL_FILE
		),
		new StationExit(
			"exit-sadang-2",
			"station-sadang",
			"2",
			"2번 출구",
			new BigDecimal("37.476208"),
			new BigDecimal("126.982157"),
			true,
			false,
			DataConfidenceLevel.HIGH,
			DataSourceType.OFFICIAL_FILE
		)
	);

	private static final List<StationLayoutSource> STATION_LAYOUT_SOURCES = List.of(
		// 저작권 리스크가 있는 원본 도면은 저장하지 않고, 구조도 단순화에 사용한 출처 메타데이터만 보관한다.
		new StationLayoutSource(
			"layout-source-sangnoksu-station-map",
			"station-sangnoksu",
			StationLayoutSourceType.OPERATOR_DIAGRAM,
			"상록수역 역사 안내도",
			"https://www.seoulmetro.co.kr",
			"운영기관 안내도 확인용",
			false,
			true,
			LocalDate.of(2026, 6, 12),
			LocalDate.of(2026, 6, 12)
		)
	);

	private static final List<SimplifiedStationLayout> SIMPLIFIED_STATION_LAYOUTS = List.of(
		new SimplifiedStationLayout(
			"layout-sangnoksu-draft",
			"station-sangnoksu",
			1,
			SimplifiedStationLayoutStatus.DRAFT,
			List.of("layout-source-sangnoksu-station-map"),
			SimplifiedStationLayoutConfidence.OFFICIAL_DIAGRAM_REFERENCED,
			"B1",
			"{\"nodes\":[],\"edges\":[]}",
			null,
			"admin-user",
			null,
			null,
			LocalDate.of(2026, 6, 12)
		)
	);

	private static final List<RouteNode> ROUTE_NODES = List.of(
		new RouteNode(
			"node-sangnoksu-elevator-1",
			"station-sangnoksu",
			RouteNodeType.ELEVATOR,
			"1번 출구 엘리베이터",
			"B1",
			new BigDecimal("37.302421"),
			new BigDecimal("126.866221"),
			"facility-sangnoksu-elevator-1",
			"layout-sangnoksu-draft",
			120,
			240,
			"엘리베이터",
			"휠체어 이동 가능"
		),
		new RouteNode(
			"node-sangnoksu-faregate",
			"station-sangnoksu",
			RouteNodeType.FAREGATE,
			"개찰구",
			"B1",
			null,
			null,
			null,
			"layout-sangnoksu-draft",
			260,
			240,
			"개찰구",
			null
		)
	);

	private static final List<RouteEdge> ROUTE_EDGES = List.of(
		new RouteEdge(
			"edge-sangnoksu-elevator-to-faregate",
			"station-sangnoksu",
			"node-sangnoksu-elevator-1",
			"node-sangnoksu-faregate",
			RouteEdgeType.WALK,
			28,
			75,
			false,
			true,
			false,
			1,
			2,
			92,
			true
		)
	);

	private final Map<String, AccessibilityFacility> accessibilityFacilities = new LinkedHashMap<>();
	private final Map<String, SimplifiedStationLayout> simplifiedStationLayouts = new LinkedHashMap<>();
	private final Map<String, RouteNode> routeNodes = new LinkedHashMap<>();

	public InMemoryTransitMasterRepository() {
		seedAccessibilityFacilities();
		seedSimplifiedStationLayouts();
		seedRouteNodes();
	}

	@Override
	public List<TransitOperator> loadOperators() {
		return OPERATORS;
	}

	@Override
	public List<SubwayLine> loadLines() {
		return LINES;
	}

	@Override
	public List<Station> loadStations() {
		return STATIONS;
	}

	@Override
	public List<StationLine> loadStationLines() {
		return STATION_LINES;
	}

	@Override
	public List<StationExit> loadStationExits() {
		return STATION_EXITS;
	}

	@Override
	public List<AccessibilityFacility> loadAccessibilityFacilities() {
		return List.copyOf(accessibilityFacilities.values());
	}

	@Override
	public List<StationLayoutSource> loadStationLayoutSources() {
		return STATION_LAYOUT_SOURCES;
	}

	@Override
	public List<SimplifiedStationLayout> loadSimplifiedStationLayouts() {
		return List.copyOf(simplifiedStationLayouts.values());
	}

	@Override
	public List<RouteNode> loadRouteNodes() {
		return List.copyOf(routeNodes.values());
	}

	@Override
	public List<RouteEdge> loadRouteEdges() {
		return ROUTE_EDGES;
	}

	@Override
	public void saveFacilityStatus(String facilityId, AccessibilityFacilityStatus status, LocalDate updatedAt) {
		AccessibilityFacility facility = accessibilityFacilities.get(facilityId);
		if (facility == null) {
			// 신고 생성 단계에서 시설 존재 여부를 검증하므로 저장 어댑터는 알 수 없는 식별자를 무시한다.
			return;
		}

		accessibilityFacilities.put(facilityId, new AccessibilityFacility(
			facility.id(),
			facility.stationId(),
			facility.exitId(),
			facility.type(),
			facility.name(),
			facility.floorFrom(),
			facility.floorTo(),
			facility.latitude(),
			facility.longitude(),
			facility.description(),
			status,
			facility.dataConfidence(),
			facility.dataSourceType(),
			updatedAt
		));
	}

	@Override
	public void saveAccessibilityFacility(AccessibilityFacility facility) {
		accessibilityFacilities.put(facility.id(), facility);
	}

	@Override
	public void saveSimplifiedStationLayoutStatus(
		String layoutId,
		SimplifiedStationLayoutStatus status,
		String reviewedBy,
		LocalDate updatedAt
	) {
		SimplifiedStationLayout layout = simplifiedStationLayouts.get(layoutId);
		if (layout == null) {
			return;
		}

		simplifiedStationLayouts.put(layoutId, new SimplifiedStationLayout(
			layout.id(),
			layout.stationId(),
			layout.version(),
			status,
			layout.sourceIds(),
			layout.confidenceLevel(),
			layout.baseFloor(),
			layout.layoutJson(),
			layout.renderedPreviewUrl(),
			layout.createdBy(),
			reviewedBy,
			status == SimplifiedStationLayoutStatus.PUBLISHED ? updatedAt : layout.publishedAt(),
			updatedAt
		));
	}

	@Override
	public void saveRouteNode(RouteNode routeNode) {
		routeNodes.put(routeNode.id(), routeNode);
	}

	private void seedAccessibilityFacilities() {
		saveSeedFacility(new AccessibilityFacility(
			"facility-sangnoksu-elevator-1",
			"station-sangnoksu",
			"exit-sangnoksu-1",
			AccessibilityFacilityType.ELEVATOR,
			"1번 출구 엘리베이터",
			"지상",
			"대합실",
			new BigDecimal("37.302421"),
			new BigDecimal("126.866221"),
			"1번 출구와 대합실을 연결합니다.",
			AccessibilityFacilityStatus.NORMAL,
			DataConfidenceLevel.HIGH,
			DataSourceType.OFFICIAL_FILE,
			LocalDate.of(2026, 6, 12)
		));
		saveSeedFacility(new AccessibilityFacility(
			"facility-sangnoksu-escalator-1",
			"station-sangnoksu",
			"exit-sangnoksu-1",
			AccessibilityFacilityType.ESCALATOR,
			"1번 출구 에스컬레이터",
			"지상",
			"대합실",
			new BigDecimal("37.302444"),
			new BigDecimal("126.866250"),
			"1번 출구 방향 상행 에스컬레이터입니다.",
			AccessibilityFacilityStatus.NORMAL,
			DataConfidenceLevel.MEDIUM,
			DataSourceType.OFFICIAL_FILE,
			LocalDate.of(2026, 6, 12)
		));
		saveSeedFacility(new AccessibilityFacility(
			"facility-sangnoksu-accessible-toilet",
			"station-sangnoksu",
			null,
			AccessibilityFacilityType.ACCESSIBLE_TOILET,
			"장애인 화장실",
			"대합실",
			"대합실",
			new BigDecimal("37.302820"),
			new BigDecimal("126.866401"),
			"개찰구 안쪽 대합실에 있습니다.",
			AccessibilityFacilityStatus.UNKNOWN,
			DataConfidenceLevel.NEEDS_VERIFICATION,
			DataSourceType.OFFICIAL_FILE,
			LocalDate.of(2026, 6, 12)
		));
	}

	private void saveSeedFacility(AccessibilityFacility facility) {
		accessibilityFacilities.put(facility.id(), facility);
	}

	private void seedSimplifiedStationLayouts() {
		SIMPLIFIED_STATION_LAYOUTS.forEach(layout -> simplifiedStationLayouts.put(layout.id(), layout));
	}

	private void seedRouteNodes() {
		ROUTE_NODES.forEach(routeNode -> routeNodes.put(routeNode.id(), routeNode));
	}
}

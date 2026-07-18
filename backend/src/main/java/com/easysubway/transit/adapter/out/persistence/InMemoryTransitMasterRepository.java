package com.easysubway.transit.adapter.out.persistence;

import com.easysubway.transit.application.port.out.LoadTransitMasterPort;
import com.easysubway.transit.application.port.out.SaveAccessibilityFacilityStatusPort;
import com.easysubway.transit.application.port.out.SaveRouteEdgePort;
import com.easysubway.transit.application.port.out.SaveRouteNodePort;
import com.easysubway.transit.application.port.out.SaveStationLayoutSourcePort;
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
@Profile("!prod & !staging & !release & !prod-like")
public class InMemoryTransitMasterRepository implements
	LoadTransitMasterPort,
	SaveAccessibilityFacilityStatusPort,
	SaveStationLayoutSourcePort,
	SaveSimplifiedStationLayoutStatusPort,
	SaveRouteNodePort,
	SaveRouteEdgePort {

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
		new SubwayLine("suin-bundang", "korail", "수인분당선", "#F5A200", "수도권", "K1", true),
		// ITX-청춘(경춘선) pilot 노선 — 위 14개 pilot 정차역을 연결한다(#2095 PR #2286
		// 리뷰 지적: 역만 있고 노선·역-노선 연결이 없어 강원권 노선/운영기관 집계가
		// 부정확하게 0으로 나오는 orphan 상태였다). color는 tools/route-map/route-map-line-colors.json
		// ("수도권 경춘": #0c8e72, 출처 위키백과 틀:한국 철도 노선색)에서 그대로 가져온
		// 실데이터다. region은 "강원권"으로 뒀다 — 이 노선은 수도권(11역)과 강원권(3역)에
		// 걸쳐 있지만 SubwayLine.region은 단일 값만 담을 수 있어 두 지역을 동시에 표현할
		// 수 없다. 수도권은 이미 seoul-4/suin-bundang으로 lineCount가 채워져 있었던 반면
		// 강원권은 이 노선이 연결되기 전까지 0이었던(=orphan 증상 그 자체였던) 쪽이라
		// region="강원권"으로 둬 그 불일치를 직접 해소했다. lineCode는 Korail이 경춘선에
		// suin-bundang의 "K1"급으로 공식 부여한 짧은 코드를 이 저장소 소스에서 확인하지
		// 못해 지어내지 않고 빈 문자열로 뒀다(강원권 집계·capacity 어디에도 쓰이지 않는
		// 순수 표시용 필드).
		//
		// 운영기관(TransitOperator) 쪽은 이 PR에서 건드리지 않았다 — korail은 이미 존재하는
		// 단일 실제 법인이고 그 region 필드는 suin-bundang 때부터 "수도권"으로 고정돼 있다.
		// 강원권 operatorCount를 억지로 채우려고 "korail-gangwon" 같은 실재하지 않는 두 번째
		// 운영기관을 만들어내는 것은 조직을 지어내는 것이라 하지 않았다 — 강원권
		// operatorCount=0은 "이 스키마가 단일-지역 운영기관만 표현할 수 있다"는 알려진
		// 한계이지 데이터 누락이 아니다.
		new SubwayLine("itx-cheongchun", "korail", "ITX-청춘", "#0c8e72", "강원권", "", true)
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
		),
		// ITX-청춘(경춘선) pilot 정차역 14곳 — Route V2 capacity evidence(#2095)가 검증하는
		// pilot scope. id·이름·정차 순서는 tools/datapack/sources/itx-cheongchun-source-timetable-20260715152903681.json
		// (stationRosters, providerStationId·providerStationName·canonicalStationId·corridorSequence,
		// 원출처 data.go.kr 열린데이터광장 KRIC API — 같은 소스의
		// korail-itx-cheongchun-station-sequence-20260713.json officialSourceUrl 참고)에서
		// 그대로 가져왔고, production 격리 클론의 실제 transit_stop_times/transit_trips
		// (service_class='ITX_CHEONGCHUN') stop_sequence 순서와 대조해 일치를 확인했다
		// (#2095). nameEn은 국립국어원 로마자 표기법(코레일 역명판 표기와 동일) 표준 변환이다.
		// region은 행정구역 기준(가평까지 경기도=수도권, 강촌부터 강원도=강원권)이며 정밀
		// 좌표가 아니므로 창작이 아니다.
		//
		// 위도·경도는 이 datapack 소스에 없어 만들어내지 않고 0,0을 자리표시자로 쓴다.
		// 이 클래스는 !prod 프로필이지만, prod에서 활성화되는
		// JdbcTransitMasterOverrideRepository가 loadStations()를 오버라이드하지 않고
		// UnavailableTransitMasterRepository를 거쳐 이 STATIONS를 그대로 상속하므로,
		// 이 0,0은 dev 격리에 머물지 않고 prod seed까지 그대로 도달한다(예: 관리자
		// 역 상세/편집 화면에 0/0으로 노출됨). null을 쓰면 TransitMasterService의
		// "인근 역 검색"(distanceMeters())이 이 14역에 대해 NullPointerException을
		// 내므로 그 대안은 아니다. 현재는 capacity 스크립트가 검증하는 Route V2
		// search 경로(RouteV2Planner)도, 공개 nearby-search 엔드포인트도 이 14역의
		// 좌표를 소비하지 않아(loadActiveStation()은 존재·active 여부만 확인) 무해하지만,
		// #2098 real data-pack adapter로 실좌표가 반입되기 전에 nearby-search가 이
		// 데이터에 배선되면 0,0이 실제 위치로 오응답에 섞여들 수 있는 잠복 리스크가
		// 있다는 점은 명시해둔다. 실좌표 반입은 #2098 범위다.
		itxCheongchunPilotStation("station-8aa315864466", "용산", "Yongsan"),
		itxCheongchunPilotStation("station-c0679b9a6cf8", "옥수", "Oksu"),
		itxCheongchunPilotStation("station-e5cf592cf355", "왕십리", "Wangsimni"),
		itxCheongchunPilotStation("station-b819702fa7d9", "청량리", "Cheongnyangni"),
		itxCheongchunPilotStation("station-83bcb1eae340", "상봉", "Sangbong"),
		itxCheongchunPilotStation("station-b52ac4dfe64e", "퇴계원", "Toegyewon"),
		itxCheongchunPilotStation("station-2ccf5647f7f7", "사릉", "Sareung"),
		itxCheongchunPilotStation("station-f3d9c93ba7d6", "평내호평", "Pyeongnae-Hopyeong"),
		itxCheongchunPilotStation("station-661ff65ea040", "마석", "Maseok"),
		itxCheongchunPilotStation("station-6c1f50a5aa3b", "청평", "Cheongpyeong"),
		itxCheongchunPilotStation("station-4f6045ff9103", "가평", "Gapyeong"),
		itxCheongchunPilotStation("station-30ba86472e55", "강촌", "Gangchon", "강원권"),
		itxCheongchunPilotStation("station-d5e344125b52", "남춘천", "Namchuncheon", "강원권"),
		itxCheongchunPilotStation("station-dd14cfb89cbc", "춘천", "Chuncheon", "강원권")
	);

	private static Station itxCheongchunPilotStation(String id, String nameKo, String nameEn) {
		return itxCheongchunPilotStation(id, nameKo, nameEn, "수도권");
	}

	private static Station itxCheongchunPilotStation(String id, String nameKo, String nameEn, String region) {
		return new Station(
			id,
			nameKo,
			nameEn,
			region,
			BigDecimal.ZERO,
			BigDecimal.ZERO,
			DataQualityLevel.LEVEL_1,
			DataSourceType.OFFICIAL_FILE,
			LocalDate.of(2026, 7, 15),
			true
		);
	}

	private static final List<StationLine> STATION_LINES = List.of(
		new StationLine("station-sangnoksu", "seoul-4", "448", 48, "당고개 방면 / 오이도 방면"),
		new StationLine("station-sadang", "seoul-4", "433", 33, "당고개 방면 / 오이도 방면"),
		// stationCode는 tools/datapack/sources/itx-cheongchun-source-timetable-20260715152903681.json
		// stationRosters의 providerStationId(KRIC 제공 역 식별자)를 그대로 썼다 — seoul-4의
		// "448" 같은 노선도 상 공식 역번호 체계와는 다른 provider 고유 코드이지만, 실제로
		// KRIC API가 발급한 값이라 지어낸 것은 아니다. sequence는 같은 소스의
		// corridorSequence(광운대·대성리·백양리·김유정 등 완행 전용역을 포함한 경춘선 전체
		// 정차 순서)를 그대로 썼다 — RouteSearchService의 RAPTOR 그래프는 같은 lineId의
		// 역이면 sequence 값과 무관하게 모두 직접 연결(1구간)로 취급하고, sequence는
		// 비용 추정(Math.abs 차이)에만 쓰인다. 그래서 1..14로 다시 매기지 않고 실제
		// corridorSequence를 그대로 둔 편이 건너뛴 완행 전용역만큼의 물리적 거리를 더
		// 정확히 반영한다. platformInfo는 seoul-4 예시(노선의 양방향 종점 방면)와 같은
		// 형식으로 "용산 방면 / 춘천 방면"을 모든 역에 공통 적용했다.
		new StationLine("station-8aa315864466", "itx-cheongchun", "NAT010032", 1, "용산 방면 / 춘천 방면"),
		new StationLine("station-c0679b9a6cf8", "itx-cheongchun", "NAT130070", 2, "용산 방면 / 춘천 방면"),
		new StationLine("station-e5cf592cf355", "itx-cheongchun", "NAT130104", 3, "용산 방면 / 춘천 방면"),
		new StationLine("station-b819702fa7d9", "itx-cheongchun", "NAT130126", 4, "용산 방면 / 춘천 방면"),
		new StationLine("station-83bcb1eae340", "itx-cheongchun", "NAT020040", 7, "용산 방면 / 춘천 방면"),
		new StationLine("station-b52ac4dfe64e", "itx-cheongchun", "NAT140098", 12, "용산 방면 / 춘천 방면"),
		new StationLine("station-2ccf5647f7f7", "itx-cheongchun", "NAT140133", 13, "용산 방면 / 춘천 방면"),
		new StationLine("station-f3d9c93ba7d6", "itx-cheongchun", "NAT140214", 15, "용산 방면 / 춘천 방면"),
		new StationLine("station-661ff65ea040", "itx-cheongchun", "NAT140277", 17, "용산 방면 / 춘천 방면"),
		new StationLine("station-6c1f50a5aa3b", "itx-cheongchun", "NAT140436", 19, "용산 방면 / 춘천 방면"),
		new StationLine("station-4f6045ff9103", "itx-cheongchun", "NAT140576", 21, "용산 방면 / 춘천 방면"),
		new StationLine("station-30ba86472e55", "itx-cheongchun", "NAT140701", 24, "용산 방면 / 춘천 방면"),
		new StationLine("station-d5e344125b52", "itx-cheongchun", "NAT140840", 26, "용산 방면 / 춘천 방면"),
		new StationLine("station-dd14cfb89cbc", "itx-cheongchun", "NAT140873", 27, "용산 방면 / 춘천 방면")
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
	private final Map<String, StationLayoutSource> stationLayoutSources = new LinkedHashMap<>();
	private final Map<String, SimplifiedStationLayout> simplifiedStationLayouts = new LinkedHashMap<>();
	private final Map<String, RouteNode> routeNodes = new LinkedHashMap<>();
	private final Map<String, RouteEdge> routeEdges = new LinkedHashMap<>();

	public InMemoryTransitMasterRepository() {
		seedAccessibilityFacilities();
		seedStationLayoutSources();
		seedSimplifiedStationLayouts();
		seedRouteNodes();
		seedRouteEdges();
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
		return List.copyOf(stationLayoutSources.values());
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
		return List.copyOf(routeEdges.values());
	}

	@Override
	public void saveFacilityStatus(String facilityId, AccessibilityFacilityStatus status, LocalDate updatedAt) {
		AccessibilityFacility facility = accessibilityFacilities.get(facilityId);
		if (facility == null) {
			// 신고 생성 단계에서 시설 존재 여부를 확인하므로 저장 어댑터는 알 수 없는 식별자를 무시한다.
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
	public void saveStationLayoutSource(StationLayoutSource source) {
		stationLayoutSources.put(source.id(), source);
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
			layout.version() + 1,
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

	@Override
	public void saveRouteEdge(RouteEdge routeEdge) {
		routeEdges.put(routeEdge.id(), routeEdge);
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

	private void seedStationLayoutSources() {
		STATION_LAYOUT_SOURCES.forEach(source -> stationLayoutSources.put(source.id(), source));
	}

	private void seedRouteNodes() {
		ROUTE_NODES.forEach(routeNode -> routeNodes.put(routeNode.id(), routeNode));
	}

	private void seedRouteEdges() {
		ROUTE_EDGES.forEach(routeEdge -> routeEdges.put(routeEdge.id(), routeEdge));
	}
}

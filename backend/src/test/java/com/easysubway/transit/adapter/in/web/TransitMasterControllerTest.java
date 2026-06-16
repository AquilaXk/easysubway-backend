package com.easysubway.transit.adapter.in.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
	"easysubway.admin.username=admin-user",
	"easysubway.admin.password=admin-test-password",
	"easysubway.user.username=basic-user",
	"easysubway.user.password=user-test-password"
})
@AutoConfigureMockMvc
@DisplayName("도시철도 마스터데이터 API")
class TransitMasterControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("지역 목록은 활성 마스터데이터 수와 데이터 품질 분포를 반환한다")
	void regionsReturnActiveMasterDataCountsAndQualityCounts() throws Exception {
		mockMvc.perform(get("/api/v1/regions"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data[0].name").value("수도권"))
			.andExpect(jsonPath("$.data[0].operatorCount").value(2))
			.andExpect(jsonPath("$.data[0].lineCount").value(2))
			.andExpect(jsonPath("$.data[0].stationCount").value(2))
			.andExpect(jsonPath("$.data[0].dataQualityCounts.LEVEL_1").value(2));
	}

	@Test
	@DisplayName("운영기관 목록은 시드된 활성 기관을 반환한다")
	void operatorsReturnsSeededTransitOperators() throws Exception {
		mockMvc.perform(get("/api/v1/operators"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data[0].id").value("seoul-metro"))
			.andExpect(jsonPath("$.data[0].name").value("서울교통공사"))
			.andExpect(jsonPath("$.data[0].region").value("수도권"))
			.andExpect(jsonPath("$.data[0].dataSourceType").value("OFFICIAL_FILE"))
			.andExpect(jsonPath("$.data[0].active").value(true));
	}

	@Test
	@DisplayName("노선 목록은 운영기관으로 필터링할 수 있다")
	void linesCanBeFilteredByOperator() throws Exception {
		mockMvc.perform(get("/api/v1/lines").param("operatorId", "seoul-metro"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data[0].id").value("seoul-4"))
			.andExpect(jsonPath("$.data[0].operatorId").value("seoul-metro"))
			.andExpect(jsonPath("$.data[0].name").value("수도권 4호선"))
			.andExpect(jsonPath("$.data[0].color").value("#00A5DE"));
	}

	@Test
	@DisplayName("역 검색은 한글 역명과 데이터 출처로 조회할 수 있다")
	void stationsCanBeSearchedByKoreanName() throws Exception {
		mockMvc.perform(get("/api/v1/stations").param("query", "상록수"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data[0].id").value("station-sangnoksu"))
			.andExpect(jsonPath("$.data[0].nameKo").value("상록수"))
			.andExpect(jsonPath("$.data[0].dataQualityLevel").value("LEVEL_1"))
			.andExpect(jsonPath("$.data[0].dataSourceType").value("OFFICIAL_FILE"))
			.andExpect(jsonPath("$.data[0].lines[0].id").value("seoul-4"));
	}

	@Test
	@DisplayName("역 검색은 한글 초성만 입력해도 조회할 수 있다")
	void stationsCanBeSearchedByKoreanInitialConsonants() throws Exception {
		mockMvc.perform(get("/api/v1/stations").param("query", "ㅅㄹㅅ"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data[0].id").value("station-sangnoksu"))
			.andExpect(jsonPath("$.data[0].nameKo").value("상록수"));
	}

	@Test
	@DisplayName("공개 역 검색은 잘못된 Basic 인증 헤더가 있어도 허용한다")
	void publicStationSearchIgnoresInvalidBasicAuthentication() throws Exception {
		mockMvc.perform(get("/api/v1/stations")
				.param("query", "상록수")
				.with(httpBasic("wrong-admin", "wrong-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data[0].id").value("station-sangnoksu"));
	}

	@Test
	@DisplayName("역 상세는 연결 노선과 데이터 품질과 출처를 포함한다")
	void stationDetailIncludesConnectedLinesAndQuality() throws Exception {
		mockMvc.perform(get("/api/v1/stations/station-sangnoksu"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.id").value("station-sangnoksu"))
			.andExpect(jsonPath("$.data.nameEn").value("Sangnoksu"))
			.andExpect(jsonPath("$.data.latitude").value(37.302795))
			.andExpect(jsonPath("$.data.longitude").value(126.866489))
			.andExpect(jsonPath("$.data.dataQualityLevel").value("LEVEL_1"))
			.andExpect(jsonPath("$.data.dataSourceType").value("OFFICIAL_FILE"))
			.andExpect(jsonPath("$.data.lines[0].stationCode").value("448"));
	}

	@Test
	@DisplayName("가까운 역 조회는 현재 위치 기준으로 역을 거리순 반환한다")
	void nearbyStationsReturnStationsOrderedByDistance() throws Exception {
		mockMvc.perform(get("/api/v1/stations/nearby")
				.param("lat", "37.302800")
				.param("lng", "126.866500")
				.param("radiusMeters", "30000"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data[0].id").value("station-sangnoksu"))
			.andExpect(jsonPath("$.data[0].nameKo").value("상록수"))
			.andExpect(jsonPath("$.data[0].distanceMeters").isNumber())
			.andExpect(jsonPath("$.data[0].lines[0].id").value("seoul-4"))
			.andExpect(jsonPath("$.data[1].id").value("station-sadang"));
	}

	@Test
	@DisplayName("가까운 역 조회는 반경 밖 역을 제외한다")
	void nearbyStationsExcludeStationsOutsideRadius() throws Exception {
		mockMvc.perform(get("/api/v1/stations/nearby")
				.param("lat", "37.302800")
				.param("lng", "126.866500")
				.param("radiusMeters", "500"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.length()").value(1))
			.andExpect(jsonPath("$.data[0].id").value("station-sangnoksu"));
	}

	@Test
	@DisplayName("가까운 역 조회는 올바른 좌표를 요구한다")
	void nearbyStationsRequireValidCoordinates() throws Exception {
		mockMvc.perform(get("/api/v1/stations/nearby")
				.param("lat", "91.0")
				.param("lng", "126.866500"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("위도는 -90부터 90 사이여야 합니다."));
	}

	@Test
	@DisplayName("가까운 역 조회는 좌표 누락도 공통 오류 응답으로 반환한다")
	void nearbyStationsReturnCommonErrorWhenCoordinatesAreMissing() throws Exception {
		mockMvc.perform(get("/api/v1/stations/nearby")
				.param("lng", "126.866500"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("위도가 필요합니다."));
	}

	@Test
	@DisplayName("역 출구 목록은 접근성 신호와 데이터 출처를 포함한다")
	void stationExitsIncludeAccessibilitySignals() throws Exception {
		mockMvc.perform(get("/api/v1/stations/station-sangnoksu/exits"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data[0].id").value("exit-sangnoksu-1"))
			.andExpect(jsonPath("$.data[0].exitNumber").value("1"))
			.andExpect(jsonPath("$.data[0].name").value("1번 출구"))
			.andExpect(jsonPath("$.data[0].hasElevatorConnection").value(true))
			.andExpect(jsonPath("$.data[0].hasStairOnlyPath").value(false))
			.andExpect(jsonPath("$.data[0].dataSourceType").value("OFFICIAL_FILE"))
			.andExpect(jsonPath("$.data[0].dataConfidence").value("HIGH"));
	}

	@Test
	@DisplayName("역 시설 목록은 시설 유형과 상태와 신뢰도와 출처를 포함한다")
	void stationFacilitiesIncludeTypeStatusAndConfidence() throws Exception {
		mockMvc.perform(get("/api/v1/stations/station-sangnoksu/facilities"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data[0].id").value("facility-sangnoksu-elevator-1"))
			.andExpect(jsonPath("$.data[0].type").value("ELEVATOR"))
			.andExpect(jsonPath("$.data[0].name").value("1번 출구 엘리베이터"))
			.andExpect(jsonPath("$.data[0].exitId").value("exit-sangnoksu-1"))
			.andExpect(jsonPath("$.data[0].status").value("NORMAL"))
			.andExpect(jsonPath("$.data[0].dataConfidence").value("HIGH"))
			.andExpect(jsonPath("$.data[0].dataSourceType").value("OFFICIAL_FILE"))
			.andExpect(jsonPath("$.data[0].lastUpdatedAt").value("2026-06-12"));
	}

	@Test
	@DisplayName("존재하지 않는 역 상세는 공통 404 응답을 반환한다")
	void missingStationReturnsCommonErrorResponse() throws Exception {
		mockMvc.perform(get("/api/v1/stations/unknown-station"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("역 정보를 찾을 수 없습니다."));
	}

	@Test
	@DisplayName("존재하지 않는 역 출구 목록은 공통 404 응답을 반환한다")
	void missingStationExitsReturnCommonErrorResponse() throws Exception {
		mockMvc.perform(get("/api/v1/stations/unknown-station/exits"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("역 정보를 찾을 수 없습니다."));
	}

	@Test
	@DisplayName("존재하지 않는 역 시설 목록은 공통 404 응답을 반환한다")
	void missingStationFacilitiesReturnCommonErrorResponse() throws Exception {
		mockMvc.perform(get("/api/v1/stations/unknown-station/facilities"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("역 정보를 찾을 수 없습니다."));
	}

	@Test
	@DisplayName("관리자는 역 내부 구조도 기준 자료의 출처와 검수일을 조회한다")
	void adminListsStationLayoutSourcesWithLicenseAndReviewMetadata() throws Exception {
		mockMvc.perform(get("/admin/stations/station-sangnoksu/layout-sources")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data[0].id").value("layout-source-sangnoksu-station-map"))
			.andExpect(jsonPath("$.data[0].stationId").value("station-sangnoksu"))
			.andExpect(jsonPath("$.data[0].sourceType").value("OPERATOR_DIAGRAM"))
			.andExpect(jsonPath("$.data[0].sourceName").value("상록수역 역사 안내도"))
			.andExpect(jsonPath("$.data[0].license").value("운영기관 안내도 확인용"))
			.andExpect(jsonPath("$.data[0].commercialUseAllowed").value(false))
			.andExpect(jsonPath("$.data[0].attributionRequired").value(true))
			.andExpect(jsonPath("$.data[0].capturedAt").value("2026-06-12"))
			.andExpect(jsonPath("$.data[0].reviewedAt").value("2026-06-12"));
	}

	@Test
	@DisplayName("역 내부 구조도 기준 자료 관리자 API는 관리자 인증을 요구한다")
	void adminStationLayoutSourcesRequireAdminAuthentication() throws Exception {
		mockMvc.perform(get("/admin/stations/station-sangnoksu/layout-sources"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/admin/stations/station-sangnoksu/layout-sources")
				.with(httpBasic("basic-user", "user-test-password")))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("존재하지 않는 역의 구조도 기준 자료는 공통 404 응답을 반환한다")
	void missingStationLayoutSourcesReturnCommonErrorResponse() throws Exception {
		mockMvc.perform(get("/admin/stations/unknown-station/layout-sources")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("역 정보를 찾을 수 없습니다."));
	}

	@Test
	@DisplayName("관리자는 역별 쉬운 내부 구조도 초안의 상태와 신뢰도를 조회한다")
	void adminListsSimplifiedStationLayoutsWithStatusAndConfidence() throws Exception {
		mockMvc.perform(get("/admin/stations/station-sangnoksu/layouts")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data[0].id").value("layout-sangnoksu-draft"))
			.andExpect(jsonPath("$.data[0].stationId").value("station-sangnoksu"))
			.andExpect(jsonPath("$.data[0].version").value(1))
			.andExpect(jsonPath("$.data[0].status").value("DRAFT"))
			.andExpect(jsonPath("$.data[0].sourceIds[0]").value("layout-source-sangnoksu-station-map"))
			.andExpect(jsonPath("$.data[0].confidenceLevel").value("OFFICIAL_DIAGRAM_REFERENCED"))
			.andExpect(jsonPath("$.data[0].baseFloor").value("B1"))
			.andExpect(jsonPath("$.data[0].layoutJson").value("{\"nodes\":[],\"edges\":[]}"))
			.andExpect(jsonPath("$.data[0].createdBy").value("admin-user"))
			.andExpect(jsonPath("$.data[0].lastVerifiedAt").value("2026-06-12"));
	}

	@Test
	@DisplayName("쉬운 내부 구조도 관리자 API는 관리자 인증을 요구한다")
	void adminSimplifiedStationLayoutsRequireAdminAuthentication() throws Exception {
		mockMvc.perform(get("/admin/stations/station-sangnoksu/layouts"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/admin/stations/station-sangnoksu/layouts")
				.with(httpBasic("basic-user", "user-test-password")))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("존재하지 않는 역의 쉬운 내부 구조도는 공통 404 응답을 반환한다")
	void missingSimplifiedStationLayoutsReturnCommonErrorResponse() throws Exception {
		mockMvc.perform(get("/admin/stations/unknown-station/layouts")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("역 정보를 찾을 수 없습니다."));
	}

	@Test
	@DisplayName("관리자는 역별 내부 이동 노드의 유형과 표시 정보를 조회한다")
	void adminListsRouteNodesWithDisplayMetadata() throws Exception {
		mockMvc.perform(get("/admin/stations/station-sangnoksu/route-nodes")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data[0].id").value("node-sangnoksu-elevator-1"))
			.andExpect(jsonPath("$.data[0].stationId").value("station-sangnoksu"))
			.andExpect(jsonPath("$.data[0].type").value("ELEVATOR"))
			.andExpect(jsonPath("$.data[0].name").value("1번 출구 엘리베이터"))
			.andExpect(jsonPath("$.data[0].floor").value("B1"))
			.andExpect(jsonPath("$.data[0].facilityId").value("facility-sangnoksu-elevator-1"))
			.andExpect(jsonPath("$.data[0].layoutId").value("layout-sangnoksu-draft"))
			.andExpect(jsonPath("$.data[0].displayX").value(120))
			.andExpect(jsonPath("$.data[0].displayY").value(240))
			.andExpect(jsonPath("$.data[0].displayLabel").value("엘리베이터"))
			.andExpect(jsonPath("$.data[0].accessibilityNote").value("휠체어 이동 가능"));
	}

	@Test
	@DisplayName("내부 이동 노드 관리자 API는 관리자 인증을 요구한다")
	void adminRouteNodesRequireAdminAuthentication() throws Exception {
		mockMvc.perform(get("/admin/stations/station-sangnoksu/route-nodes"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/admin/stations/station-sangnoksu/route-nodes")
				.with(httpBasic("basic-user", "user-test-password")))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("존재하지 않는 역의 내부 이동 노드는 공통 404 응답을 반환한다")
	void missingRouteNodesReturnCommonErrorResponse() throws Exception {
		mockMvc.perform(get("/admin/stations/unknown-station/route-nodes")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("역 정보를 찾을 수 없습니다."));
	}

	@Test
	@DisplayName("관리자는 역별 내부 이동 간선의 난이도와 접근성 제약을 조회한다")
	void adminListsRouteEdgesWithAccessibilityMetadata() throws Exception {
		mockMvc.perform(get("/admin/stations/station-sangnoksu/route-edges")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data[0].id").value("edge-sangnoksu-elevator-to-faregate"))
			.andExpect(jsonPath("$.data[0].stationId").value("station-sangnoksu"))
			.andExpect(jsonPath("$.data[0].fromNodeId").value("node-sangnoksu-elevator-1"))
			.andExpect(jsonPath("$.data[0].toNodeId").value("node-sangnoksu-faregate"))
			.andExpect(jsonPath("$.data[0].type").value("WALK"))
			.andExpect(jsonPath("$.data[0].distanceMeters").value(28))
			.andExpect(jsonPath("$.data[0].estimatedSeconds").value(75))
			.andExpect(jsonPath("$.data[0].hasStairs").value(false))
			.andExpect(jsonPath("$.data[0].requiresElevator").value(true))
			.andExpect(jsonPath("$.data[0].requiresEscalator").value(false))
			.andExpect(jsonPath("$.data[0].slopeLevel").value(1))
			.andExpect(jsonPath("$.data[0].widthLevel").value(2))
			.andExpect(jsonPath("$.data[0].reliabilityScore").value(92))
			.andExpect(jsonPath("$.data[0].active").value(true));
	}

	@Test
	@DisplayName("내부 이동 간선 관리자 API는 관리자 인증을 요구한다")
	void adminRouteEdgesRequireAdminAuthentication() throws Exception {
		mockMvc.perform(get("/admin/stations/station-sangnoksu/route-edges"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/admin/stations/station-sangnoksu/route-edges")
				.with(httpBasic("basic-user", "user-test-password")))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("존재하지 않는 역의 내부 이동 간선은 공통 404 응답을 반환한다")
	void missingRouteEdgesReturnCommonErrorResponse() throws Exception {
		mockMvc.perform(get("/admin/stations/unknown-station/route-edges")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("역 정보를 찾을 수 없습니다."));
	}

	@Test
	@DisplayName("관리자는 시설 상태를 수정하고 공개 시설 목록에서 확인할 수 있다")
	@DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
	void adminUpdatesFacilityStatusAndPublicListReflectsIt() throws Exception {
		mockMvc.perform(patch("/admin/facilities/facility-sangnoksu-elevator-1/status")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "status": "BROKEN"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.id").value("facility-sangnoksu-elevator-1"))
			.andExpect(jsonPath("$.data.status").value("BROKEN"));

		mockMvc.perform(get("/api/v1/stations/station-sangnoksu/facilities"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].id").value("facility-sangnoksu-elevator-1"))
			.andExpect(jsonPath("$.data[0].status").value("BROKEN"));
	}

	@Test
	@DisplayName("시설 상태 수정 API는 관리자 인증을 요구한다")
	void updateFacilityStatusRequiresAdminAuthentication() throws Exception {
		mockMvc.perform(patch("/admin/facilities/facility-sangnoksu-elevator-1/status")
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "status": "BROKEN"
					}
					"""))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(patch("/admin/facilities/facility-sangnoksu-elevator-1/status")
				.with(httpBasic("basic-user", "user-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "status": "BROKEN"
					}
					"""))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("시설 상태 수정 요청은 상태값을 요구한다")
	void updateFacilityStatusRequiresStatus() throws Exception {
		mockMvc.perform(patch("/admin/facilities/facility-sangnoksu-elevator-1/status")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("시설 상태를 선택해야 합니다."));
	}

	@Test
	@DisplayName("존재하지 않는 시설 상태 수정은 공통 404 응답을 반환한다")
	void updateFacilityStatusReturnsCommonErrorForMissingFacility() throws Exception {
		mockMvc.perform(patch("/admin/facilities/missing-facility/status")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "status": "BROKEN"
					}
					"""))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("시설 정보를 찾을 수 없습니다."));
	}
}

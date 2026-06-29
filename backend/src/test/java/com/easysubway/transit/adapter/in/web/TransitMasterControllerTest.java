package com.easysubway.transit.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
	@DisplayName("관리자는 역 목록에서 운영 데이터 구축 현황을 집계로 확인한다")
	void adminListsStationsWithMasterDataCounts() throws Exception {
		mockMvc.perform(get("/admin/stations")
				.param("query", "상록수")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data[0].id").value("station-sangnoksu"))
			.andExpect(jsonPath("$.data[0].nameKo").value("상록수"))
			.andExpect(jsonPath("$.data[0].lines[0].id").value("seoul-4"))
			.andExpect(jsonPath("$.data[0].exitCount").value(2))
			.andExpect(jsonPath("$.data[0].facilityCount").value(3))
			.andExpect(jsonPath("$.data[0].layoutSourceCount").value(1))
			.andExpect(jsonPath("$.data[0].simplifiedLayoutCount").value(1))
			.andExpect(jsonPath("$.data[0].routeNodeCount").value(2))
			.andExpect(jsonPath("$.data[0].routeEdgeCount").value(1));
	}

	@Test
	@DisplayName("관리자 역 목록 화면은 page size와 현재 페이지를 링크에 표시한다")
	void adminStationPageShowsPaginationLinks() throws Exception {
		String html = mockMvc.perform(get("/admin/stations/page")
				.param("size", "1")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("역 목록 페이지")
			.contains("aria-current=\"page\"")
			.contains("page=1&amp;size=1")
			.contains("다음");
	}

	@Test
	@DisplayName("관리자 역 목록은 노선 식별자로 필터링할 수 있다")
	void adminStationsCanBeFilteredByLine() throws Exception {
		mockMvc.perform(get("/admin/stations")
				.param("lineId", "seoul-4")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.length()").value(2))
			.andExpect(jsonPath("$.data[0].id").value("station-sadang"))
			.andExpect(jsonPath("$.data[1].id").value("station-sangnoksu"));
	}

	@Test
	@DisplayName("관리자는 역 상세에서 운영 데이터 묶음을 함께 조회한다")
	void adminGetsStationDetailWithMasterData() throws Exception {
		mockMvc.perform(get("/admin/stations/station-sangnoksu")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.station.id").value("station-sangnoksu"))
			.andExpect(jsonPath("$.data.station.lines[0].stationCode").value("448"))
			.andExpect(jsonPath("$.data.exits[0].id").value("exit-sangnoksu-1"))
			.andExpect(jsonPath("$.data.facilities[0].id").value("facility-sangnoksu-elevator-1"))
			.andExpect(jsonPath("$.data.layoutSources[0].id").value("layout-source-sangnoksu-station-map"))
			.andExpect(jsonPath("$.data.simplifiedLayouts[0].id").value("layout-sangnoksu-draft"))
			.andExpect(jsonPath("$.data.routeNodes[0].id").value("node-sangnoksu-elevator-1"))
			.andExpect(jsonPath("$.data.routeEdges[0].id").value("edge-sangnoksu-elevator-to-faregate"));
	}

	@Test
	@DisplayName("관리자 역 조회 API는 관리자 인증을 요구한다")
	void adminStationApisRequireAdminAuthentication() throws Exception {
		mockMvc.perform(get("/admin/stations"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/admin/stations/station-sangnoksu")
				.with(httpBasic("basic-user", "user-test-password")))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("존재하지 않는 역의 관리자 상세는 공통 404 응답을 반환한다")
	void missingAdminStationDetailReturnsCommonErrorResponse() throws Exception {
		mockMvc.perform(get("/admin/stations/unknown-station")
				.with(httpBasic("admin-user", "admin-test-password")))
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
	@DisplayName("관리자는 구조도 기준 자료의 출처와 이용 조건과 검수일을 수정한다")
	@DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
	void adminUpdatesStationLayoutSourceMetadataAndListReflectsIt() throws Exception {
		mockMvc.perform(patch("/admin/stations/station-sangnoksu/layout-sources/layout-source-sangnoksu-station-map")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "sourceType": "OPERATOR_PAGE",
					  "sourceName": "상록수역 운영기관 안내 페이지",
					  "sourceUrl": "https://www.seoulmetro.co.kr/station/sangnoksu",
					  "license": "운영기관 페이지 확인용",
					  "commercialUseAllowed": true,
					  "attributionRequired": false,
					  "capturedAt": "2026-06-13",
					  "reviewedAt": "2026-06-14"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.id").value("layout-source-sangnoksu-station-map"))
			.andExpect(jsonPath("$.data.sourceType").value("OPERATOR_PAGE"))
			.andExpect(jsonPath("$.data.sourceName").value("상록수역 운영기관 안내 페이지"))
			.andExpect(jsonPath("$.data.sourceUrl").value("https://www.seoulmetro.co.kr/station/sangnoksu"))
			.andExpect(jsonPath("$.data.license").value("운영기관 페이지 확인용"))
			.andExpect(jsonPath("$.data.commercialUseAllowed").value(true))
			.andExpect(jsonPath("$.data.attributionRequired").value(false))
			.andExpect(jsonPath("$.data.capturedAt").value("2026-06-13"))
			.andExpect(jsonPath("$.data.reviewedAt").value("2026-06-14"));

		mockMvc.perform(get("/admin/stations/station-sangnoksu/layout-sources")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].sourceName").value("상록수역 운영기관 안내 페이지"))
			.andExpect(jsonPath("$.data[0].commercialUseAllowed").value(true))
			.andExpect(jsonPath("$.data[0].attributionRequired").value(false))
			.andExpect(jsonPath("$.data[0].reviewedAt").value("2026-06-14"));
	}

	@Test
	@DisplayName("구조도 기준 자료 수정 API는 관리자 인증을 요구한다")
	void updateStationLayoutSourceMetadataRequiresAdminAuthentication() throws Exception {
		mockMvc.perform(patch("/admin/stations/station-sangnoksu/layout-sources/layout-source-sangnoksu-station-map")
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "sourceType": "OPERATOR_PAGE",
					  "sourceName": "상록수역 운영기관 안내 페이지",
					  "sourceUrl": "https://www.seoulmetro.co.kr/station/sangnoksu",
					  "license": "운영기관 페이지 확인용",
					  "commercialUseAllowed": true,
					  "attributionRequired": false,
					  "capturedAt": "2026-06-13",
					  "reviewedAt": "2026-06-14"
					}
					"""))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(patch("/admin/stations/station-sangnoksu/layout-sources/layout-source-sangnoksu-station-map")
				.with(httpBasic("basic-user", "user-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "sourceType": "OPERATOR_PAGE",
					  "sourceName": "상록수역 운영기관 안내 페이지",
					  "sourceUrl": "https://www.seoulmetro.co.kr/station/sangnoksu",
					  "license": "운영기관 페이지 확인용",
					  "commercialUseAllowed": true,
					  "attributionRequired": false,
					  "capturedAt": "2026-06-13",
					  "reviewedAt": "2026-06-14"
					}
					"""))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("구조도 기준 자료 수정은 필수 값과 검수일 범위를 요구한다")
	void updateStationLayoutSourceMetadataRequiresValidInputs() throws Exception {
		mockMvc.perform(patch("/admin/stations/station-sangnoksu/layout-sources/layout-source-sangnoksu-station-map")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "sourceType": "OPERATOR_PAGE",
					  "sourceName": " ",
					  "sourceUrl": "https://www.seoulmetro.co.kr/station/sangnoksu",
					  "license": "운영기관 페이지 확인용",
					  "commercialUseAllowed": true,
					  "attributionRequired": false,
					  "capturedAt": "2026-06-13",
					  "reviewedAt": "2026-06-14"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("기준 자료 이름을 입력해야 합니다."));

		mockMvc.perform(patch("/admin/stations/station-sangnoksu/layout-sources/layout-source-sangnoksu-station-map")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "sourceType": "OPERATOR_PAGE",
					  "sourceName": "상록수역 운영기관 안내 페이지",
					  "sourceUrl": "https://www.seoulmetro.co.kr/station/sangnoksu",
					  "license": "운영기관 페이지 확인용",
					  "commercialUseAllowed": true,
					  "attributionRequired": false,
					  "capturedAt": "2026-06-13",
					  "reviewedAt": "2026-06-12"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("기준 자료 확인일은 수집일보다 빠를 수 없습니다."));
	}

	@Test
	@DisplayName("구조도 기준 자료 수정은 URL 역과 기준 자료 소속이 일치해야 한다")
	void updateStationLayoutSourceMetadataRequiresSourceInStation() throws Exception {
		mockMvc.perform(patch("/admin/stations/station-sadang/layout-sources/layout-source-sangnoksu-station-map")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "sourceType": "OPERATOR_PAGE",
					  "sourceName": "상록수역 운영기관 안내 페이지",
					  "sourceUrl": "https://www.seoulmetro.co.kr/station/sangnoksu",
					  "license": "운영기관 페이지 확인용",
					  "commercialUseAllowed": true,
					  "attributionRequired": false,
					  "capturedAt": "2026-06-13",
					  "reviewedAt": "2026-06-14"
					}
					"""))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("구조도 기준 자료 정보를 찾을 수 없습니다."));
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
	@DisplayName("관리자는 쉬운 내부 구조도 검수 상태를 수정하고 관리자 조회에서 확인한다")
	@DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
	void adminUpdatesSimplifiedStationLayoutStatusAndListReflectsIt() throws Exception {
		mockMvc.perform(patch("/admin/stations/layouts/layout-sangnoksu-draft/status")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "status": "READY_FOR_REVIEW"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.id").value("layout-sangnoksu-draft"))
			.andExpect(jsonPath("$.data.status").value("READY_FOR_REVIEW"))
			.andExpect(jsonPath("$.data.reviewedBy").value("admin-user"));

		mockMvc.perform(get("/admin/stations/station-sangnoksu/layouts")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].status").value("READY_FOR_REVIEW"))
			.andExpect(jsonPath("$.data[0].reviewedBy").value("admin-user"));
	}

	@Test
	@DisplayName("쉬운 내부 구조도 상태 수정 API는 관리자 인증을 요구한다")
	void updateSimplifiedStationLayoutStatusRequiresAdminAuthentication() throws Exception {
		mockMvc.perform(patch("/admin/stations/layouts/layout-sangnoksu-draft/status")
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "status": "READY_FOR_REVIEW"
					}
					"""))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(patch("/admin/stations/layouts/layout-sangnoksu-draft/status")
				.with(httpBasic("basic-user", "user-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "status": "READY_FOR_REVIEW"
					}
					"""))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("쉬운 내부 구조도 상태 수정 요청은 상태값을 요구한다")
	void updateSimplifiedStationLayoutStatusRequiresStatus() throws Exception {
		mockMvc.perform(patch("/admin/stations/layouts/layout-sangnoksu-draft/status")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("구조도 상태를 선택해야 합니다."));

		mockMvc.perform(get("/admin/stations/station-sangnoksu/layouts")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].status").value("DRAFT"));
	}

	@Test
	@DisplayName("쉬운 내부 구조도 상태 수정 요청의 알 수 없는 enum은 요청 본문 오류로 응답한다")
	void updateSimplifiedStationLayoutStatusRejectsUnknownStatusAsUnreadableBody() throws Exception {
		mockMvc.perform(patch("/admin/stations/layouts/layout-sangnoksu-draft/status")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "status": "NOT_A_STATUS"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("요청 본문을 확인해야 합니다."));
	}

	@Test
	@DisplayName("존재하지 않는 쉬운 내부 구조도 상태 수정은 공통 404 응답을 반환한다")
	void updateSimplifiedStationLayoutStatusReturnsCommonErrorForMissingLayout() throws Exception {
		mockMvc.perform(patch("/admin/stations/layouts/missing-layout/status")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "status": "PUBLISHED"
					}
					"""))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("역 구조도 정보를 찾을 수 없습니다."));
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
	@DisplayName("관리자는 내부 이동 노드의 표시 위치와 안내 문구를 수정한다")
	@DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
	void adminUpdatesRouteNodeDisplayMetadataAndListReflectsIt() throws Exception {
		mockMvc.perform(patch("/admin/stations/station-sangnoksu/route-nodes/node-sangnoksu-elevator-1")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "displayX": 132,
					  "displayY": 256,
					  "displayLabel": "1번 출구 승강기",
					  "accessibilityNote": "휠체어와 유모차 이동 가능"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.id").value("node-sangnoksu-elevator-1"))
			.andExpect(jsonPath("$.data.displayX").value(132))
			.andExpect(jsonPath("$.data.displayY").value(256))
			.andExpect(jsonPath("$.data.displayLabel").value("1번 출구 승강기"))
			.andExpect(jsonPath("$.data.accessibilityNote").value("휠체어와 유모차 이동 가능"));

		mockMvc.perform(get("/admin/stations/station-sangnoksu/route-nodes")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].displayX").value(132))
			.andExpect(jsonPath("$.data[0].displayY").value(256))
			.andExpect(jsonPath("$.data[0].displayLabel").value("1번 출구 승강기"))
			.andExpect(jsonPath("$.data[0].accessibilityNote").value("휠체어와 유모차 이동 가능"));
	}

	@Test
	@DisplayName("내부 이동 노드 수정 API는 관리자 인증을 요구한다")
	void updateRouteNodeDisplayMetadataRequiresAdminAuthentication() throws Exception {
		mockMvc.perform(patch("/admin/stations/station-sangnoksu/route-nodes/node-sangnoksu-elevator-1")
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "displayX": 132,
					  "displayY": 256,
					  "displayLabel": "1번 출구 승강기"
					}
					"""))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(patch("/admin/stations/station-sangnoksu/route-nodes/node-sangnoksu-elevator-1")
				.with(httpBasic("basic-user", "user-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "displayX": 132,
					  "displayY": 256,
					  "displayLabel": "1번 출구 승강기"
					}
					"""))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("내부 이동 노드 수정은 표시 라벨과 음수가 아닌 좌표를 요구한다")
	void updateRouteNodeDisplayMetadataRequiresValidDisplayInputs() throws Exception {
		mockMvc.perform(patch("/admin/stations/station-sangnoksu/route-nodes/node-sangnoksu-elevator-1")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "displayX": -1,
					  "displayY": 256,
					  "displayLabel": "1번 출구 승강기"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("노드 표시 좌표는 0 이상이어야 합니다."));

		mockMvc.perform(patch("/admin/stations/station-sangnoksu/route-nodes/node-sangnoksu-elevator-1")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "displayX": 132,
					  "displayY": 256,
					  "displayLabel": " "
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("노드 표시 라벨을 입력해야 합니다."));

		mockMvc.perform(patch("/admin/stations/station-sangnoksu/route-nodes/node-sangnoksu-elevator-1")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "displayY": 256,
					  "displayLabel": "1번 출구 승강기"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("노드 표시 좌표가 필요합니다."));

		mockMvc.perform(get("/admin/stations/station-sangnoksu/route-nodes")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].displayX").value(120))
			.andExpect(jsonPath("$.data[0].displayY").value(240));
	}

	@Test
	@DisplayName("내부 이동 노드 수정은 URL 역과 노드 소속이 일치해야 한다")
	void updateRouteNodeDisplayMetadataRequiresNodeInStation() throws Exception {
		mockMvc.perform(patch("/admin/stations/station-sadang/route-nodes/node-sangnoksu-elevator-1")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "displayX": 132,
					  "displayY": 256,
					  "displayLabel": "1번 출구 승강기"
					}
					"""))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("내부 이동 노드 정보를 찾을 수 없습니다."));
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
	@DisplayName("관리자는 내부 이동 간선의 이동 난이도와 접근성 제약을 수정한다")
	@DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
	void adminUpdatesRouteEdgeMetadataAndListReflectsIt() throws Exception {
		mockMvc.perform(patch("/admin/stations/station-sangnoksu/route-edges/edge-sangnoksu-elevator-to-faregate")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "distanceMeters": 34,
					  "estimatedSeconds": 90,
					  "hasStairs": true,
					  "requiresElevator": false,
					  "requiresEscalator": true,
					  "slopeLevel": 2,
					  "widthLevel": 3,
					  "reliabilityScore": 76,
					  "active": false
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.id").value("edge-sangnoksu-elevator-to-faregate"))
			.andExpect(jsonPath("$.data.distanceMeters").value(34))
			.andExpect(jsonPath("$.data.estimatedSeconds").value(90))
			.andExpect(jsonPath("$.data.hasStairs").value(true))
			.andExpect(jsonPath("$.data.requiresElevator").value(false))
			.andExpect(jsonPath("$.data.requiresEscalator").value(true))
			.andExpect(jsonPath("$.data.slopeLevel").value(2))
			.andExpect(jsonPath("$.data.widthLevel").value(3))
			.andExpect(jsonPath("$.data.reliabilityScore").value(76))
			.andExpect(jsonPath("$.data.active").value(false));

		mockMvc.perform(get("/admin/stations/station-sangnoksu/route-edges")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].distanceMeters").value(34))
			.andExpect(jsonPath("$.data[0].estimatedSeconds").value(90))
			.andExpect(jsonPath("$.data[0].hasStairs").value(true))
			.andExpect(jsonPath("$.data[0].requiresElevator").value(false))
			.andExpect(jsonPath("$.data[0].requiresEscalator").value(true))
			.andExpect(jsonPath("$.data[0].slopeLevel").value(2))
			.andExpect(jsonPath("$.data[0].widthLevel").value(3))
			.andExpect(jsonPath("$.data[0].reliabilityScore").value(76))
			.andExpect(jsonPath("$.data[0].active").value(false));
	}

	@Test
	@DisplayName("내부 이동 간선 수정 API는 관리자 인증을 요구한다")
	void updateRouteEdgeMetadataRequiresAdminAuthentication() throws Exception {
		mockMvc.perform(patch("/admin/stations/station-sangnoksu/route-edges/edge-sangnoksu-elevator-to-faregate")
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "distanceMeters": 34,
					  "estimatedSeconds": 90,
					  "hasStairs": false,
					  "requiresElevator": true,
					  "requiresEscalator": false,
					  "slopeLevel": 1,
					  "widthLevel": 2,
					  "reliabilityScore": 92,
					  "active": true
					}
					"""))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(patch("/admin/stations/station-sangnoksu/route-edges/edge-sangnoksu-elevator-to-faregate")
				.with(httpBasic("basic-user", "user-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "distanceMeters": 34,
					  "estimatedSeconds": 90,
					  "hasStairs": false,
					  "requiresElevator": true,
					  "requiresEscalator": false,
					  "slopeLevel": 1,
					  "widthLevel": 2,
					  "reliabilityScore": 92,
					  "active": true
					}
					"""))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("내부 이동 간선 수정은 필수 숫자와 유효 범위를 요구한다")
	void updateRouteEdgeMetadataRequiresValidInputs() throws Exception {
		mockMvc.perform(patch("/admin/stations/station-sangnoksu/route-edges/edge-sangnoksu-elevator-to-faregate")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "estimatedSeconds": 90,
					  "hasStairs": false,
					  "requiresElevator": true,
					  "requiresEscalator": false,
					  "slopeLevel": 1,
					  "widthLevel": 2,
					  "reliabilityScore": 92,
					  "active": true
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("간선 거리와 예상 시간이 필요합니다."));

		mockMvc.perform(patch("/admin/stations/station-sangnoksu/route-edges/edge-sangnoksu-elevator-to-faregate")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "distanceMeters": 34,
					  "estimatedSeconds": 90,
					  "hasStairs": false,
					  "requiresElevator": true,
					  "requiresEscalator": false,
					  "slopeLevel": 1,
					  "widthLevel": 2,
					  "reliabilityScore": 101,
					  "active": true
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("내부 이동로 확인 정도는 0부터 100까지 입력해야 합니다."));
	}

	@Test
	@DisplayName("내부 이동 간선 수정은 URL 역과 간선 소속이 일치해야 한다")
	void updateRouteEdgeMetadataRequiresEdgeInStation() throws Exception {
		mockMvc.perform(patch("/admin/stations/station-sadang/route-edges/edge-sangnoksu-elevator-to-faregate")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "distanceMeters": 34,
					  "estimatedSeconds": 90,
					  "hasStairs": false,
					  "requiresElevator": true,
					  "requiresEscalator": false,
					  "slopeLevel": 1,
					  "widthLevel": 2,
					  "reliabilityScore": 92,
					  "active": true
					}
					"""))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("내부 이동 간선 정보를 찾을 수 없습니다."));
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
	@DisplayName("관리자는 시설 상태를 수정하고 관리자 역 상세에서 확인할 수 있다")
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

		mockMvc.perform(get("/admin/stations/station-sangnoksu")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.facilities[0].id").value("facility-sangnoksu-elevator-1"))
			.andExpect(jsonPath("$.data.facilities[0].status").value("BROKEN"));
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

		mockMvc.perform(get("/admin/stations/station-sangnoksu")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.facilities[0].status").value("NORMAL"));
	}

	@Test
	@DisplayName("시설 상태 수정 요청의 알 수 없는 enum은 요청 본문 오류로 응답한다")
	void updateFacilityStatusRejectsUnknownStatusAsUnreadableBody() throws Exception {
		mockMvc.perform(patch("/admin/facilities/facility-sangnoksu-elevator-1/status")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "status": "NOT_A_STATUS"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("요청 본문을 확인해야 합니다."));
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

	@Test
	@DisplayName("관리자는 접근성 시설을 등록하고 관리자 역 상세에서 확인한다")
	@DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
	void adminCreatesAccessibilityFacilityAndPublicListReflectsIt() throws Exception {
		mockMvc.perform(post("/admin/facilities")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "id": "facility-sangnoksu-ramp-1",
					  "stationId": "station-sangnoksu",
					  "exitId": "exit-sangnoksu-2",
					  "type": "RAMP",
					  "name": "2번 출구 경사로",
					  "floorFrom": "지상",
					  "floorTo": "대합실",
					  "latitude": 37.303041,
					  "longitude": 126.866768,
					  "description": "2번 출구와 대합실 사이 경사로입니다.",
					  "status": "NORMAL",
					  "dataConfidence": "MEDIUM",
					  "dataSourceType": "ADMIN_VERIFIED"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.id").value("facility-sangnoksu-ramp-1"))
			.andExpect(jsonPath("$.data.stationId").value("station-sangnoksu"))
			.andExpect(jsonPath("$.data.type").value("RAMP"))
			.andExpect(jsonPath("$.data.dataSourceType").value("ADMIN_VERIFIED"));

		mockMvc.perform(get("/admin/stations/station-sangnoksu")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.facilities[3].id").value("facility-sangnoksu-ramp-1"))
			.andExpect(jsonPath("$.data.facilities[3].name").value("2번 출구 경사로"));
	}

	@Test
	@DisplayName("관리자는 접근성 시설 전체 정보를 수정한다")
	@DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
	void adminUpdatesAccessibilityFacility() throws Exception {
		mockMvc.perform(put("/admin/facilities/facility-sangnoksu-elevator-1")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "stationId": "station-sangnoksu",
					  "exitId": "exit-sangnoksu-1",
					  "type": "ELEVATOR",
					  "name": "1번 출구 엘리베이터 점검 반영",
					  "floorFrom": "지상",
					  "floorTo": "대합실",
					  "latitude": 37.302430,
					  "longitude": 126.866230,
					  "description": "관리자 검수 후 위치와 설명을 보정했습니다.",
					  "status": "UNDER_CONSTRUCTION",
					  "dataConfidence": "HIGH",
					  "dataSourceType": "ADMIN_VERIFIED"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.id").value("facility-sangnoksu-elevator-1"))
			.andExpect(jsonPath("$.data.name").value("1번 출구 엘리베이터 점검 반영"))
			.andExpect(jsonPath("$.data.status").value("UNDER_CONSTRUCTION"));

		mockMvc.perform(get("/admin/stations/station-sangnoksu")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.facilities[0].id").value("facility-sangnoksu-elevator-1"))
			.andExpect(jsonPath("$.data.facilities[0].description").value("관리자 검수 후 위치와 설명을 보정했습니다."));
	}

	@Test
	@DisplayName("시설 등록과 전체 수정 API는 관리자 인증을 요구한다")
	void facilityWriteApisRequireAdminAuthentication() throws Exception {
		mockMvc.perform(post("/admin/facilities")
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "id": "facility-sangnoksu-ramp-1",
					  "stationId": "station-sangnoksu",
					  "type": "RAMP",
					  "name": "2번 출구 경사로",
					  "status": "NORMAL",
					  "dataConfidence": "MEDIUM",
					  "dataSourceType": "ADMIN_VERIFIED"
					}
					"""))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(put("/admin/facilities/facility-sangnoksu-elevator-1")
				.with(httpBasic("basic-user", "user-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "stationId": "station-sangnoksu",
					  "type": "ELEVATOR",
					  "name": "1번 출구 엘리베이터",
					  "status": "NORMAL",
					  "dataConfidence": "HIGH",
					  "dataSourceType": "ADMIN_VERIFIED"
					}
					"""))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("시설 등록은 중복 식별자를 공통 오류로 반환한다")
	void createAccessibilityFacilityReturnsCommonErrorForDuplicateId() throws Exception {
		mockMvc.perform(post("/admin/facilities")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "id": "facility-sangnoksu-elevator-1",
					  "stationId": "station-sangnoksu",
					  "exitId": "exit-sangnoksu-1",
					  "type": "ELEVATOR",
					  "name": "중복 시설",
					  "status": "NORMAL",
					  "dataConfidence": "HIGH",
					  "dataSourceType": "ADMIN_VERIFIED"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("이미 등록된 시설입니다."));
	}
}

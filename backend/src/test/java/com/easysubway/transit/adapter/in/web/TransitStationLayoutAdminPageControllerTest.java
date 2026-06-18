package com.easysubway.transit.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
	"easysubway.admin.username=admin-user",
	"easysubway.admin.password=admin-test-password",
	"easysubway.user.username=basic-user",
	"easysubway.user.password=user-test-password"
})
@AutoConfigureMockMvc
@DisplayName("관리자 역 구조도 요약 화면")
class TransitStationLayoutAdminPageControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("관리자는 역 구조도 기준 자료와 내부 이동 그래프를 읽기 전용으로 확인한다")
	void adminGetsStationLayoutPage() throws Exception {
		String html = mockMvc.perform(get("/admin/stations/station-sangnoksu/layouts/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("역 구조도 요약")
			.contains("상록수")
			.contains("수도권 4호선")
			.contains("구조도 기준 자료")
			.contains("상록수역 역사 안내도")
			.contains("운영기관 안내도 확인용")
			.contains("상업적 사용 불가")
			.contains("출처 표시 필요")
			.contains("쉬운 내부 구조도")
			.contains("DRAFT")
			.contains("OFFICIAL_DIAGRAM_REFERENCED")
			.contains("B1")
			.contains("name=\"status\"")
			.contains("READY_FOR_REVIEW")
			.contains("PUBLISHED")
			.contains("name=\"_csrf\"")
			.contains("내부 이동 노드")
			.contains("1번 출구 엘리베이터")
			.contains("휠체어 이동 가능")
			.contains("name=\"displayX\"")
			.contains("name=\"displayY\"")
			.contains("name=\"displayLabel\"")
			.contains("name=\"accessibilityNote\"")
			.doesNotContain("value=\"접근성 메모 없음\"")
			.contains("내부 이동 간선")
			.contains("node-sangnoksu-elevator-1")
			.contains("node-sangnoksu-faregate")
			.contains("계단 없음")
			.contains("엘리베이터 필요")
			.contains("75초")
			.contains("신뢰도 92")
			.contains("name=\"distanceMeters\"")
			.contains("name=\"estimatedSeconds\"")
			.contains("name=\"hasStairs\"")
			.contains("name=\"requiresElevator\"")
			.contains("name=\"requiresEscalator\"")
			.contains("name=\"slopeLevel\"")
			.contains("name=\"widthLevel\"")
			.contains("name=\"reliabilityScore\"")
			.contains("name=\"active\"")
			.doesNotContain("{\"nodes\":[],\"edges\":[]}")
			.doesNotContain("<img");
	}

	@Test
	@DisplayName("관리자는 역 구조도 화면에서 쉬운 내부 구조도 상태를 변경한다")
	@DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
	void adminUpdatesStationLayoutStatusFromPageAndRedirectsToLayoutPage() throws Exception {
		mockMvc.perform(post("/admin/stations/station-sangnoksu/layouts/layout-sangnoksu-draft/page/status")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("status", "READY_FOR_REVIEW"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/admin/stations/station-sangnoksu/layouts/page"));

		String html = mockMvc.perform(get("/admin/stations/station-sangnoksu/layouts/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("layout-sangnoksu-draft")
			.contains("READY_FOR_REVIEW");
	}

	@Test
	@DisplayName("관리자는 역 구조도 화면에서 내부 이동 노드 표시 정보를 변경한다")
	@DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
	void adminUpdatesRouteNodeDisplayMetadataFromPageAndRedirectsToLayoutPage() throws Exception {
		mockMvc.perform(post("/admin/stations/station-sangnoksu/route-nodes/node-sangnoksu-elevator-1/page")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("displayX", "132")
				.param("displayY", "256")
				.param("displayLabel", "1번 출구 승강기")
				.param("accessibilityNote", "휠체어와 유모차 이동 가능"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/admin/stations/station-sangnoksu/layouts/page"));

		String html = mockMvc.perform(get("/admin/stations/station-sangnoksu/layouts/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("1번 출구 승강기")
			.contains("x 132, y 256")
			.contains("휠체어와 유모차 이동 가능");
	}

	@Test
	@DisplayName("관리자는 메모가 없는 내부 이동 노드의 좌표와 라벨만 변경한다")
	@DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
	void adminUpdatesRouteNodeWithoutPersistingEmptyNotePlaceholderFromPage() throws Exception {
		mockMvc.perform(post("/admin/stations/station-sangnoksu/route-nodes/node-sangnoksu-faregate/page")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("displayX", "288")
				.param("displayY", "244")
				.param("displayLabel", "대합실 개찰구"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/admin/stations/station-sangnoksu/layouts/page"));

		String html = mockMvc.perform(get("/admin/stations/station-sangnoksu/layouts/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("대합실 개찰구")
			.contains("x 288, y 244")
			.contains("접근성 메모 없음")
			.doesNotContain("value=\"접근성 메모 없음\"");
	}

	@Test
	@DisplayName("관리자는 역 구조도 화면에서 내부 이동 간선 정보를 변경한다")
	@DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
	void adminUpdatesRouteEdgeMetadataFromPageAndRedirectsToLayoutPage() throws Exception {
		mockMvc.perform(post("/admin/stations/station-sangnoksu/route-edges/edge-sangnoksu-elevator-to-faregate/page")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("distanceMeters", "34")
				.param("estimatedSeconds", "90")
				.param("hasStairs", "true")
				.param("requiresElevator", "false")
				.param("requiresEscalator", "true")
				.param("slopeLevel", "2")
				.param("widthLevel", "3")
				.param("reliabilityScore", "76")
				.param("active", "false"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/admin/stations/station-sangnoksu/layouts/page"));

		String html = mockMvc.perform(get("/admin/stations/station-sangnoksu/layouts/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("34m")
			.contains("90초")
			.contains("계단 포함")
			.contains("엘리베이터 불필요")
			.contains("에스컬레이터 필요")
			.contains("신뢰도 76")
			.contains("비활성");
	}

	@Test
	@DisplayName("관리자 역 구조도 요약 화면은 관리자 인증을 요구한다")
	void stationLayoutPageRequiresAdminAuthentication() throws Exception {
		mockMvc.perform(get("/admin/stations/station-sangnoksu/layouts/page"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/admin/stations/station-sangnoksu/layouts/page")
				.with(httpBasic("basic-user", "user-test-password")))
			.andExpect(status().isForbidden());

		mockMvc.perform(post("/admin/stations/station-sangnoksu/layouts/layout-sangnoksu-draft/page/status")
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("status", "READY_FOR_REVIEW"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(post("/admin/stations/station-sangnoksu/layouts/layout-sangnoksu-draft/page/status")
				.with(httpBasic("basic-user", "user-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("status", "READY_FOR_REVIEW"))
			.andExpect(status().isForbidden());

		mockMvc.perform(post("/admin/stations/station-sangnoksu/route-nodes/node-sangnoksu-elevator-1/page")
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("displayX", "132")
				.param("displayY", "256")
				.param("displayLabel", "1번 출구 승강기"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(post("/admin/stations/station-sangnoksu/route-nodes/node-sangnoksu-elevator-1/page")
				.with(httpBasic("basic-user", "user-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("displayX", "132")
				.param("displayY", "256")
				.param("displayLabel", "1번 출구 승강기"))
			.andExpect(status().isForbidden());

		mockMvc.perform(post("/admin/stations/station-sangnoksu/route-edges/edge-sangnoksu-elevator-to-faregate/page")
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("distanceMeters", "34")
				.param("estimatedSeconds", "90")
				.param("hasStairs", "false")
				.param("requiresElevator", "true")
				.param("requiresEscalator", "false")
				.param("slopeLevel", "1")
				.param("widthLevel", "2")
				.param("reliabilityScore", "92")
				.param("active", "true"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(post("/admin/stations/station-sangnoksu/route-edges/edge-sangnoksu-elevator-to-faregate/page")
				.with(httpBasic("basic-user", "user-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("distanceMeters", "34")
				.param("estimatedSeconds", "90")
				.param("hasStairs", "false")
				.param("requiresElevator", "true")
				.param("requiresEscalator", "false")
				.param("slopeLevel", "1")
				.param("widthLevel", "2")
				.param("reliabilityScore", "92")
				.param("active", "true"))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("존재하지 않는 역의 구조도 요약 화면은 공통 404 응답을 반환한다")
	void missingStationLayoutPageReturnsCommonErrorResponse() throws Exception {
		mockMvc.perform(get("/admin/stations/unknown-station/layouts/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("역 정보를 찾을 수 없습니다."));
	}

	@Test
	@DisplayName("역 구조도 노드 변경은 URL 역과 노드 소속이 일치해야 한다")
	void stationLayoutNodeUpdateRequiresNodeInStation() throws Exception {
		mockMvc.perform(post("/admin/stations/station-sadang/route-nodes/node-sangnoksu-elevator-1/page")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("displayX", "132")
				.param("displayY", "256")
				.param("displayLabel", "1번 출구 승강기"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("내부 이동 노드 정보를 찾을 수 없습니다."));
	}

	@Test
	@DisplayName("역 구조도 간선 변경은 URL 역과 간선 소속이 일치해야 한다")
	void stationLayoutEdgeUpdateRequiresEdgeInStation() throws Exception {
		mockMvc.perform(post("/admin/stations/station-sadang/route-edges/edge-sangnoksu-elevator-to-faregate/page")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("distanceMeters", "34")
				.param("estimatedSeconds", "90")
				.param("hasStairs", "false")
				.param("requiresElevator", "true")
				.param("requiresEscalator", "false")
				.param("slopeLevel", "1")
				.param("widthLevel", "2")
				.param("reliabilityScore", "92")
				.param("active", "true"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("내부 이동 간선 정보를 찾을 수 없습니다."));
	}

	@Test
	@DisplayName("역 구조도 상태 변경은 URL 역과 구조도 소속이 일치해야 한다")
	void stationLayoutStatusUpdateRequiresLayoutInStation() throws Exception {
		mockMvc.perform(post("/admin/stations/station-sadang/layouts/layout-sangnoksu-draft/page/status")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("status", "READY_FOR_REVIEW"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("역 구조도 정보를 찾을 수 없습니다."));

		mockMvc.perform(get("/admin/stations/station-sangnoksu/layouts")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].id").value("layout-sangnoksu-draft"))
			.andExpect(jsonPath("$.data[0].status").value("DRAFT"));
	}
}

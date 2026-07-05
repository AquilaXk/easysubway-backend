package com.easysubway.admin.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
	"easysubway.admin.username=admin-user",
	"easysubway.admin.password=admin-test-password"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("통합 관리자 v3 화면")
class AdminV3PageSmokeTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("관리자는 v3 신규 관리자 화면을 실제 백엔드 모델로 렌더링한다")
	void adminRendersNewV3Pages() throws Exception {
		assertPage("/admin/dashboard/page", "통합 대시보드");
		assertPage("/admin/stations/page", "역 목록");
		assertPage("/admin/stations/station-sangnoksu/page", "상록수");
		assertPage("/admin/facilities/editor/page", "시설 등록·수정");
		assertPage("/admin/field-verifications/page", "현장 확인 목록");
		assertPage("/admin/field-verifications/station-sangnoksu/page", "현장 확인 상세");
		assertPage("/admin/system/page", "시스템 상태");
	}

	@Test
	@DisplayName("시설 상태판은 유형·상태·역 필터 툴바와 제보·허브 크로스링크를 렌더한다")
	void facilityStatusBoardRendersV4ToolbarAndCrossLinks() throws Exception {
		String html = mockMvc.perform(get("/admin/facilities/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();

		assertThat(html)
			.contains("시설·역 이름 검색")
			.contains("유형 필터")
			.contains("상태 필터")
			.contains("역 필터")
			.contains("확인 필요 먼저")
			// 역명은 허브(시설 탭)로, 제보는 역 필터 제보 대기열로 크로스링크한다.
			.contains("/admin/stations/station-sangnoksu/page?tab=facilities")
			.contains("/admin/reports/page?station=station-sangnoksu");

		String fragment = mockMvc.perform(get("/admin/facilities/page")
				.param("sort", "attention")
				.header("HX-Request", "true")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(fragment)
			.contains("id=\"facility-results\"")
			.doesNotContain("admin-shell");
	}

	@Test
	@DisplayName("역 목록은 검색·지역·노선·정렬 툴바와 미확인 제보 뱃지를 렌더한다")
	void stationListRendersV4ToolbarAndPendingBadge() throws Exception {
		String html = mockMvc.perform(get("/admin/stations/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();

		assertThat(html)
			.contains("역 이름 검색")
			.contains("지역 필터")
			.contains("노선 필터")
			.contains("미확인 제보 많은 순")
			.contains("미확인 제보")
			.contains("확인 필요 시설")
			// 상록수 역 링크가 상세 허브로 연결된다.
			.contains("/admin/stations/station-sangnoksu/page");

		// 정렬·지역 파라미터가 페이지네이션/툴바에 유지된다(htmx 부분 갱신도 같은 필터 공유).
		String sorted = mockMvc.perform(get("/admin/stations/page")
				.param("sort", "pending")
				.header("HX-Request", "true")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(sorted)
			.contains("id=\"station-results\"")
			.doesNotContain("admin-shell");
	}

	@Test
	@DisplayName("역 상세는 허브 탭으로 개요·시설·제보·현장·구조를 오간다")
	void stationDetailHubTabsNavigate() throws Exception {
		String overview = getStationHub(null);
		assertThat(overview)
			.contains("상록수")
			.contains("개요")
			.contains("제보 이력")
			.contains("현장 확인")
			.contains("구조·동선")
			.contains("데이터 품질");

		String reports = getStationHub("reports");
		assertThat(reports)
			.contains("제보 이력")
			.contains("전체 제보 보기")
			// 제보 이력 탭은 역 필터가 걸린 제보 대기열(#1740)로 딥링크한다.
			.contains("/admin/reports/page?station=station-sangnoksu");

		String field = getStationHub("field");
		assertThat(field)
			.contains("현장 확인")
			.contains("/admin/field-verifications/station-sangnoksu/page");

		String structure = getStationHub("structure");
		assertThat(structure)
			.contains("구조·동선")
			.contains("/admin/stations/station-sangnoksu/layouts/page");

		assertThat(getStationHub("facilities")).contains("접근성 시설");
	}

	@Test
	@DisplayName("역 허브 탭 htmx 요청은 셸 없이 탭 패널 fragment만 돌려준다")
	void stationDetailHubTabHtmxReturnsFragmentOnly() throws Exception {
		String fragment = mockMvc.perform(get("/admin/stations/station-sangnoksu/page")
				.param("tab", "reports")
				.header("HX-Request", "true")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();

		assertThat(fragment)
			.contains("id=\"station-hub\"")
			.contains("제보 이력")
			// fragment 응답은 셸(사이드바·<html>)을 포함하지 않는다.
			.doesNotContain("admin-shell")
			.doesNotContain("<html");
	}

	private String getStationHub(String tab) throws Exception {
		var request = get("/admin/stations/station-sangnoksu/page")
			.with(httpBasic("admin-user", "admin-test-password"));
		if (tab != null) {
			request = request.param("tab", tab);
		}
		return mockMvc.perform(request)
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
	}

	@Test
	@DisplayName("관리자 시스템 화면은 health component 표를 표시한다")
	void adminSystemPageShowsHealthComponents() throws Exception {
		String html = mockMvc.perform(get("/admin/system/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("컴포넌트 상태")
			.contains("애플리케이션")
			.contains("마스터 데이터")
			.contains("데이터베이스")
			.doesNotContain("prod-object-storage-secret-key");
	}

	@Test
	@DisplayName("관리자 sidebar는 permission이 있는 program만 표시한다")
	void adminSidebarShowsOnlyPermittedPrograms() throws Exception {
		String html = mockMvc.perform(get("/admin/dashboard/page")
				.with(user("viewer").authorities(new SimpleGrantedAuthority("admin.view"))))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("통합 대시보드")
			.doesNotContain("제보 확인 대기열")
			.doesNotContain("역 구조·동선 편집")
			.doesNotContain("데이터 수집");
	}

	@Test
	@DisplayName("전체 permission 관리자는 모든 관리자 program을 볼 수 있다")
	void fullPermissionAdminSeesAllPrograms() throws Exception {
		String html = mockMvc.perform(get("/admin/dashboard/page")
				.with(user("admin").authorities(
					new SimpleGrantedAuthority("admin.view"),
					new SimpleGrantedAuthority("admin.report.review"),
					new SimpleGrantedAuthority("admin.master.edit"),
					new SimpleGrantedAuthority("admin.field.operate"),
					new SimpleGrantedAuthority("admin.data.operate"),
					new SimpleGrantedAuthority("admin.security.audit"),
					new SimpleGrantedAuthority("admin.security.admin")
				)))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("제보 확인 대기열")
			.contains("역 구조·동선 편집")
			.contains("href=\"/admin/facilities/editor/page\"")
			.contains("데이터 수집");
	}

	@Test
	@DisplayName("권한이 없는 관리자는 쓰기 entrypoint에 접근할 수 없다")
	void adminPermissionBlocksMutatingEntrypoint() throws Exception {
		mockMvc.perform(post("/admin/reports/report-1/page/review")
				.with(user("viewer").authorities(new SimpleGrantedAuthority("admin.view")))
				.with(csrf())
				.param("decision", "REJECT"))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("권한이 없는 관리자는 제한된 읽기 화면에 직접 접근할 수 없다")
	void adminPermissionBlocksRestrictedReadPages() throws Exception {
		mockMvc.perform(get("/admin/reports/page")
				.with(user("viewer").authorities(new SimpleGrantedAuthority("admin.view"))))
			.andExpect(status().isForbidden());
		mockMvc.perform(get("/admin/notifications/push/page")
				.with(user("viewer").authorities(new SimpleGrantedAuthority("admin.view"))))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("업무 permission이 있는 관리자는 제한된 읽기 화면에 접근할 수 있다")
	void adminPermissionAllowsRestrictedReadPages() throws Exception {
		mockMvc.perform(get("/admin/reports/page")
				.with(user("reporter").authorities(new SimpleGrantedAuthority("admin.report.review"))))
			.andExpect(status().isOk());
		mockMvc.perform(get("/admin/notifications/push/page")
				.with(user("operator").authorities(new SimpleGrantedAuthority("admin.data.operate"))))
			.andExpect(status().isOk());
	}

	@Test
	@DisplayName("자동 상태 badge는 적용 화면의 실제 label을 tone으로 분류한다")
	void statusAutoCoversConvertedListLabels() throws Exception {
		String shell = new String(
			getClass().getResourceAsStream("/templates/admin/fragments/shell.html").readAllBytes(),
			StandardCharsets.UTF_8
		);

		assertThat(shell)
			.contains("반영됨")
			.contains("폐쇄")
			.contains("접수됨")
			.contains("실행 중")
			.contains("사용자 제보")
			.contains("관리자 확인");
	}

	@Test
	@DisplayName("권한이 없는 관리자는 푸시 발송 entrypoint에 접근할 수 없다")
	void adminPermissionBlocksPushEntrypoint() throws Exception {
		mockMvc.perform(post("/admin/notifications/push")
				.with(user("viewer").authorities(new SimpleGrantedAuthority("admin.view")))
				.with(csrf()))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("관리자 로그인 화면은 Spring Security form login으로 대시보드에 진입한다")
	void adminLoginUsesSecurityFormLogin() throws Exception {
		mockMvc.perform(get("/admin/dashboard/page")
				.header("Accept", "text/html"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrlPattern("**/admin/login"));

		String html = mockMvc.perform(get("/admin/login"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("관리자 로그인")
			.contains("통합 관리자 콘솔")
			.contains("name=\"username\"")
			.contains("name=\"password\"");

		mockMvc.perform(post("/admin/login")
				.with(csrf())
				.param("username", "admin-user")
				.param("password", "admin-test-password"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/admin/dashboard/page"));
	}

	@Test
	@DisplayName("시설 등록·수정 화면은 실제 마스터 데이터 저장 흐름을 사용한다")
	void facilityEditorSavesMasterData() throws Exception {
		mockMvc.perform(post("/admin/facilities/editor/page")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.param("facilityId", "facility-sangnoksu-elevator-1")
				.param("stationId", "station-sangnoksu")
				.param("exitId", "exit-sangnoksu-1")
				.param("type", "ELEVATOR")
				.param("name", "1번 출구 엘리베이터 QA 수정")
				.param("floorFrom", "지상")
				.param("floorTo", "대합실")
				.param("latitude", "37.302421")
				.param("longitude", "126.866221")
				.param("description", "QA 요청으로 관리자 편집 저장을 확인합니다.")
				.param("status", "ADMIN_VERIFIED")
				.param("dataConfidence", "HIGH")
				.param("dataSourceType", "ADMIN_VERIFIED"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/admin/facilities/editor/page?stationId=station-sangnoksu&facilityId=facility-sangnoksu-elevator-1"));

		String updatedHtml = mockMvc.perform(get("/admin/facilities/editor/page")
				.param("stationId", "station-sangnoksu")
				.param("facilityId", "facility-sangnoksu-elevator-1")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(updatedHtml)
			.contains("1번 출구 엘리베이터 QA 수정")
			.contains("ADMIN_VERIFIED")
			.contains("QA 요청으로 관리자 편집 저장을 확인합니다.");

		mockMvc.perform(post("/admin/facilities/editor/page")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.param("stationId", "station-sangnoksu")
				.param("exitId", "exit-sangnoksu-2")
				.param("type", "RAMP")
				.param("name", "2번 출구 경사로")
				.param("floorFrom", "지상")
				.param("floorTo", "대합실")
				.param("latitude", "37.302500")
				.param("longitude", "126.866300")
				.param("description", "휠체어 이용자가 2번 출구에서 대합실로 이동하는 경로입니다.")
				.param("status", "NORMAL")
				.param("dataConfidence", "MEDIUM")
				.param("dataSourceType", "ADMIN_VERIFIED"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrlPattern("/admin/facilities/editor/page?stationId=station-sangnoksu&facilityId=facility-station-sangnoksu-ramp-*"));

		String listHtml = mockMvc.perform(get("/admin/facilities/editor/page")
				.param("stationId", "station-sangnoksu")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(listHtml).contains("2번 출구 경사로");
	}

	@Test
	@DisplayName("현장 검증 상세 화면은 각 검증 항목을 개별 저장한다")
	void fieldVerificationDetailSavesEachItem() throws Exception {
		String html = mockMvc.perform(get("/admin/field-verifications/station-sangnoksu/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("/admin/field-verifications/station-sangnoksu/items/field-verification-sangnoksu-exit/page/status")
			.contains("/admin/field-verifications/station-sangnoksu/items/field-verification-sangnoksu-escalator/page/status")
			.contains("상록수역")
			.contains("변경할 상태 선택");

		mockMvc.perform(post("/admin/field-verifications/station-sangnoksu/items/field-verification-sangnoksu-escalator/page/status")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.param("status", "NEEDS_RECHECK")
				.param("note", "에스컬레이터 방향 재확인이 필요합니다."))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/admin/field-verifications/station-sangnoksu/page"));

		String updatedHtml = mockMvc.perform(get("/admin/field-verifications/station-sangnoksu/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(updatedHtml)
			.contains("재확인 필요")
			.contains("에스컬레이터 방향 재확인이 필요합니다.");
	}

	private void assertPage(String path, String expectedText) throws Exception {
		String html = mockMvc.perform(get(path)
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("통합 관리자")
			.contains("admin-v3")
			.contains("class=\"admin-shell\"")
			.contains("class=\"admin-topbar-row\"")
			.contains("href=\"#admin-content\"")
			.contains("id=\"admin-content\"")
			.doesNotContain("<main id=\"admin-content\"")
			.contains("DEV")
			.contains("관리자 <strong>")
			.contains("revision <strong>local</strong>")
			.contains("master data <strong>unknown</strong>")
			.contains(expectedText);
		assertThat(html.indexOf("href=\"#admin-content\""))
			.isLessThan(html.indexOf("class=\"admin-shell\""));
		assertThat(html.indexOf("class=\"admin-topbar-row\""))
			.isLessThan(html.indexOf("id=\"admin-content\""));
		assertThat(html.indexOf("id=\"admin-content\""))
			.isLessThan(html.indexOf("<header class=\"admin-page-head\">"));
	}
}

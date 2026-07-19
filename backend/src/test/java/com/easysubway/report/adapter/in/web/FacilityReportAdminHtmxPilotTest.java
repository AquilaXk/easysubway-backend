package com.easysubway.report.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.admin.savedview.application.port.in.AdminSavedViewUseCase;
import com.easysubway.admin.savedview.application.port.in.SaveAdminSavedViewCommand;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 관리자 프론트 상호작용 기반(#1736)의 htmx·CSP·SRI 규약 참조 테스트.
 *
 * <p>이후 화면군 이슈(#1739~)는 이 패턴을 복제한다:
 * <ol>
 *   <li>일반 요청 = 셸을 포함한 풀페이지 응답.</li>
 *   <li>{@code HX-Request: true} = {@code th:fragment} 하나만 담은 부분 응답.</li>
 *   <li>부분 갱신 트리거는 {@code href}(no-JS fallback)와 {@code hx-get}을 함께 갖는다.</li>
 *   <li>관리자 응답에는 CSP 헤더가 있고, 스크립트는 self-host + SRI이며 외부 CDN 참조가 없다.</li>
 * </ol>
 */
@SpringBootTest(properties = {
	"easysubway.admin.username=admin-test",
	"easysubway.admin.password=admin-test-password",
	"easysubway.user.username=basic-user",
	"easysubway.user.password=user-test-password"
})
@AutoConfigureMockMvc
@DisplayName("관리자 프론트 상호작용 기반 파일럿(#1736)")
class FacilityReportAdminHtmxPilotTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private AdminSavedViewUseCase savedViewUseCase;

	@Test
	@DisplayName("일반 요청은 셸·스크립트·결과 fragment를 모두 포함한 풀페이지를 반환한다")
	void plainRequestReturnsFullPageWithShellAndScripts() throws Exception {
		createReport("풀페이지에서 확인할 신고");

		String html = mockMvc.perform(get("/admin/reports/page")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("<!doctype html>")
			.contains("admin-sidebar")
			.contains("id=\"report-results\"")
			.contains("신고 상태 필터")
			.contains("풀페이지에서 확인할 신고");
	}

	@Test
	@DisplayName("HX-Request 요청은 셸 없이 report-results fragment만 반환한다")
	void htmxRequestReturnsOnlyTheResultsFragment() throws Exception {
		createReport("부분 응답에서 확인할 신고");

		String fragment = mockMvc.perform(get("/admin/reports/page")
				.header("HX-Request", "true")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(fragment)
			.contains("id=\"report-results\"")
			.contains("신고 상태 필터")
			.contains("부분 응답에서 확인할 신고");
		assertThat(fragment)
			.doesNotContain("<!doctype html>")
			.doesNotContain("admin-sidebar")
			.doesNotContain("<html");
	}

	@Test
	@DisplayName("htmx 히스토리 복원 요청은 fragment가 아니라 셸을 포함한 풀페이지를 반환한다")
	void htmxHistoryRestoreRequestReturnsFullPageNotFragment() throws Exception {
		String html = mockMvc.perform(get("/admin/reports/page")
				.header("HX-Request", "true")
				.header("HX-History-Restore-Request", "true")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("<!doctype html>")
			.contains("admin-sidebar")
			.contains("id=\"report-results\"");
	}

	@Test
	@DisplayName("상태 필터 링크는 no-JS fallback href와 htmx hx-get을 함께 갖는다")
	void statusFilterLinksExposeBothHrefAndHxGet() throws Exception {
		String html = mockMvc.perform(get("/admin/reports/page")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("href=\"/admin/reports/page?status=SUBMITTED\"")
			.contains("hx-get=\"/admin/reports/page?status=SUBMITTED\"")
			.contains("hx-target=\"#report-results\"")
			.contains("hx-swap=\"outerHTML\"")
			.contains("hx-push-url=\"true\"");
	}

	@Test
	@DisplayName("관리자 응답에 CSP 헤더가 있고 스크립트는 self-host + SRI이며 외부 CDN 참조가 없다")
	void adminResponseCarriesCspAndSelfHostedScriptsWithSri() throws Exception {
		String html = mockMvc.perform(get("/admin/reports/page")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Security-Policy", containsString("script-src 'self'")))
			.andExpect(header().string("Content-Security-Policy", containsString("object-src 'none'")))
			.andExpect(header().string("Content-Security-Policy", containsString("base-uri 'self'")))
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("/vendor/htmx-2.0.10/htmx.min.js")
			.contains("/js/admin/app.js")
			.contains("/vendor/alpinejs-csp-3.15.12/cdn.min.js")
			.contains("integrity=\"sha384-")
			.contains("crossorigin=\"anonymous\"")
			// htmx의 인라인 indicator <style> 주입을 꺼 style-src 'self' 위반을 방지한다.
			.contains("name=\"htmx-config\"")
			.contains("includeIndicatorStyles");
		assertThat(html)
			.doesNotContain("cdn.jsdelivr.net")
			.doesNotContain("unpkg.com")
			.doesNotContain("cdnjs.cloudflare.com");
		assertThat(html.indexOf("/vendor/htmx-2.0.10/htmx.min.js"))
			.isLessThan(html.indexOf("/js/admin/app.js"));
		assertThat(html.indexOf("/js/admin/app.js"))
			.isLessThan(html.indexOf("/vendor/alpinejs-csp-3.15.12/cdn.min.js"));
	}

	@Test
	@DisplayName("키워드 검색은 신고 내용으로 목록을 거른다")
	void keywordSearchFiltersReports() throws Exception {
		createReport("엘리베이터가 멈췄습니다");
		createReport("에스컬레이터 소음 문제");

		String html = mockMvc.perform(get("/admin/reports/page")
				.param("keyword", "엘리베이터")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("엘리베이터가 멈췄습니다")
			.doesNotContain("에스컬레이터 소음 문제");
	}

	@Test
	@DisplayName("검색 툴바와 정렬 헤더가 접근성 속성과 함께 렌더된다")
	void searchToolbarAndSortableHeadersRender() throws Exception {
		String html = mockMvc.perform(get("/admin/reports/page")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("role=\"search\"")
			.contains("name=\"keyword\"")
			// 기본 정렬은 접수일 내림차순
			.contains("aria-sort=\"descending\"")
			.contains("sort=status,asc")
			.contains("sort=created_at,asc");
	}

	@Test
	@DisplayName("신고 대기열은 V6-06 공통 툴바(list-toolbar)를 소비하고 reportTable 스코프를 컨테이너로 올린다")
	void reportQueueConsumesUnifiedListToolbar() throws Exception {
		createReport("툴바 이관 확인 신고");

		String html = mockMvc.perform(get("/admin/reports/page")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();

		assertThat(html)
			// 공통 툴바 루트 + 시트 트리거(필터·보기 설정)를 소비한다.
			.contains("class=\"admin-list-toolbar\"")
			.contains("admin-toolbar-filter-sheet")
			.contains("admin-toolbar-view-sheet")
			// 유형·사진 필터는 필터 시트 form으로, 밀도·열 표시는 보기 설정 시트로 이관된다.
			.contains("aria-label=\"유형·사진 필터\"")
			.contains("class=\"table-viewbar\"")
			// 보기 설정(밀도·열 표시)이 동작하도록 reportTable 스코프가 컨테이너(#report-results)로 올라간다.
			.contains("id=\"report-results\"")
			.contains("x-data=\"reportTable\"")
			.contains("x-bind:class=\"tableClass\"");
		// 필터 form은 키워드·상태를 hidden으로 보존해 필터 변경이 검색·상태를 덮어쓰지 않는다(§7 filter binding).
		assertThat(html.replaceAll("\\s+", " "))
			.contains("<input type=\"hidden\" name=\"keyword\"")
			.contains("<input type=\"hidden\" name=\"status\"");
	}

	@Test
	@DisplayName("검색·상태 필터 링크는 현재 키워드를 유지한다")
	void filterLinksPreserveKeyword() throws Exception {
		String html = mockMvc.perform(get("/admin/reports/page")
				.param("keyword", "엘리베이터")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("keyword=%EC%97%98%EB%A6%AC%EB%B2%A0%EC%9D%B4%ED%84%B0")
			.contains("status=SUBMITTED");
	}

	@Test
	@DisplayName("기간 프리셋이 렌더되고 활성 필터는 제거 칩으로 표시된다")
	void datePresetsAndFilterChipsRender() throws Exception {
		String html = mockMvc.perform(get("/admin/reports/page")
				.param("keyword", "엘리베이터")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("오늘")
			.contains("최근 7일")
			.contains("최근 30일")
			.contains("전체 기간")
			.contains("class=\"filter-chip\"")
			.contains("검색: 엘리베이터");
	}

	@Test
	@DisplayName("기본 저장 뷰가 있으면 필터 없이 진입 시 그 질의로 리다이렉트된다(루프 없음)")
	void defaultSavedViewAppliesOnFreshEntry() throws Exception {
		var view = savedViewUseCase.saveView(new SaveAdminSavedViewCommand(
			"admin-test", "a-reports", "기본 진입 뷰", "status=SUBMITTED", true));
		try {
			mockMvc.perform(get("/admin/reports/page")
					.with(httpBasic("admin-test", "admin-test-password")))
				.andExpect(status().is3xxRedirection())
				.andExpect(header().string("Location", "/admin/reports/page?status=SUBMITTED"));
			// 파라미터가 붙은 요청은 재리다이렉트하지 않는다(루프 방지).
			mockMvc.perform(get("/admin/reports/page")
					.param("status", "RESOLVED")
					.with(httpBasic("admin-test", "admin-test-password")))
				.andExpect(status().isOk());
		} finally {
			savedViewUseCase.deleteView("admin-test", view.viewId());
		}
	}

	@Test
	@DisplayName("저장된 뷰 저장 폼과 저장된 뷰 적용 링크가 렌더된다")
	void savedViewsSectionRenders() throws Exception {
		savedViewUseCase.saveView(new SaveAdminSavedViewCommand(
			"admin-test", "a-reports", "미확인 급증", "status=SUBMITTED", false));

		String html = mockMvc.perform(get("/admin/reports/page")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			// 저장 폼
			.contains("class=\"save-view\"")
			.contains("name=\"programId\"")
			.contains("현재 검색 저장")
			.contains("name=\"commandToken\"")
			// 적용 링크
			.contains("미확인 급증")
			.contains("/admin/reports/page?status=SUBMITTED");
	}

	@Test
	@DisplayName("상세 풀페이지는 breadcrumb를 보여주고 드로어 fragment에는 없다")
	void detailPageShowsBreadcrumbButDrawerFragmentDoesNot() throws Exception {
		String reportId = createReport("breadcrumb 확인 신고");

		String fullPage = mockMvc.perform(get("/admin/reports/{id}/page", reportId)
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(fullPage)
			.contains("admin-breadcrumb")
			.contains("aria-label=\"위치\"")
			.contains("제보 확인 대기열");

		String drawerFragment = mockMvc.perform(get("/admin/reports/{id}/page", reportId)
				.header("HX-Request", "true")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(drawerFragment).doesNotContain("admin-breadcrumb");
	}

	@Test
	@DisplayName("상세를 htmx로 열면 셸 없이 상세 본문 fragment만 오고 드로어 열기 트리거가 붙는다")
	void detailDrawerFragmentReturnsBodyWithOpenTrigger() throws Exception {
		String reportId = createReport("드로어로 볼 신고");

		var result = mockMvc.perform(get("/admin/reports/{id}/page", reportId)
				.header("HX-Request", "true")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(header().string("HX-Trigger", org.hamcrest.Matchers.containsString("admin-drawer-open")))
			.andReturn();
		String fragment = result.getResponse().getContentAsString();

		assertThat(fragment)
			.contains("제보 상세·판정")
			.contains("드로어로 볼 신고");
		assertThat(fragment)
			.doesNotContain("<!doctype html>")
			.doesNotContain("admin-sidebar");
	}

	@Test
	@DisplayName("목록의 드로어 컨테이너와 상세 링크의 드로어 타깃이 렌더된다")
	void drawerContainerAndDetailLinkRender() throws Exception {
		createReport("드로어 링크 신고");

		String html = mockMvc.perform(get("/admin/reports/page")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("class=\"admin-drawer\"")
			.contains("id=\"admin-drawer-body\"")
			.contains("hx-target=\"#admin-drawer-body\"");
	}

	@Test
	@DisplayName("토스트 영역이 aria-live와 함께 렌더된다(no-JS는 서버 flash가 대체)")
	void toastRegionRenders() throws Exception {
		String html = mockMvc.perform(get("/admin/reports/page")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("class=\"toast-region\"")
			.contains("aria-live=\"polite\"")
			.contains("x-data=\"toastHub\"");
	}

	@Test
	@DisplayName("표 보기 설정(밀도·컬럼 토글)과 표 스코프가 렌더된다")
	void tableViewControlsRender() throws Exception {
		String html = mockMvc.perform(get("/admin/reports/page")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("x-data=\"reportTable\"")
			.contains("class=\"table-viewbar\"")
			.contains("좁게")
			.contains("넓게")
			.contains("좌표 숨김")
			.contains("사진 숨김")
			.contains("x-bind:class=\"tableClass\"");
	}

	@Test
	@DisplayName("결과가 없으면 표준 빈 상태 컴포넌트가 안내 문구와 함께 렌더된다")
	void emptyResultRendersEmptyStateComponent() throws Exception {
		String html = mockMvc.perform(get("/admin/reports/page")
				.param("keyword", "존재하지않는키워드zzz")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("class=\"empty-state\"")
			.contains("확인할 신고가 없습니다.")
			.contains("검색어·상태·기간 필터를 조정해 보세요.");
	}

	private String createReport(String description) throws Exception {
		String created = mockMvc.perform(post("/api/v1/reports")
				.with(httpBasic("basic-user", "user-test-password"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "stationId": "station-sangnoksu",
					  "facilityId": "facility-sangnoksu-elevator-1",
					  "reportType": "BROKEN",
					  "description": "%s"
					}
					""".formatted(description)))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return JsonPath.read(created, "$.data.id");
	}
}

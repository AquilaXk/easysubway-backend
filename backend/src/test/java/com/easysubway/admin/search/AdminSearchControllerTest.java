package com.easysubway.admin.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.admin.authorization.AdminPermission;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties = {
	"easysubway.admin.username=admin-test",
	"easysubway.admin.password=admin-test-password"
})
@AutoConfigureMockMvc
@DisplayName("관리자 통합 검색")
class AdminSearchControllerTest {

	// nav.admin-sidebar(id="admin-primary-nav") 블록만 잘라내 트리거가 그 안에 있는지 구조적으로 검증한다.
	private static final Pattern ADMIN_SIDEBAR_NAV = Pattern.compile(
		"<nav class=\"admin-sidebar\" id=\"admin-primary-nav\".*?</nav>", Pattern.DOTALL);

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("검색은 메뉴 라벨로 화면을 찾는다")
	void searchFindsMenuByLabel() throws Exception {
		String html = mockMvc.perform(get("/admin/search")
				.param("q", "제보")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("제보 확인 대기열")
			.contains("/admin/reports/page");
	}

	@Test
	@DisplayName("권한이 없는 계정에게는 보이지 않는 메뉴가 결과에서 제외된다")
	void searchExcludesProgramsWithoutPermission() throws Exception {
		// ADMIN_VIEW만 가진 계정은 MASTER_EDIT 화면(역 구조·동선 편집)을 볼 수 없다.
		RequestPostProcessor viewOnly = user("viewer")
			.authorities(new SimpleGrantedAuthority(AdminPermission.ADMIN_VIEW.authority()));

		String html = mockMvc.perform(get("/admin/search")
				.param("q", "역")
				.with(viewOnly))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("역 목록")
			.doesNotContain("역 구조·동선 편집");
	}

	@Test
	@DisplayName("MASTER_EDIT 권한이 있으면 편집 화면이 결과에 포함된다")
	void searchIncludesMasterEditProgramWhenPermitted() throws Exception {
		RequestPostProcessor masterEditor = user("editor").authorities(
			new SimpleGrantedAuthority(AdminPermission.ADMIN_VIEW.authority()),
			new SimpleGrantedAuthority(AdminPermission.MASTER_EDIT.authority()));

		String html = mockMvc.perform(get("/admin/search")
				.param("q", "역 구조")
				.with(masterEditor))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html).contains("역 구조·동선 편집");
	}

	@Test
	@DisplayName("HX-Request는 셸 없이 결과 fragment만 반환한다")
	void hxRequestReturnsResultsFragmentOnly() throws Exception {
		String fragment = mockMvc.perform(get("/admin/search")
				.param("q", "제보")
				.header("HX-Request", "true")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(fragment).contains("제보 확인 대기열");
		assertThat(fragment)
			.doesNotContain("<!doctype html>")
			.doesNotContain("admin-sidebar");
	}

	@Test
	@DisplayName("사이드바에 커맨드 팔레트 트리거가 렌더되고 no-JS는 검색 페이지로 이동한다")
	void sidebarRendersCommandPalette() throws Exception {
		String html = mockMvc.perform(get("/admin/search")
				.param("q", "제보")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("class=\"command-palette\"")
			.contains("command-palette-trigger")
			.contains("x-data=\"commandPalette\"")
			.contains("id=\"palette-results\"")
			// no-JS fallback: 트리거는 검색 전용 페이지로 이동
			.contains("href=\"/admin/search\"");

		// 트리거가 topbar가 아니라 사이드바(nav.admin-sidebar) 내부에 실제로 위치하는지 구조적으로 검증한다.
		Matcher navMatcher = ADMIN_SIDEBAR_NAV.matcher(html);
		assertThat(navMatcher.find())
			.as("nav.admin-sidebar(id=admin-primary-nav) 블록을 찾을 수 없습니다")
			.isTrue();
		String sidebarNavHtml = navMatcher.group();

		assertThat(sidebarNavHtml)
			.as("사이드바 nav 내부에 퀵서치·커맨드 팔레트 트리거가 있어야 합니다")
			.contains("class=\"admin-sidebar-quicksearch command-palette-trigger\"");
	}

	@Test
	@DisplayName("결과가 없으면 빈 상태를 보여준다")
	void emptyResultShowsEmptyState() throws Exception {
		String html = mockMvc.perform(get("/admin/search")
				.param("q", "존재하지않는검색어zzz")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html).contains("검색 결과가 없습니다.");
	}
}

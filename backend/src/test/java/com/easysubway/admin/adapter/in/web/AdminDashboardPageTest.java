package com.easysubway.admin.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.admin.authorization.AdminPermission;
import com.easysubway.admin.metric.application.port.out.AdminMetricDailyRepository;
import com.easysubway.admin.metric.domain.AdminMetricDaily;
import com.easysubway.admin.metric.domain.AdminMetricKeys;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
	"easysubway.admin.username=admin-test",
	"easysubway.admin.password=admin-test-password"
})
@AutoConfigureMockMvc
@DisplayName("통합 대시보드 재설계")
class AdminDashboardPageTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private AdminMetricDailyRepository repository;

	@Test
	@DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
	@DisplayName("현재 운영 상태와 최근 7일 제보 흐름을 실제 지표로 렌더한다")
	void rendersCurrentOperationsAndSevenDayReportFlow() throws Exception {
		repository.save(AdminMetricDaily.scalar(AdminMetricKeys.REPORTS_RECENT_24H, LocalDate.now().minusDays(1), 4));
		repository.save(AdminMetricDaily.scalar(AdminMetricKeys.REPORTS_RECENT_24H, LocalDate.now(), 7));

		String html = mockMvc.perform(get("/admin/dashboard/page")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("<title>오늘의 운영 현황</title>")
			.contains("<h1>오늘의 운영 현황</h1>")
			.contains("id=\"dashboard-current-operations\"")
			.contains("기준")
			.contains("aria-label=\"운영 상태 새로고침\"")
			.contains("href=\"/admin/dashboard/page\" aria-label=\"운영 상태 새로고침\"")
			.contains("전체 서비스")
			.containsPattern("<dt>제보</dt>\\s*<dd[^>]*>\\d+건</dd>")
			.doesNotContainPattern("class=\"is-warn\"[^>]*>\\s*<dt>데이터</dt>")
			.containsPattern("class=\"\\s*is-waiting\"[^>]*>\\s*<dt>지표 집계</dt>\\s*<dd[^>]*>대기</dd>")
			.contains("최근 7일 API 정상률")
			.containsPattern("최근 7일 API 정상률</span>\\s*<strong><span aria-hidden=\\\"true\\\">—</span><span class=\\\"sr-only\\\">집계 없음</span></strong>")
			.contains("id=\"dashboard-weekly-operations\"")
			.contains("data-weekday=\"월\"")
			.contains("data-weekday=\"화\"")
			.contains("data-weekday=\"수\"")
			.contains("data-weekday=\"목\"")
			.contains("data-weekday=\"금\"")
			.contains("data-weekday=\"토\"")
			.contains("data-weekday=\"일\"")
			.containsPattern("class=\"dashboard-week-day\\s+is-today[\\s\"]")
			.containsPattern("class=\"dashboard-weekly-total\">[1-2]일 집계")
			.contains("aria-label=\"이번 주 최근 24시간 제보 스냅샷\"")
			.doesNotContain("aria-label=\"최근 7일 제보 접수 현황\"")
			.doesNotContain("처리 완료")
			.doesNotContain("미처리")
			.doesNotContain("주간 처리율")
			.contains("class=\"dashboard-reference-lower\"")
			.doesNotContain("최근 7일 운영 추이")
			.doesNotContain("aria-label=\"API 정상률\"")
			.doesNotContain("오류율 0.0%");
	}

	@Test
	@DisplayName("주간 제보 스냅샷은 합계 대신 집계된 날짜 수를 표시한다")
	void labelsWeeklySnapshotCoverage() {
		assertThat(AdminOverviewPageController.weeklyCoverageLabel(0)).isEqualTo("집계 대기");
		assertThat(AdminOverviewPageController.weeklyCoverageLabel(2)).isEqualTo("2일 집계");
		assertThat(AdminOverviewPageController.weeklyCoverageLabel(7)).isEqualTo("7일 집계");
	}

	@Test
	@DisplayName("핵심 카드가 클릭 가능하고 스냅샷 이력이 있으면 스파크라인을 그린다")
	void rendersClickableCardsWithSparkline() throws Exception {
		repository.save(AdminMetricDaily.scalar(AdminMetricKeys.REPORTS_PENDING, LocalDate.now().minusDays(1), 3));
		repository.save(AdminMetricDaily.scalar(AdminMetricKeys.REPORTS_PENDING, LocalDate.now(), 8));

		String html = mockMvc.perform(get("/admin/dashboard/page")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("class=\"dashboard-card metric-cell\"")
			.contains("오늘의 운영 현황")
			.doesNotContain("지금 급한 것 → 추세 → 상세 순으로 신고·시설·경로·알림·시스템을 모읍니다.")
			.contains("class=\"dashboard-snapshot-form\" data-dashboard-snapshot-form")
			.contains(">지표 다시 집계</button>")
			.contains("확인할 제보")
			.contains("href=\"/admin/reports/page\"")
			.contains("dashboard-spark")
			.contains("<polyline");
	}

	@Test
	@DisplayName("축과 범례가 없는 장식 운영 추이를 렌더하지 않는다")
	void omitsDecorativeReferenceTrend() throws Exception {
		repository.save(AdminMetricDaily.scalar(AdminMetricKeys.REPORTS_PENDING, LocalDate.now().minusDays(1), 3));
		repository.save(AdminMetricDaily.scalar(AdminMetricKeys.REPORTS_PENDING, LocalDate.now(), 8));

		String html = mockMvc.perform(get("/admin/dashboard/page")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("class=\"dashboard-reference-lower\"")
			.contains("class=\"dashboard-operations-details\"")
			.contains("상세 운영 지표 보기")
			.contains("id=\"dashboard-trends\"")
			.contains("class=\"admin-grid-2 dashboard-readiness-grid")
			.doesNotContain("dashboard-reference-trend")
			.doesNotContain("최근 7일 운영 추이");
	}

	@Test
	@DisplayName("핵심 지표는 대표 KPI 3개를 headline으로 노출하고 나머지는 disclosure로 격하한다")
	void foregroundsThreeRepresentativeKpisWithRestInDisclosure() throws Exception {
		String html = mockMvc.perform(get("/admin/dashboard/page")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		// 기간 표기 명확화(headline 의미 희석 해소): 값=현재, 스파크라인=최근 7일, 델타=전일 대비.
		assertThat(html)
			.contains("현재 값 · 최근 7일 스파크라인 · 전일 대비")
			// 대표 KPI 3개(시점·비율)는 headline, 4번째(누계 총량인 푸시 실패)는 disclosure로 격하.
			.contains("class=\"dashboard-details dashboard-more\"")
			.contains("나머지 지표")
			.contains("확인 필요 시설")
			.contains("경로 차단률")
			.contains("푸시 실패");
	}

	@Test
	@DisplayName("권한 부분집합(ADMIN_VIEW+DATA_OPERATE, REPORT_REVIEW 없음)에서도 누계 카드(푸시 실패)는 headline이 아니라 disclosure로 격하된다")
	void demotesCumulativeCardEvenWhenHeadlineWouldFillWithoutIt() throws Exception {
		// REPORT_REVIEW가 없으면 대표 제보 카드가 빠져 남는 카드가 3개(시설·차단률·푸시 실패)가 된다.
		// index 기반이면 3개가 전부 headline이라 누계 총량인 푸시 실패가 headline으로 승격되는 결함(#2306
		// 리뷰). 지표 의미(metric key) 기반 격하는 index와 무관하게 누계 카드를 항상 disclosure로 내린다.
		String html = mockMvc.perform(get("/admin/dashboard/page")
				.with(user("dataop").authorities(
					new SimpleGrantedAuthority(AdminPermission.ADMIN_VIEW.authority()),
					new SimpleGrantedAuthority(AdminPermission.DATA_OPERATE.authority()))))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		int disclosureAt = html.indexOf("class=\"dashboard-details dashboard-more\"");
		assertThat(disclosureAt).as("누계 카드를 담는 disclosure 블록이 존재해야 한다").isGreaterThan(-1);
		// 카드 정체성은 data-metric-key로 판정한다(서술 코멘트 텍스트에 의존하지 않게).
		String headline = html.substring(0, disclosureAt);
		String disclosure = html.substring(disclosureAt);
		assertThat(headline)
			.contains("data-metric-key=\"" + AdminMetricKeys.FACILITIES_NEEDS_VERIFICATION + "\"")
			.contains("data-metric-key=\"" + AdminMetricKeys.ROUTE_BLOCKED_RATE + "\"")
			.doesNotContain("data-metric-key=\"" + AdminMetricKeys.REPORTS_PENDING + "\"")
			.doesNotContain("data-metric-key=\"" + AdminMetricKeys.PUSH_FAILED + "\"");
		assertThat(disclosure).contains("data-metric-key=\"" + AdminMetricKeys.PUSH_FAILED + "\"");
	}

	@Test
	@DisplayName("지표 스냅샷 수동 재실행은 command token으로 집계 후 대시보드로 리다이렉트한다")
	void manualSnapshotRerunRedirects() throws Exception {
		MockHttpSession session = new MockHttpSession();
		String token = issueCommandToken(session);

		mockMvc.perform(post("/admin/dashboard/metrics/snapshot")
				.session(session)
				.with(httpBasic("admin-test", "admin-test-password"))
				.with(csrf())
				.param("commandToken", token))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/admin/dashboard/page"));
	}

	@Test
	@DisplayName("추이 섹션은 스냅샷이 있으면 차트 canvas와 접근성 대체 표·기간 선택을 렌더한다")
	void rendersTrendChartsWithAltTable() throws Exception {
		seedAllTrendMetrics();

		String html = mockMvc.perform(get("/admin/dashboard/page")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("class=\"trend-periods\"")
			.contains("class=\"trend-canvas\"")
			.contains("data-chart=")
			.contains("role=\"img\"")
			.contains("데이터 표로 보기")
			.contains("/js/admin/dashboard-charts.js")
			.as("데이터가 있으면 빈 상태를 렌더하지 않는다(#2327 회귀 금지)")
			.doesNotContain("아직 집계된 추이가 없습니다.");
	}

	@Test
	@DisplayName("기간 버튼은 HX-Request로 추이 fragment만 부분 갱신한다")
	void trendsHxFragmentSwitchesPeriod() throws Exception {
		seedAllTrendMetrics();

		String fragment = mockMvc.perform(get("/admin/dashboard/trends")
				.param("days", "30")
				.header("HX-Request", "true")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(fragment)
			.contains("class=\"trend-canvas\"")
			.doesNotContain("<!doctype html>")
			.doesNotContain("admin-sidebar");
	}

	// 조회 기간 내 스냅샷이 전무할 때 canvas 대신 empty-state를 렌더하는 분기(#2327)는
	// AdminV3PageSmokeTest#dashboardTrendsRenderEmptyStateWhenNoMetricSnapshot(별도 컨텍스트, 스냅샷
	// 미기록 상태 보장)과 AdminMetricQueryServiceTest(서비스 단위)에서 고정한다. 이 클래스는 테스트
	// 메서드 간 DB 상태가 공유(롤백 없음)돼 순서에 따라 스냅샷 유무가 달라지므로, "전무" 단정 테스트는
	// 여기 두지 않는다.

	// 추이 섹션이 참조하는 4개 지표 키(REPORTS_RECENT_24H·REPORTS_PENDING·ROUTE_BLOCKED_RATE·
	// API_ERROR_RATE) 모두에 오늘자 스냅샷을 채워 두 추이 패널이 모두 데이터를 갖게 한다(#2327).
	private void seedAllTrendMetrics() {
		repository.save(AdminMetricDaily.scalar(AdminMetricKeys.REPORTS_RECENT_24H, LocalDate.now(), 5));
		repository.save(AdminMetricDaily.scalar(AdminMetricKeys.REPORTS_PENDING, LocalDate.now(), 3));
		repository.save(AdminMetricDaily.scalar(AdminMetricKeys.ROUTE_BLOCKED_RATE, LocalDate.now(), 12));
		repository.save(AdminMetricDaily.scalar(AdminMetricKeys.API_ERROR_RATE, LocalDate.now(), 1));
	}

	private String issueCommandToken(MockHttpSession session) throws Exception {
		String html = mockMvc.perform(get("/admin/dashboard/page")
				.session(session)
				.with(httpBasic("admin-test", "admin-test-password")))
			.andReturn()
			.getResponse()
			.getContentAsString();
		Matcher matcher = Pattern.compile("name=\"commandToken\" value=\"([^\"]+)\"").matcher(html);
		assertThat(matcher.find()).as("command token in dashboard form").isTrue();
		return matcher.group(1);
	}
}

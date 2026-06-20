package com.easysubway.operator.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.application.port.in.RouteSearchUseCase;
import com.easysubway.route.application.port.in.SearchRouteCommand;
import com.easysubway.route.application.port.in.SubmitRouteFeedbackCommand;
import com.easysubway.route.domain.RouteFeedbackRating;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
	"easysubway.admin.username=admin-user",
	"easysubway.admin.password=admin-test-password",
	"easysubway.operator.username=operator-user",
	"easysubway.operator.password=operator-test-password",
	"easysubway.user.username=basic-user",
	"easysubway.user.password=user-test-password"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("운영기관 이동 불편 신고 분석 화면")
class OperatorRouteFeedbackReportPageControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private RouteSearchUseCase routeSearchUseCase;

	@Test
	@DisplayName("운영기관 계정은 읽기 전용 이동 불편 신고 분석 화면을 확인한다")
	void operatorGetsRouteFeedbackReportPage() throws Exception {
		String routeSearchId = createRouteSearch();
		submitRouteFeedback(routeSearchId, "HELPFUL", "안내가 도움이 됐어요");
		submitRouteFeedback(routeSearchId, "NOT_HELPFUL", "실제 이동 상황과 달랐어요");
		submitRouteFeedback(routeSearchId, "BLOCKED_BY_REAL_WORLD", "엘리베이터가 막혀 있었어요");

		String html = mockMvc.perform(get("/operator/route-feedback-report/page")
				.with(httpBasic("operator-user", "operator-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("운영기관 이동 불편 신고 분석")
			.contains("읽기 전용 리포트")
			.contains("전체 피드백")
			.contains("도움이 됨")
			.contains("도움이 안 됨")
			.contains("현장 차단")
			.contains("평점별 피드백")
			.contains("최근 현장 차단 신고")
			.contains("상록수")
			.contains("사당")
			.contains("고령자")
			.doesNotContain("basic-user")
			.doesNotContain(routeSearchId)
			.doesNotContain("엘리베이터가 막혀 있었어요")
			.doesNotContain("name=\"_csrf\"")
			.doesNotContain("<form")
			.doesNotContain("/admin/reports");
	}

	@Test
	@DisplayName("운영기관 이동 불편 신고 분석 화면은 운영기관 계정 인증을 요구한다")
	void routeFeedbackReportPageRequiresOperatorAuthentication() throws Exception {
		mockMvc.perform(get("/operator/route-feedback-report/page"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/operator/route-feedback-report/page")
				.with(httpBasic("basic-user", "user-test-password")))
			.andExpect(status().isForbidden());

		mockMvc.perform(get("/operator/route-feedback-report/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isForbidden());
	}

	private String createRouteSearch() {
		return routeSearchUseCase.searchRoute(new SearchRouteCommand(
			"station-sangnoksu",
			"station-sadang",
			MobilityType.SENIOR
		)).routeSearchId();
	}

	private void submitRouteFeedback(String routeSearchId, String rating, String comment) {
		routeSearchUseCase.submitRouteFeedback(new SubmitRouteFeedbackCommand(
			routeSearchId,
			"basic-user",
			RouteFeedbackRating.valueOf(rating),
			comment
		));
	}
}

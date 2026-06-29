package com.easysubway.operator.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
@DisplayName("운영기관 접근성 시설 현황 리포트 화면")
class OperatorAccessibilityReportPageControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("운영기관 계정은 읽기 전용 접근성 시설 현황을 확인한다")
	void operatorGetsAccessibilityReportPage() throws Exception {
		String html = mockMvc.perform(get("/operator/accessibility-report/page")
				.with(httpBasic("operator-user", "operator-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("운영기관 접근성 시설 현황")
			.contains("운영기관 포털")
			.contains("협력 제안서 CSV")
			.contains("전체 역")
			.contains("접근성 시설")
			.contains("확인 필요 시설")
			.contains("검증일 누락 역")
			.contains("지역별 데이터 품질")
			.contains("일부 정보는 확인 중이에요")
			.contains("시설 정보를 함께 볼 수 있어요")
			.contains("쉬운 길 안내를 볼 수 있어요")
			.contains("고장·공사 소식이 반영됐어요")
			.contains("수도권")
			.contains("운영기관")
			.contains("노선")
			.contains("역")
			.contains("역별 접근성 점수")
			.contains("접근성 점수")
			.contains("보강 사유")
			.contains("접근성 개선 우선순위")
			.contains("우선순위 점수")
			.contains("개선 사유")
			.contains("상록수")
			.contains("장애인 화장실")
			.contains("확인 필요 상태")
			.doesNotContain("name=\"_csrf\"")
			.doesNotContain("<form")
			.doesNotContain("/admin/reports")
			.doesNotContain("station-sangnoksu")
			.doesNotContain("facility-sangnoksu");
	}

	@Test
	@DisplayName("운영기관 접근성 리포트는 운영기관 계정 인증을 요구한다")
	void accessibilityReportRequiresOperatorAuthentication() throws Exception {
		mockMvc.perform(get("/operator/accessibility-report/page"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("http://localhost/operator/login"));

		mockMvc.perform(get("/operator/accessibility-report/page")
				.with(httpBasic("basic-user", "user-test-password")))
			.andExpect(status().isForbidden());

		mockMvc.perform(get("/operator/accessibility-report/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isForbidden());
	}
}

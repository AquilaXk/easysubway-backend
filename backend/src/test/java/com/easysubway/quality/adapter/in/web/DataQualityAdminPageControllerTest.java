package com.easysubway.quality.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
	"easysubway.user.username=basic-user",
	"easysubway.user.password=user-test-password"
})
@AutoConfigureMockMvc
@DisplayName("관리자 데이터 품질 대시보드")
class DataQualityAdminPageControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("관리자는 데이터 품질 대시보드에서 주요 집계와 보강 대상을 확인한다")
	void adminGetsDataQualityDashboardPage() throws Exception {
		String html = mockMvc.perform(get("/admin/data-quality/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("데이터 품질 대시보드")
			.contains("전체 역")
			.contains(">2<")
			.contains("전체 출구")
			.contains("전체 시설")
			.contains(">3<")
			.contains("확인 필요한 시설")
			.contains("갱신 지연 시설")
			.contains("검수일 없는 역")
			.contains("Level 1")
			.contains("기본 정보 확인")
			.contains("높음")
			.contains("보통")
			.contains("확인 필요")
			.contains("지역별 데이터 품질")
			.contains("수도권")
			.contains("운영기관")
			.contains("노선")
			.contains("역")
			.contains("Level 2")
			.contains("Level 3")
			.contains("Level 4")
			.contains("시설 상태 갱신 지연")
			.contains("상태")
			.contains("지연 시설")
			.doesNotContain("station-sangnoksu")
			.doesNotContain("exit-sangnoksu")
			.doesNotContain("facility-sangnoksu");
	}

	@Test
	@DisplayName("데이터 품질 대시보드는 관리자 인증을 요구한다")
	void dataQualityDashboardRequiresAdminAuthentication() throws Exception {
		mockMvc.perform(get("/admin/data-quality/page"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/admin/data-quality/page")
				.with(httpBasic("basic-user", "user-test-password")))
			.andExpect(status().isForbidden());
	}
}

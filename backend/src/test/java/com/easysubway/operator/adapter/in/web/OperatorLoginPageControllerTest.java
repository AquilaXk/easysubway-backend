package com.easysubway.operator.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
	"easysubway.operator.password=operator-test-password"
})
@AutoConfigureMockMvc
@DisplayName("운영기관 로그인 화면")
class OperatorLoginPageControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("운영기관 로그인 화면은 공개로 렌더링된다")
	void operatorLoginPageRenders() throws Exception {
		String html = mockMvc.perform(get("/operator/login"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("운영기관 로그인")
			.contains("운영기관 포털")
			.contains("아이디")
			.contains("비밀번호")
			.contains("안전하게 로그인")
			.contains("name=\"_csrf\"");
	}

	@Test
	@DisplayName("운영기관 계정은 로그인 후 접근성 보고서로 이동한다")
	void operatorLoginRedirectsToAccessibilityReport() throws Exception {
		mockMvc.perform(post("/operator/login")
				.with(csrf())
				.param("username", "operator-user")
				.param("password", "operator-test-password"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/operator/accessibility-report/page"));
	}
}

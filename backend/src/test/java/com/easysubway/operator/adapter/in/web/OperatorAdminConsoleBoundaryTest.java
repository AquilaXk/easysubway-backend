package com.easysubway.operator.adapter.in.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * #1748 경계 조건: 운영기관(OPERATOR_ADMIN) 계정은 자신의 리포트만 볼 수 있고,
 * 관리자 콘솔 전용 기능(통합 검색·알림 센터 등)에는 접근할 수 없다.
 *
 * <p>{@link com.easysubway.admin.authorization.AdminAuthorization#authoritiesFor}가
 * OPERATOR_ADMIN에 admin 권한을 전혀 부여하지 않으므로 경계는 구조적으로 성립한다.
 * 이 테스트는 이후 RBAC 변경으로 운영기관 계정이 실수로 관리자 콘솔에 노출되는 회귀를 막는 가드다.
 */
@SpringBootTest(properties = {
	"easysubway.admin.username=admin-user",
	"easysubway.admin.password=admin-test-password",
	"easysubway.operator.username=operator-user",
	"easysubway.operator.password=operator-test-password",
	"easysubway.user.username=basic-user",
	"easysubway.user.password=user-test-password"
})
@AutoConfigureMockMvc
@DisplayName("운영기관 계정의 관리자 콘솔 접근 경계")
class OperatorAdminConsoleBoundaryTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("운영기관 계정은 관리자 통합 검색에 접근할 수 없다")
	void operatorCannotAccessAdminSearch() throws Exception {
		mockMvc.perform(get("/admin/search")
				.with(httpBasic("operator-user", "operator-test-password")))
			.andExpect(status().isForbidden());

		mockMvc.perform(get("/admin/search")
				.header("HX-Request", "true")
				.with(httpBasic("operator-user", "operator-test-password")))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("운영기관 계정은 관리자 알림 센터에 접근할 수 없다")
	void operatorCannotAccessAdminAlerts() throws Exception {
		mockMvc.perform(get("/admin/alerts")
				.with(httpBasic("operator-user", "operator-test-password")))
			.andExpect(status().isForbidden());

		mockMvc.perform(get("/admin/alerts")
				.header("HX-Request", "true")
				.with(httpBasic("operator-user", "operator-test-password")))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("운영기관 계정은 관리자 콘솔 대시보드에 접근할 수 없다")
	void operatorCannotAccessAdminDashboard() throws Exception {
		mockMvc.perform(get("/admin/dashboard/page")
				.with(httpBasic("operator-user", "operator-test-password")))
			.andExpect(status().isForbidden());
	}
}

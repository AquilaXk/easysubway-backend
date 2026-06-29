package com.easysubway.datapack.adapter.in.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
	"easysubway.admin.username=admin-user",
	"easysubway.admin.password=admin-test-password"
})
@AutoConfigureMockMvc
@DisplayName("데이터팩 관리자 보안 negative smoke")
class DatapackAdminSecurityNegativeTest {

	@Autowired
	private MockMvc mockMvc;

	@ParameterizedTest(name = "{0} requires admin.datapack.read")
	@ValueSource(strings = {
		"/admin/datapack/source-snapshots/page",
		"/admin/datapack/source-snapshots/snapshot-missing/page",
		"/admin/datapack/alias-quarantine/page",
		"/admin/datapack/facility-evidence/page",
		"/admin/datapack/route-gates/page",
		"/admin/datapack/manual-overrides/page",
		"/admin/datapack/candidates/page",
		"/admin/datapack/candidates/candidate-missing/page",
		"/admin/datapack/release-channels/page"
	})
	@DisplayName("datapack read 권한 없는 관리자는 데이터팩 운영 화면에 접근할 수 없다")
	void datapackAdminPagesRequireDatapackReadPermission(String path) throws Exception {
		mockMvc.perform(get(path)
				.with(user("viewer").authorities(new SimpleGrantedAuthority("admin.view"))))
			.andExpect(status().isForbidden());
	}
}

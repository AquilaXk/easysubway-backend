package com.easysubway.datapack.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
@DisplayName("관리자 데이터팩 파이프라인 개요 화면")
class DatapackPipelineAdminPageControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("datapack read 권한 관리자는 파이프라인 단계 그래프와 드릴다운 링크를 확인한다")
	void datapackReadAdminViewsPipelineStages() throws Exception {
		String html = mockMvc.perform(get("/admin/datapack/pipeline/page")
				.with(user("datapack-viewer").authorities(new SimpleGrantedAuthority("admin.datapack.read"))))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("데이터팩 파이프라인 개요")
			.contains("파이프라인 단계")
			.contains("원천 스냅샷")
			.contains("후보 게이트")
			.contains("별칭·격리")
			.contains("매니페스트 서명")
			.contains("채널 승격")
			.contains("/admin/datapack/source-snapshots/page")
			.contains("/admin/datapack/alias-quarantine/page")
			.contains("/admin/datapack/route-gates/page")
			.contains("/admin/datapack/release-channels/page")
			.contains("서명·증거")
			.contains("sha 원문 보기");
	}

	@Test
	@DisplayName("datapack read 권한이 없으면 파이프라인 화면에 접근할 수 없다")
	void pipelineRequiresDatapackReadAuthority() throws Exception {
		mockMvc.perform(get("/admin/datapack/pipeline/page")
				.with(user("no-datapack").authorities(new SimpleGrantedAuthority("admin.view"))))
			.andExpect(status().isForbidden());
	}
}

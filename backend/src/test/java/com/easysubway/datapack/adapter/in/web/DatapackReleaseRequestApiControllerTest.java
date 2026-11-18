package com.easysubway.datapack.adapter.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.datapack.application.service.DatapackReleaseRequestService;
import com.easysubway.datapack.application.service.DatapackReleaseRequestService.CreateReleaseRequestCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "easysubway.datapack.workflow-token=test-workflow-token")
@DisplayName("release request 조회 API")
class DatapackReleaseRequestApiControllerTest {

	private static final String SHA = "a".repeat(64);

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private DatapackReleaseRequestService service;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM datapack_release_request");
	}

	private String approvedApprovalId() {
		var created = service.create(new CreateReleaseRequestCommand(
			"cand-1", "scope-1", "staging", SHA, SHA, SHA, "alice"));
		service.approve(created.approvalId(), "bob");
		return created.approvalId();
	}

	@Test
	@DisplayName("유효 Bearer + 승인된 요청 → 스키마 1:1 응답")
	void servesApprovedWithToken() throws Exception {
		String id = approvedApprovalId();
		mockMvc.perform(get("/admin/api/datapack/release-requests/{id}", id)
				.header("Authorization", "Bearer test-workflow-token"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.schemaVersion").value(1))
			.andExpect(jsonPath("$.artifactKind").value("datapack-release-request"))
			.andExpect(jsonPath("$.candidateId").value("cand-1"))
			.andExpect(jsonPath("$.scopeId").value("scope-1"))
			.andExpect(jsonPath("$.approvedBy").value("bob"))
			.andExpect(jsonPath("$.targetChannel").value("staging"));
	}

	@Test
	@DisplayName("토큰 없으면 4xx")
	void rejectsWithoutToken() throws Exception {
		String id = approvedApprovalId();
		mockMvc.perform(get("/admin/api/datapack/release-requests/{id}", id))
			.andExpect(status().is4xxClientError());
	}

	@Test
	@DisplayName("미승인 요청은 404")
	void notFoundWhenUnapproved() throws Exception {
		var created = service.create(new CreateReleaseRequestCommand(
			"cand-1", "scope-1", "staging", SHA, SHA, SHA, "alice"));
		mockMvc.perform(get("/admin/api/datapack/release-requests/{id}", created.approvalId())
				.header("Authorization", "Bearer test-workflow-token"))
			.andExpect(status().isNotFound());
	}
}

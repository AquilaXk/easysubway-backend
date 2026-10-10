package com.easysubway.datapack.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.datapack.application.service.AutomationStatusFixtures;
import com.easysubway.datapack.application.port.in.AutomationStatusUseCase;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "easysubway.datapack.workflow-token=test-workflow-token")
@DisplayName("자동화 상태 snapshot 수신 API")
class AutomationStatusApiControllerTest {

	private static final String PATH = "/admin/api/datapack/automation-status";

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM datapack_automation_status");
	}

	private String freshJson() {
		return AutomationStatusFixtures.validJson(
			Instant.now().minusSeconds(30).toString(), "2026-10-31T00:00:00.000Z", "[]", "[]", "false");
	}

	@Test
	@DisplayName("서비스 토큰 없이는 snapshot을 받지 않는다")
	void rejectsRequestsWithoutTheWorkflowToken() throws Exception {
		mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(freshJson()))
			.andExpect(status().isForbidden());
		mockMvc.perform(post(PATH).header("Authorization", "Bearer wrong").contentType(MediaType.APPLICATION_JSON).content(freshJson()))
			.andExpect(status().isForbidden());
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM datapack_automation_status", Integer.class)).isZero();
	}

	@Test
	@DisplayName("유효한 snapshot은 저장하고 더 오래된 snapshot은 무시한다")
	void acceptsAValidSnapshotAndIgnoresAnOlderOne() throws Exception {
		mockMvc.perform(post(PATH).header("Authorization", "Bearer test-workflow-token")
				.contentType(MediaType.APPLICATION_JSON).content(freshJson()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ACCEPTED"));
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM datapack_automation_status", Integer.class)).isEqualTo(1);

		String older = AutomationStatusFixtures.validJson(
			Instant.now().minusSeconds(3600).toString(), "2026-10-31T00:00:00.000Z", "[]", "[]", "false");
		mockMvc.perform(post(PATH).header("Authorization", "Bearer test-workflow-token")
				.contentType(MediaType.APPLICATION_JSON).content(older))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("STALE"));
	}

	@Test
	@DisplayName("원천 근거 만료 목록(expiringSources)이 든 snapshot도 받고 저장하며, 모르는 키가 든 항목은 400으로 거부한다(backend#507)")
	void acceptsExpiringSourcesAndRejectsMalformedOnes() throws Exception {
		String item = AutomationStatusFixtures.expiringSourceJson("incheon-line1-train-timetable", "인천 1호선 열차 시간표",
			"2026-10-10T07:22:23.648Z", "capital-topology-refresh", "BLOCKED");
		String withSources = AutomationStatusFixtures.validJsonWithStagesAndSources(Instant.now().minusSeconds(30).toString(), "2026-10-31T00:00:00.000Z",
			AutomationStatusFixtures.healthyStagesJson(), "[]", "[]", "false", "[" + item + "]");
		mockMvc.perform(post(PATH).header("Authorization", "Bearer test-workflow-token")
				.contentType(MediaType.APPLICATION_JSON).content(withSources))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ACCEPTED"));
		assertThat(jdbcTemplate.queryForObject("SELECT payload_json FROM datapack_automation_status", String.class))
			.contains("\"expiringSources\"").contains("incheon-line1-train-timetable");

		jdbcTemplate.update("DELETE FROM datapack_automation_status");
		String unknownKey = withSources.replace("\"refreshState\"", "\"extra\": 1, \"refreshState\"");
		mockMvc.perform(post(PATH).header("Authorization", "Bearer test-workflow-token")
				.contentType(MediaType.APPLICATION_JSON).content(unknownKey))
			.andExpect(status().isBadRequest());
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM datapack_automation_status", Integer.class)).isZero();
	}

	@Test
	@DisplayName("형식이 어긋난 본문과 미래 시각, 과대 본문은 거부하고 저장하지 않는다")
	void rejectsMalformedFutureAndOversizedBodies() throws Exception {
		mockMvc.perform(post(PATH).header("Authorization", "Bearer test-workflow-token")
				.contentType(MediaType.APPLICATION_JSON).content("{\"hello\":1}"))
			.andExpect(status().isBadRequest());
		String future = AutomationStatusFixtures.validJson(
			Instant.now().plusSeconds(3600).toString(), "2026-10-31T00:00:00.000Z", "[]", "[]", "false");
		mockMvc.perform(post(PATH).header("Authorization", "Bearer test-workflow-token")
				.contentType(MediaType.APPLICATION_JSON).content(future))
			.andExpect(status().isBadRequest());
		String huge = "{\"padding\":\"" + "x".repeat(70_000) + "\"}";
		mockMvc.perform(post(PATH).header("Authorization", "Bearer test-workflow-token")
				.contentType(MediaType.APPLICATION_JSON).content(huge))
			.andExpect(status().isPayloadTooLarge());
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM datapack_automation_status", Integer.class)).isZero();
	}

	@Test
	@DisplayName("선언된 길이가 없거나 거짓이어도 상한을 넘는 본문은 상한 + 1바이트까지만 읽고 거부한다")
	void oversizedBodyIsNotReadBeyondTheCap() throws Exception {
		AtomicLong bytesRead = new AtomicLong();
		MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH) {
			@Override
			public ServletInputStream getInputStream() {
				return new ServletInputStream() {
					@Override
					public int read() {
						bytesRead.incrementAndGet();
						return 'x';
					}

					@Override
					public boolean isFinished() {
						return false;
					}

					@Override
					public boolean isReady() {
						return true;
					}

					@Override
					public void setReadListener(ReadListener listener) {
					}
				};
			}
		};
		AutomationStatusUseCase useCase = mock(AutomationStatusUseCase.class);
		AutomationStatusApiController controller = new AutomationStatusApiController(useCase);

		var response = controller.receive(request);

		assertThat(response.getStatusCode().value()).isEqualTo(413);
		assertThat(bytesRead.get()).isEqualTo(AutomationStatusApiController.MAX_BODY_BYTES + 1L);
		verifyNoInteractions(useCase);

		MockHttpServletRequest declared = new MockHttpServletRequest("POST", PATH);
		declared.setContent(new byte[AutomationStatusApiController.MAX_BODY_BYTES + 1]);
		assertThat(controller.receive(declared).getStatusCode().value()).isEqualTo(413);
		verifyNoInteractions(useCase);
	}
}

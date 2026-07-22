package com.easysubway.admin.errors.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.admin.errors.application.port.out.ErrorEventRepository;
import com.easysubway.admin.errors.domain.ErrorEvent;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
	"easysubway.admin.username=admin-user",
	"easysubway.admin.password=admin-test-password"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("오류 이벤트 관리자 조회 통합")
class ErrorEventIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private ErrorEventRepository errorEventRepository;

	@BeforeEach
	void cleanTable() {
		jdbcTemplate.update("DELETE FROM error_events");
	}

	@Test
	@DisplayName("GET /admin/api/errors는 필터·페이지와 권한을 적용한다")
	void adminApiFiltersAndRequiresPermission() throws Exception {
		Instant now = Instant.parse("2026-07-10T12:00:00Z");
		errorEventRepository.upsertOccurrence(new ErrorEvent(
			null, now, now, "INTERNAL_ERROR", "SYSTEM", 500, "GET",
			"/api/a", "java.lang.IllegalStateException",
			"a".repeat(64), "corr-a", 1L
		));
		errorEventRepository.upsertOccurrence(new ErrorEvent(
			null, now, now, "ITX_TIMETABLE_UNAVAILABLE", "DEPENDENCY", 503, "GET",
			"/api/b", "java.lang.IllegalStateException",
			"b".repeat(64), "corr-b", 3L
		));

		mockMvc.perform(get("/admin/api/errors")
				.param("code", "ITX_TIMETABLE_UNAVAILABLE")
				.param("category", "DEPENDENCY")
				.param("page", "0")
				.param("size", "10")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.items.length()").value(1))
			.andExpect(jsonPath("$.data.items[0].code").value("ITX_TIMETABLE_UNAVAILABLE"))
			.andExpect(jsonPath("$.data.items[0].occurrenceCount").value(3));

		mockMvc.perform(get("/admin/api/errors")
				.with(user("viewer").authorities(new SimpleGrantedAuthority("admin.view"))))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("콘솔 오류 이벤트 화면이 렌더된다")
	void consolePageRenders() throws Exception {
		String html = mockMvc.perform(get("/admin/errors/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("오류 이벤트")
			.contains("id=\"error-results\"")
			.contains("name=\"from\"")
			.contains("name=\"category\"");
	}
}

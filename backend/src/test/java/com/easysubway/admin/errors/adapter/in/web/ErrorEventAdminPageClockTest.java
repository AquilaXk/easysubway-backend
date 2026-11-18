package com.easysubway.admin.errors.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.admin.errors.application.port.out.ErrorEventRepository;
import com.easysubway.admin.errors.domain.ErrorEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 시계를 한국 새벽(UTC로는 전날)에 고정해 오류 이벤트 화면의 기본 기간(최근 7일)과 양 끝 경계가 한국 날짜인지 본다.
 */
@SpringBootTest(properties = {
	"easysubway.admin.username=admin-user",
	"easysubway.admin.password=admin-test-password"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("오류 이벤트 관리자 화면의 한국 날짜 기본 기간")
class ErrorEventAdminPageClockTest {

	@TestConfiguration
	static class KoreanEarlyMorningClock {

		/** 한국 2026-10-18 01:30. UTC 날짜로는 아직 2026-10-17이다. */
		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(Instant.parse("2026-10-17T16:30:00Z"), ZoneOffset.UTC);
		}
	}

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
	@DisplayName("기본 기간은 한국 오늘 기준 최근 7일이고 시작은 포함, 종료 다음 날 자정은 제외한다")
	void defaultWindowIsLastSevenKoreanDays() throws Exception {
		Map<String, Instant> lastOccurredAtByCode = new LinkedHashMap<>();
		lastOccurredAtByCode.put("BEFORE_WINDOW", Instant.parse("2026-10-10T14:59:59Z"));
		lastOccurredAtByCode.put("WINDOW_START", Instant.parse("2026-10-10T15:00:00Z"));
		lastOccurredAtByCode.put("KOREAN_EARLY_MORNING", Instant.parse("2026-10-17T16:10:00Z"));
		lastOccurredAtByCode.put("AFTER_WINDOW", Instant.parse("2026-10-18T15:00:00Z"));
		int hashSeed = 0;
		for (var entry : lastOccurredAtByCode.entrySet()) {
			errorEventRepository.upsertOccurrence(new ErrorEvent(
				null, entry.getValue(), entry.getValue(), entry.getKey(), "SYSTEM", 500, "GET",
				"/api/" + entry.getKey(), "java.lang.IllegalStateException",
				Integer.toString(hashSeed++).repeat(64), "corr-" + entry.getKey(), 1L
			));
		}

		String html = mockMvc.perform(get("/admin/errors/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();

		assertThat(html)
			.contains("name=\"from\" value=\"2026-10-11\"")
			.contains("name=\"to\" value=\"2026-10-18\"")
			.contains("WINDOW_START", "KOREAN_EARLY_MORNING")
			.doesNotContain("BEFORE_WINDOW", "AFTER_WINDOW");
	}
}

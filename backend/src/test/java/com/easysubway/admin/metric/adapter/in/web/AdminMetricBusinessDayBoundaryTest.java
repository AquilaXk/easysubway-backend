package com.easysubway.admin.metric.adapter.in.web;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.admin.metric.application.port.out.AdminMetricDailyRepository;
import com.easysubway.admin.metric.domain.AdminMetricDaily;
import com.easysubway.admin.metric.domain.AdminMetricKeys;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 차트·분석 시계열의 "오늘"은 서버 시계가 가리키는 순간의 한국 날짜다. 한국 자정(UTC 15:00)의 직전과
 * 직후에 시계를 두고, 오늘 칸·어제 칸·결측 칸이 한국 날짜에 맞는지 본다.
 */
@SpringBootTest(properties = {
	"easysubway.admin.username=admin-test",
	"easysubway.admin.password=admin-test-password"
})
@AutoConfigureMockMvc
@Import(AdminMetricTestClock.Configuration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("지표 시계열의 한국 날짜 경계")
class AdminMetricBusinessDayBoundaryTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private AdminMetricDailyRepository repository;

	@Autowired
	private AdminMetricTestClock clock;

	@ParameterizedTest(name = "{0}")
	@DisplayName("차트·분석 시계열과 기간 비교는 한국 날짜 기준 오늘로 끝난다")
	@CsvSource({
		"한국 자정 직전, 2026-10-22T14:59:59Z, 2026-10-22",
		"한국 자정 직후, 2026-10-22T15:00:00Z, 2026-10-23"
	})
	void seriesEndAtKoreanToday(String situation, Instant now, LocalDate koreanToday) throws Exception {
		clock.setInstant(now);
		repository.save(AdminMetricDaily.scalar(AdminMetricKeys.REPORTS_RECENT_24H, koreanToday, 12));
		repository.save(AdminMetricDaily.scalar(AdminMetricKeys.REPORTS_RECENT_24H, koreanToday.minusDays(1), 5));
		repository.save(AdminMetricDaily.scalar(AdminMetricKeys.ROUTE_SEARCHES, koreanToday, 40));
		repository.save(AdminMetricDaily.scalar(AdminMetricKeys.ROUTE_SEARCHES, koreanToday.minusDays(7), 10));

		mockMvc.perform(get("/admin/dashboard/metrics")
				.param("keys", AdminMetricKeys.REPORTS_RECENT_24H)
				.param("days", "7")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.labels[0]").value(koreanToday.minusDays(6).toString()))
			.andExpect(jsonPath("$.labels[6]").value(koreanToday.toString()))
			.andExpect(jsonPath("$.series[0].values[6]").value(12.0))
			.andExpect(jsonPath("$.series[0].values[5]").value(5.0))
			.andExpect(jsonPath("$.series[0].values[0]").value(nullValue()));

		mockMvc.perform(get("/admin/analytics/metrics")
				.param("keys", AdminMetricKeys.ROUTE_SEARCHES)
				.param("days", "7")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.labels[6]").value(koreanToday.toString()))
			.andExpect(jsonPath("$.series[0].values[6]").value(40.0))
			.andExpect(jsonPath("$.series[0].values[0]").value(nullValue()));

		mockMvc.perform(get("/admin/analytics/comparison")
				.param("keys", AdminMetricKeys.ROUTE_SEARCHES)
				.param("days", "7")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].current").value(40.0))
			.andExpect(jsonPath("$[0].previous").value(10.0));
	}
}

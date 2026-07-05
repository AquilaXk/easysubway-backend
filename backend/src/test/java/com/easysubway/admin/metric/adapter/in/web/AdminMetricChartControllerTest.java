package com.easysubway.admin.metric.adapter.in.web;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.admin.authorization.AdminPermission;
import com.easysubway.admin.metric.application.port.out.AdminMetricDailyRepository;
import com.easysubway.admin.metric.domain.AdminMetricDaily;
import com.easysubway.admin.metric.domain.AdminMetricKeys;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties = {
	"easysubway.admin.username=admin-test",
	"easysubway.admin.password=admin-test-password"
})
@AutoConfigureMockMvc
@DisplayName("차트 데이터 API")
class AdminMetricChartControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private AdminMetricDailyRepository repository;

	@BeforeEach
	void seed() {
		repository.save(AdminMetricDaily.scalar(AdminMetricKeys.REPORTS_RECENT_24H, LocalDate.now(), 12));
	}

	@Test
	@DisplayName("키·기간으로 시계열을 돌려주고 결측일은 null로 채운다")
	void returnsTimeSeries() throws Exception {
		mockMvc.perform(get("/admin/dashboard/metrics")
				.param("keys", AdminMetricKeys.REPORTS_RECENT_24H)
				.param("days", "7")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.days").value(7))
			.andExpect(jsonPath("$.labels.length()").value(7))
			.andExpect(jsonPath("$.labels[6]").value(LocalDate.now().toString()))
			.andExpect(jsonPath("$.series[0].key").value(AdminMetricKeys.REPORTS_RECENT_24H))
			.andExpect(jsonPath("$.series[0].label").value("제보 접수(24시간)"))
			.andExpect(jsonPath("$.series[0].values.length()").value(7))
			.andExpect(jsonPath("$.series[0].values[6]").value(12.0))
			.andExpect(jsonPath("$.series[0].values[0]").value(nullValue()));
	}

	@Test
	@DisplayName("허용 밖 기간·미등록 키는 기본값으로 정규화한다")
	void normalizesInvalidInput() throws Exception {
		mockMvc.perform(get("/admin/dashboard/metrics")
				.param("keys", "made.up.key")
				.param("days", "13")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.days").value(7))
			.andExpect(jsonPath("$.series[0].key").value(AdminMetricKeys.REPORTS_RECENT_24H));
	}

	@Test
	@DisplayName("ADMIN_VIEW 권한이면 차트 데이터를 볼 수 있다")
	void adminViewCanReadChart() throws Exception {
		RequestPostProcessor viewer = user("viewer")
			.authorities(new SimpleGrantedAuthority(AdminPermission.ADMIN_VIEW.authority()));

		mockMvc.perform(get("/admin/dashboard/metrics").param("days", "30").with(viewer))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.labels.length()").value(30));
	}
}

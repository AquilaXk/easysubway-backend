package com.easysubway.notice.adapter.in.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.notice.application.port.out.ServiceNoticeRepository;
import com.easysubway.notice.domain.ServiceNotice;
import com.easysubway.notice.domain.ServiceNoticeScope;
import com.easysubway.notice.domain.ServiceNoticeSeverity;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
	"easysubway.admin.username=admin-user",
	"easysubway.admin.password=admin-test-password"
})
@AutoConfigureMockMvc
@DisplayName("공개 운행 공지 API")
class ServiceNoticePublicControllerTest {

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private ServiceNoticeRepository repository;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM service_notice");
	}

	@Test
	@DisplayName("활성 공지만 반환하고 max-age=60 공개 캐시·ETag를 붙인다")
	void returnsActiveWithCacheHeaders() throws Exception {
		LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
		repository.save(new ServiceNotice("active", ServiceNoticeScope.LINE, "2",
			"2호선 지연", "우회 경로를 확인하세요.", ServiceNoticeSeverity.DISRUPTION,
			now.minusHours(1), now.plusHours(1), "operator-a"));
		repository.save(new ServiceNotice("expired", ServiceNoticeScope.ALL, null,
			"지난 공지", "본문", ServiceNoticeSeverity.INFO,
			now.minusHours(3), now.minusHours(1), "operator-a"));

		mockMvc.perform(get("/api/notices/active"))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", containsString("max-age=60")))
			.andExpect(header().string("Cache-Control", containsString("public")))
			.andExpect(header().exists("ETag"))
			.andExpect(jsonPath("$.data.length()").value(1))
			.andExpect(jsonPath("$.data[0].id").value("active"))
			.andExpect(jsonPath("$.data[0].title").value("2호선 지연"))
			.andExpect(jsonPath("$.data[0].publishedBy").doesNotExist());
	}

	@Test
	@DisplayName("같은 ETag로 If-None-Match 조건부 요청하면 304")
	void conditionalNotModified() throws Exception {
		LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
		repository.save(new ServiceNotice("active", ServiceNoticeScope.ALL, null,
			"전체 공지", "본문", ServiceNoticeSeverity.INFO,
			now.minusHours(1), null, "operator-a"));

		MvcResult first = mockMvc.perform(get("/api/notices/active"))
			.andExpect(status().isOk())
			.andReturn();
		String etag = first.getResponse().getHeader("ETag");

		mockMvc.perform(get("/api/notices/active").header("If-None-Match", etag))
			.andExpect(status().isNotModified());
	}
}

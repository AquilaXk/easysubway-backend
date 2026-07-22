package com.easysubway.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.admin.errors.adapter.out.persistence.JdbcErrorEventRepository;
import com.easysubway.admin.errors.application.service.ErrorEventAsyncWriter;
import com.easysubway.admin.errors.application.service.ErrorEventRecorder;
import com.easysubway.common.web.CommonExceptionHandler;
import com.easysubway.common.web.CorrelationIdFilter;
import com.easysubway.common.web.WebMessageResolver;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;

@DisplayName("오류 이벤트 집계 통합(standalone)")
class ErrorEventAggregationIntegrationTest {

	private MockMvc mockMvc;
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		DataSource dataSource = new EmbeddedDatabaseBuilder()
			.setType(EmbeddedDatabaseType.H2)
			.setName("error-agg-" + System.nanoTime())
			.build();
		ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
		populator.addScript(new ClassPathResource("db/migration/h2/V68__create_error_events.sql"));
		populator.execute(dataSource);
		jdbcTemplate = new JdbcTemplate(dataSource);

		var repository = new JdbcErrorEventRepository(dataSource);
		// standalone에서는 @Async 프록시 없이 동기 persist한다.
		var writer = new ErrorEventAsyncWriter(repository);
		var recorder = new ErrorEventRecorder(
			writer,
			Clock.fixed(Instant.parse("2026-07-10T00:00:00Z"), ZoneOffset.UTC)
		);
		mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
			.setControllerAdvice(new CommonExceptionHandler(WebMessageResolver.defaultMessages(), recorder))
			.addFilters(new CorrelationIdFilter())
			.build();
	}

	@Test
	@DisplayName("강제 500은 1행을 만들고 재발 시 occurrence_count만 증가한다")
	void forced500AggregatesOccurrences() throws Exception {
		mockMvc.perform(get("/api/test/error-events/boom/station-1")
				.requestAttr(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/test/error-events/boom/{stationId}"))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

		assertThat(countRows()).isEqualTo(1L);
		assertThat(jdbcTemplate.queryForObject("SELECT occurrence_count FROM error_events", Long.class)).isEqualTo(1L);
		assertThat(jdbcTemplate.queryForObject("SELECT path_pattern FROM error_events", String.class))
			.isEqualTo("/api/test/error-events/boom/{stationId}")
			.doesNotContain("station-1");

		mockMvc.perform(get("/api/test/error-events/boom/station-2")
				.requestAttr(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/test/error-events/boom/{stationId}"))
			.andExpect(status().isInternalServerError());

		assertThat(countRows()).isEqualTo(1L);
		assertThat(jdbcTemplate.queryForObject("SELECT occurrence_count FROM error_events", Long.class)).isEqualTo(2L);
	}

	@Test
	@DisplayName("4xx는 error_events에 저장되지 않는다")
	void fourXxIsNotStored() throws Exception {
		mockMvc.perform(get("/api/test/error-events/not-found"))
			.andExpect(status().isNotFound());
		assertThat(countRows()).isZero();
	}

	private long countRows() {
		Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM error_events", Long.class);
		return count == null ? 0L : count;
	}

	@RestController
	static class ProbeController {
		@GetMapping("/api/test/error-events/boom/{stationId}")
		String boom(@PathVariable String stationId) {
			throw new IllegalStateException("forced-500-" + stationId);
		}

		@GetMapping("/api/test/error-events/not-found")
		String notFound() {
			throw new com.easysubway.common.error.ResourceNotFoundException("없음");
		}
	}
}

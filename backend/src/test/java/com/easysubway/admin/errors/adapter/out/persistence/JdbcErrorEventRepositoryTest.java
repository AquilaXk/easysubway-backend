package com.easysubway.admin.errors.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.admin.errors.application.ErrorEventQuery;
import com.easysubway.admin.errors.domain.ErrorEvent;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

@DisplayName("JDBC 오류 이벤트 저장소")
class JdbcErrorEventRepositoryTest {

	@Test
	@DisplayName("같은 stack_hash·code·path_pattern은 1행으로 집계되고 occurrence_count만 증가한다")
	void upsertAggregatesByUniqueKey() {
		var repository = new JdbcErrorEventRepository(errorEventsDataSource());
		Instant first = Instant.parse("2026-07-01T00:00:00Z");
		Instant second = Instant.parse("2026-07-01T01:00:00Z");

		repository.upsertOccurrence(event("hash-a", "INTERNAL_ERROR", "/api/demo/{id}", first, "corr-1"));
		repository.upsertOccurrence(event("hash-a", "INTERNAL_ERROR", "/api/demo/{id}", second, "corr-2"));

		var page = repository.search(ErrorEventQuery.of(null, null, null, null, 0, 20));
		assertThat(page.items()).hasSize(1);
		assertThat(page.items().getFirst().occurrenceCount()).isEqualTo(2L);
		assertThat(page.items().getFirst().sampleCorrelationId()).isEqualTo("corr-2");
		assertThat(page.items().getFirst().firstOccurredAt()).isEqualTo(first);
		assertThat(page.items().getFirst().lastOccurredAt()).isEqualTo(second);
	}

	@Test
	@DisplayName("기간·code·category 필터와 페이지네이션이 동작한다")
	void searchFiltersAndPages() {
		var repository = new JdbcErrorEventRepository(errorEventsDataSource());
		Instant day1 = Instant.parse("2026-07-01T12:00:00Z");
		Instant day2 = Instant.parse("2026-07-02T12:00:00Z");
		repository.upsertOccurrence(event("h1", "INTERNAL_ERROR", "/a", day1, "c1"));
		repository.upsertOccurrence(event("h2", "ITX_TIMETABLE_UNAVAILABLE", "/b", day2, "c2", "DEPENDENCY", 503));

		var filtered = repository.search(ErrorEventQuery.of(
			Instant.parse("2026-07-02T00:00:00Z"),
			Instant.parse("2026-07-03T00:00:00Z"),
			"ITX_TIMETABLE_UNAVAILABLE",
			"DEPENDENCY",
			0,
			20
		));
		assertThat(filtered.items()).extracting(ErrorEvent::code)
			.containsExactly("ITX_TIMETABLE_UNAVAILABLE");
		assertThat(repository.count(ErrorEventQuery.of(null, null, null, null, 0, 20))).isEqualTo(2L);
	}

	@Test
	@DisplayName("90일 초과 last_occurred_at 행만 삭제한다")
	void deleteOlderThanRetention() {
		var repository = new JdbcErrorEventRepository(errorEventsDataSource());
		Instant old = Instant.parse("2026-01-01T00:00:00Z");
		Instant recent = Instant.parse("2026-07-01T00:00:00Z");
		repository.upsertOccurrence(event("old", "INTERNAL_ERROR", "/old", old, "old"));
		repository.upsertOccurrence(event("new", "INTERNAL_ERROR", "/new", recent, "new"));

		int deleted = repository.deleteOlderThan(recent.minus(90, ChronoUnit.DAYS));
		assertThat(deleted).isEqualTo(1);
		assertThat(repository.search(ErrorEventQuery.of(null, null, null, null, 0, 20)).items())
			.extracting(ErrorEvent::pathPattern)
			.containsExactly("/new");
	}

	@Test
	@DisplayName("error_events 테이블 컬럼 목록을 계약으로 고정한다")
	void schemaColumnsArePinned() {
		var columns = new JdbcTemplate(errorEventsDataSource()).queryForList("""
			SELECT LOWER(column_name)
			FROM information_schema.columns
			WHERE LOWER(table_name) = 'error_events'
			ORDER BY ordinal_position
			""", String.class);

		assertThat(columns).containsExactly(
			"id",
			"first_occurred_at",
			"last_occurred_at",
			"code",
			"category",
			"http_status",
			"method",
			"path_pattern",
			"exception_class",
			"stack_hash",
			"sample_correlation_id",
			"occurrence_count"
		);
		assertThat(columns).doesNotContain("message", "body", "query", "header", "user_id", "login_id");
	}

	private static ErrorEvent event(
		String stackHash,
		String code,
		String pathPattern,
		Instant at,
		String correlationId
	) {
		return event(stackHash, code, pathPattern, at, correlationId, "SYSTEM", 500);
	}

	private static ErrorEvent event(
		String stackHash,
		String code,
		String pathPattern,
		Instant at,
		String correlationId,
		String category,
		int httpStatus
	) {
		return new ErrorEvent(
			null,
			at,
			at,
			code,
			category,
			httpStatus,
			"GET",
			pathPattern,
			"java.lang.IllegalStateException",
			padHash(stackHash),
			correlationId,
			1L
		);
	}

	private static String padHash(String value) {
		String normalized = value == null ? "" : value;
		if (normalized.length() >= 64) {
			return normalized.substring(0, 64);
		}
		return normalized + "0".repeat(64 - normalized.length());
	}

	private static DataSource errorEventsDataSource() {
		DataSource dataSource = new EmbeddedDatabaseBuilder()
			.setType(EmbeddedDatabaseType.H2)
			.setName("error-events-" + System.nanoTime())
			.build();
		ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
		populator.addScript(new ClassPathResource("db/migration/h2/V68__create_error_events.sql"));
		populator.execute(dataSource);
		return dataSource;
	}
}

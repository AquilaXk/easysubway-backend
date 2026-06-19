package com.easysubway.field.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.field.domain.FieldVerificationChangeHistory;
import com.easysubway.field.domain.FieldVerificationStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@DisplayName("JDBC 현장 검증 변경 이력 저장소")
class JdbcFieldVerificationChangeHistoryRepositoryTest {

	private JdbcFieldVerificationChangeHistoryRepository repository;

	@BeforeEach
	void setUp() {
		var dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:field-verification-history;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		var jdbcTemplate = new JdbcTemplate(dataSource);
		jdbcTemplate.execute("DROP TABLE IF EXISTS field_verification_change_history");
		jdbcTemplate.execute("""
			CREATE TABLE field_verification_change_history (
				history_id VARCHAR(120) PRIMARY KEY,
				session_id VARCHAR(120) NOT NULL,
				station_id VARCHAR(120) NOT NULL,
				item_id VARCHAR(120) NOT NULL,
				previous_status VARCHAR(40) NOT NULL,
				new_status VARCHAR(40) NOT NULL,
				previous_note VARCHAR(1000),
				new_note VARCHAR(1000),
				changed_by VARCHAR(120) NOT NULL,
				changed_at TIMESTAMP NOT NULL
			)
			""");
		repository = new JdbcFieldVerificationChangeHistoryRepository(jdbcTemplate);
	}

	@Test
	@DisplayName("현장 검증 변경 이력을 저장하고 역 기준 최신순으로 조회한다")
	void saveHistoryAndListByStationIdNewestFirst() {
		repository.save(history(
			"history-old",
			"station-sadang",
			"field-verification-sadang-elevator",
			FieldVerificationStatus.PLANNED,
			FieldVerificationStatus.NEEDS_RECHECK,
			null,
			"엘리베이터 운행 중지 안내문 확인 필요",
			LocalDateTime.of(2026, 6, 19, 10, 0)
		));
		repository.save(history(
			"history-new",
			"station-sadang",
			"field-verification-sadang-restroom",
			FieldVerificationStatus.PLANNED,
			FieldVerificationStatus.VERIFIED,
			null,
			"화장실 위치 확인 완료",
			LocalDateTime.of(2026, 6, 19, 11, 0)
		));
		repository.save(history(
			"history-other-station",
			"station-sangnoksu",
			"field-verification-sangnoksu-exit",
			FieldVerificationStatus.PLANNED,
			FieldVerificationStatus.VERIFIED,
			null,
			"출구 연결 확인 완료",
			LocalDateTime.of(2026, 6, 19, 12, 0)
		));

		var histories = repository.listByStationId("station-sadang");

		assertThat(histories)
			.extracting(FieldVerificationChangeHistory::id)
			.containsExactly("history-new", "history-old");
		assertThat(histories.get(0)).satisfies(history -> {
			assertThat(history.sessionId()).isEqualTo("field-verification-sadang-2026-06");
			assertThat(history.itemId()).isEqualTo("field-verification-sadang-restroom");
			assertThat(history.previousStatus()).isEqualTo(FieldVerificationStatus.PLANNED);
			assertThat(history.newStatus()).isEqualTo(FieldVerificationStatus.VERIFIED);
			assertThat(history.previousNote()).isNull();
			assertThat(history.newNote()).isEqualTo("화장실 위치 확인 완료");
			assertThat(history.changedBy()).isEqualTo("admin-user");
			assertThat(history.changedAt()).isEqualTo(LocalDateTime.of(2026, 6, 19, 11, 0));
		});
	}

	@Test
	@DisplayName("변경 이력이 없는 역은 빈 목록을 반환한다")
	void listByStationIdReturnsEmptyWhenHistoryDoesNotExist() {
		assertThat(repository.listByStationId("missing-station")).isEmpty();
	}

	private FieldVerificationChangeHistory history(
		String historyId,
		String stationId,
		String itemId,
		FieldVerificationStatus previousStatus,
		FieldVerificationStatus newStatus,
		String previousNote,
		String newNote,
		LocalDateTime changedAt
	) {
		return new FieldVerificationChangeHistory(
			historyId,
			"field-verification-sadang-2026-06",
			stationId,
			itemId,
			previousStatus,
			newStatus,
			previousNote,
			newNote,
			"admin-user",
			changedAt
		);
	}
}

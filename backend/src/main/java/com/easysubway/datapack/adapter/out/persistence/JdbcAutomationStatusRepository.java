package com.easysubway.datapack.adapter.out.persistence;

import com.easysubway.datapack.application.port.out.AutomationStatusRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 자동화 상태 snapshot의 마지막 1건(id = 1 고정). 더 새로운 generated_at만 대체한다. */
@Repository
public class JdbcAutomationStatusRepository implements AutomationStatusRepository {

	private final JdbcTemplate jdbcTemplate;

	@Autowired
	JdbcAutomationStatusRepository(DataSource dataSource) {
		this(new JdbcTemplate(dataSource));
	}

	JdbcAutomationStatusRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public SaveResult save(String payloadJson, Instant generatedAt, Instant receivedAt) {
		OffsetDateTime generated = utc(generatedAt);
		OffsetDateTime received = utc(receivedAt);
		int updated = jdbcTemplate.update(
			"UPDATE datapack_automation_status SET payload_json = ?, generated_at = ?, received_at = ? "
				+ "WHERE id = 1 AND generated_at < ?",
			payloadJson, generated, received, generated);
		if (updated == 1) {
			return SaveResult.ACCEPTED;
		}
		if (!jdbcTemplate.queryForList("SELECT id FROM datapack_automation_status WHERE id = 1").isEmpty()) {
			return SaveResult.STALE;
		}
		try {
			jdbcTemplate.update(
				"INSERT INTO datapack_automation_status (id, payload_json, generated_at, received_at) VALUES (1, ?, ?, ?)",
				payloadJson, generated, received);
			return SaveResult.ACCEPTED;
		} catch (DuplicateKeyException concurrentInsert) {
			// 동시에 첫 snapshot이 들어온 경우: 먼저 들어온 행이 있으므로 다시 새로움 비교를 한다.
			return save(payloadJson, generatedAt, receivedAt);
		}
	}

	@Override
	public Optional<StoredPayload> findLatest() {
		List<StoredPayload> rows = jdbcTemplate.query(
			"SELECT payload_json, generated_at, received_at FROM datapack_automation_status WHERE id = 1",
			(rs, rowNumber) -> new StoredPayload(
				rs.getString("payload_json"),
				rs.getObject("generated_at", OffsetDateTime.class).toInstant(),
				rs.getObject("received_at", OffsetDateTime.class).toInstant()));
		return rows.stream().findFirst();
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}
}

package com.easysubway.admin.errors.adapter.out.persistence;

import com.easysubway.admin.errors.application.ErrorEventQuery;
import com.easysubway.admin.errors.application.port.out.ErrorEventRepository;
import com.easysubway.admin.errors.domain.ErrorEvent;
import com.easysubway.common.domain.PageResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcErrorEventRepository implements ErrorEventRepository {

	private final JdbcTemplate jdbcTemplate;

	@Autowired
	public JdbcErrorEventRepository(DataSource dataSource) {
		this(new JdbcTemplate(dataSource));
	}

	JdbcErrorEventRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public void upsertOccurrence(ErrorEvent event) {
		int updated = jdbcTemplate.update(
			"""
				UPDATE error_events
				SET first_occurred_at = LEAST(first_occurred_at, ?),
					last_occurred_at = GREATEST(last_occurred_at, ?),
					sample_correlation_id = ?,
					occurrence_count = occurrence_count + 1
				WHERE stack_hash = ? AND code = ? AND path_pattern = ?
				""",
			timestamp(event.firstOccurredAt()),
			timestamp(event.lastOccurredAt()),
			event.sampleCorrelationId(),
			event.stackHash(),
			event.code(),
			event.pathPattern()
		);
		if (updated > 0) {
			return;
		}
		try {
			jdbcTemplate.update(
				"""
					INSERT INTO error_events (
						first_occurred_at, last_occurred_at, code, category, http_status,
						method, path_pattern, exception_class, stack_hash,
						sample_correlation_id, occurrence_count
					) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
					""",
				timestamp(event.firstOccurredAt()),
				timestamp(event.lastOccurredAt()),
				event.code(),
				event.category(),
				event.httpStatus(),
				event.method(),
				event.pathPattern(),
				event.exceptionClass(),
				event.stackHash(),
				event.sampleCorrelationId(),
				event.occurrenceCount()
			);
		}
		catch (DuplicateKeyException race) {
			jdbcTemplate.update(
				"""
					UPDATE error_events
					SET first_occurred_at = LEAST(first_occurred_at, ?),
						last_occurred_at = GREATEST(last_occurred_at, ?),
						sample_correlation_id = ?,
						occurrence_count = occurrence_count + 1
					WHERE stack_hash = ? AND code = ? AND path_pattern = ?
					""",
				timestamp(event.firstOccurredAt()),
				timestamp(event.lastOccurredAt()),
				event.sampleCorrelationId(),
				event.stackHash(),
				event.code(),
				event.pathPattern()
			);
		}
	}

	@Override
	public PageResult<ErrorEvent> search(ErrorEventQuery query) {
		FilterSql filter = filterSql(query);
		List<Object> args = new ArrayList<>(filter.args());
		args.add(query.pageRequest().limitForHasNext());
		args.add(query.pageRequest().offset());
		List<ErrorEvent> rows = jdbcTemplate.query(
			"""
				SELECT id, first_occurred_at, last_occurred_at, code, category, http_status,
					method, path_pattern, exception_class, stack_hash,
					sample_correlation_id, occurrence_count
				FROM error_events
				WHERE
				"""
				+ filter.sql()
				+ """
				 ORDER BY last_occurred_at DESC, id DESC
				LIMIT ? OFFSET ?
				""",
			this::mapEvent,
			args.toArray()
		);
		boolean hasNext = rows.size() > query.pageRequest().size();
		List<ErrorEvent> pageItems = hasNext ? rows.subList(0, query.pageRequest().size()) : rows;
		return new PageResult<>(pageItems, query.pageRequest().page(), query.pageRequest().size(), hasNext);
	}

	@Override
	public long count(ErrorEventQuery query) {
		FilterSql filter = filterSql(query);
		Long count = jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM error_events WHERE " + filter.sql(),
			Long.class,
			filter.args().toArray()
		);
		return count == null ? 0L : count;
	}

	@Override
	public int deleteOlderThan(Instant cutoffExclusive) {
		return jdbcTemplate.update(
			"DELETE FROM error_events WHERE last_occurred_at < ?",
			timestamp(cutoffExclusive)
		);
	}

	private FilterSql filterSql(ErrorEventQuery query) {
		List<String> clauses = new ArrayList<>();
		List<Object> args = new ArrayList<>();
		if (query.fromInclusive() != null) {
			clauses.add("last_occurred_at >= ?");
			args.add(timestamp(query.fromInclusive()));
		}
		if (query.toExclusive() != null) {
			clauses.add("last_occurred_at < ?");
			args.add(timestamp(query.toExclusive()));
		}
		if (query.code() != null) {
			clauses.add("code = ?");
			args.add(query.code());
		}
		if (query.category() != null) {
			clauses.add("category = ?");
			args.add(query.category());
		}
		if (clauses.isEmpty()) {
			return new FilterSql("1=1", List.of());
		}
		return new FilterSql(String.join(" AND ", clauses), List.copyOf(args));
	}

	private ErrorEvent mapEvent(ResultSet rs, int rowNum) throws SQLException {
		return new ErrorEvent(
			rs.getLong("id"),
			rs.getTimestamp("first_occurred_at").toInstant(),
			rs.getTimestamp("last_occurred_at").toInstant(),
			rs.getString("code"),
			rs.getString("category"),
			rs.getInt("http_status"),
			rs.getString("method"),
			rs.getString("path_pattern"),
			rs.getString("exception_class"),
			rs.getString("stack_hash"),
			rs.getString("sample_correlation_id"),
			rs.getLong("occurrence_count")
		);
	}

	private static Timestamp timestamp(Instant instant) {
		return Timestamp.from(instant);
	}

	private record FilterSql(String sql, List<Object> args) {
	}
}

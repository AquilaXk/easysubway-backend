package com.easysubway.notice.adapter.out.persistence;

import com.easysubway.notice.application.port.out.ServiceNoticeRepository;
import com.easysubway.notice.domain.ServiceNotice;
import com.easysubway.notice.domain.ServiceNoticeScope;
import com.easysubway.notice.domain.ServiceNoticeSeverity;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcServiceNoticeRepository implements ServiceNoticeRepository {

	private static final RowMapper<ServiceNotice> ROW_MAPPER = (rs, n) -> new ServiceNotice(
		rs.getString("id"),
		ServiceNoticeScope.valueOf(rs.getString("scope")),
		rs.getString("scope_value"),
		rs.getString("title"),
		rs.getString("body"),
		ServiceNoticeSeverity.valueOf(rs.getString("severity")),
		toLdt(rs.getTimestamp("published_at")),
		toLdt(rs.getTimestamp("expires_at")),
		rs.getString("published_by"),
		toLdt(rs.getTimestamp("unpublished_at")),
		rs.getString("unpublished_by"));

	private final JdbcTemplate jdbcTemplate;

	public JdbcServiceNoticeRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public void save(ServiceNotice notice) {
		int updated = jdbcTemplate.update(
			"UPDATE service_notice SET scope=?, scope_value=?, title=?, body=?, severity=?,"
				+ " published_at=?, expires_at=?, published_by=?, unpublished_at=?, unpublished_by=?"
				+ " WHERE id=?",
			notice.scope().name(), notice.scopeValue(), notice.title(), notice.body(),
			notice.severity().name(), Timestamp.valueOf(notice.publishedAt()),
			toTimestamp(notice.expiresAt()), notice.publishedBy(),
			toTimestamp(notice.unpublishedAt()), notice.unpublishedBy(), notice.id());
		if (updated == 0) {
			jdbcTemplate.update(
				"INSERT INTO service_notice (id, scope, scope_value, title, body, severity,"
					+ " published_at, expires_at, published_by, unpublished_at, unpublished_by)"
					+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
				notice.id(), notice.scope().name(), notice.scopeValue(), notice.title(),
				notice.body(), notice.severity().name(), Timestamp.valueOf(notice.publishedAt()),
				toTimestamp(notice.expiresAt()), notice.publishedBy(),
				toTimestamp(notice.unpublishedAt()), notice.unpublishedBy());
		}
	}

	@Override
	public Optional<ServiceNotice> findById(String id) {
		try {
			return Optional.ofNullable(jdbcTemplate.queryForObject(
				"SELECT * FROM service_notice WHERE id=?", ROW_MAPPER, id));
		} catch (EmptyResultDataAccessException exception) {
			return Optional.empty();
		}
	}

	@Override
	public List<ServiceNotice> findActiveAt(LocalDateTime now) {
		Timestamp at = Timestamp.valueOf(now);
		return jdbcTemplate.query(
			"SELECT * FROM service_notice WHERE unpublished_at IS NULL AND published_at <= ?"
				+ " AND (expires_at IS NULL OR expires_at > ?) ORDER BY published_at DESC",
			ROW_MAPPER, at, at);
	}

	@Override
	public List<ServiceNotice> findRecent(int limit) {
		return jdbcTemplate.query(
			"SELECT * FROM service_notice ORDER BY published_at DESC LIMIT ?",
			ROW_MAPPER, limit);
	}

	@Override
	public boolean unpublish(String id, LocalDateTime unpublishedAt, String unpublishedBy) {
		int updated = jdbcTemplate.update(
			"UPDATE service_notice SET unpublished_at=?, unpublished_by=?"
				+ " WHERE id=? AND unpublished_at IS NULL",
			Timestamp.valueOf(unpublishedAt), unpublishedBy, id);
		return updated == 1;
	}

	private static Timestamp toTimestamp(LocalDateTime value) {
		return value == null ? null : Timestamp.valueOf(value);
	}

	private static LocalDateTime toLdt(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toLocalDateTime();
	}
}

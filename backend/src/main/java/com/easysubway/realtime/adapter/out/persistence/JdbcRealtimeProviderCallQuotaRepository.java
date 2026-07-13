package com.easysubway.realtime.adapter.out.persistence;

import com.easysubway.realtime.application.port.out.RealtimeProviderCallQuotaPort;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile("prod | staging | release | prod-like")
public class JdbcRealtimeProviderCallQuotaRepository implements RealtimeProviderCallQuotaPort {

	private final JdbcTemplate jdbcTemplate;

	@Autowired
	public JdbcRealtimeProviderCallQuotaRepository(DataSource dataSource) {
		this(new JdbcTemplate(dataSource));
	}

	JdbcRealtimeProviderCallQuotaRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	@Transactional(timeout = 2)
	public boolean tryAcquire(
		String providerId,
		Instant now,
		ZoneId providerZone,
		int limitPerMinute,
		int limitPerDay
	) {
		Objects.requireNonNull(providerId, "providerId must not be null");
		Objects.requireNonNull(now, "now must not be null");
		Objects.requireNonNull(providerZone, "providerZone must not be null");
		QuotaState state = jdbcTemplate.queryForObject(
			"""
				SELECT minute_window, minute_calls, day_window, daily_calls, CURRENT_TIMESTAMP AS database_now
				FROM realtime_provider_call_quota_state
				WHERE provider_id = ?
				FOR UPDATE
				""",
			(rs, rowNum) -> new QuotaState(
				rs.getLong("minute_window"),
				rs.getInt("minute_calls"),
				rs.getLong("day_window"),
				rs.getInt("daily_calls"),
				rs.getTimestamp("database_now").toInstant()
			),
			providerId
		);
		long minuteWindow = state.databaseNow().getEpochSecond() / 60;
		long dayWindow = state.databaseNow().atZone(providerZone).toLocalDate().toEpochDay();
		int minuteCalls = state.minuteWindow() == minuteWindow ? state.minuteCalls() : 0;
		int dailyCalls = state.dayWindow() == dayWindow ? state.dailyCalls() : 0;
		if (minuteCalls >= limitPerMinute || dailyCalls >= limitPerDay) {
			return false;
		}
		jdbcTemplate.update(
			"""
				UPDATE realtime_provider_call_quota_state
				SET minute_window = ?, minute_calls = ?, day_window = ?, daily_calls = ?, updated_at = CURRENT_TIMESTAMP
				WHERE provider_id = ?
				""",
			minuteWindow,
			minuteCalls + 1,
			dayWindow,
			dailyCalls + 1,
			providerId
		);
		return true;
	}

	private record QuotaState(
		long minuteWindow,
		int minuteCalls,
		long dayWindow,
		int dailyCalls,
		Instant databaseNow
	) {
	}
}

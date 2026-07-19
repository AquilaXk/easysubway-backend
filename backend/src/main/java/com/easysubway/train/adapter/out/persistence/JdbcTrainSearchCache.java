package com.easysubway.train.adapter.out.persistence;

import com.easysubway.train.application.TrainSearchCache;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcTrainSearchCache implements TrainSearchCache {
	private static final int QUERY_TIMEOUT_SECONDS = 2;

	private final JdbcTemplate jdbcTemplate;

	@Autowired
	public JdbcTrainSearchCache(DataSource dataSource) {
		this(new JdbcTemplate(dataSource));
	}

	JdbcTrainSearchCache(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
		this.jdbcTemplate.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
	}

	@Override
	public Optional<CachedCatalog> freshCatalog(String kind, Instant now) {
		Objects.requireNonNull(kind, "kind must not be null");
		Objects.requireNonNull(now, "now must not be null");
		return jdbcTemplate.query(
			"""
				SELECT catalog_kind, payload_json, payload_sha256, observed_at, expires_at
				FROM train_catalog_cache
				WHERE catalog_kind = ? AND expires_at > CURRENT_TIMESTAMP
				""",
			(rs, rowNum) -> new CachedCatalog(
				rs.getString("catalog_kind"),
				rs.getString("payload_json"),
				rs.getString("payload_sha256"),
				rs.getTimestamp("observed_at").toInstant(),
				rs.getTimestamp("expires_at").toInstant()
			),
			kind
		).stream().findFirst();
	}

	@Override
	@Transactional(timeout = 2)
	public void replaceCatalog(List<CachedCatalog> catalogs) {
		Objects.requireNonNull(catalogs, "catalogs must not be null");
		jdbcTemplate.update("DELETE FROM train_catalog_cache");
		for (CachedCatalog catalog : catalogs) {
			jdbcTemplate.update(
				"""
					INSERT INTO train_catalog_cache
					(catalog_kind, payload_json, payload_sha256, observed_at, expires_at)
					VALUES (?, ?, ?, ?, ?)
					""",
				catalog.kind(),
				catalog.payloadJson(),
				catalog.payloadSha256(),
				Timestamp.from(catalog.observedAt()),
				Timestamp.from(catalog.expiresAt())
			);
		}
	}

	@Override
	@Transactional(timeout = 2)
	public Optional<CachedLeg> freshLeg(String key, Instant now) {
		Objects.requireNonNull(key, "key must not be null");
		Objects.requireNonNull(now, "now must not be null");
		List<CachedLeg> rows = jdbcTemplate.query(
			"""
				SELECT cache_key, normalized_query_json, payload_json, payload_sha256, observed_at, expires_at
				FROM train_search_cache
				WHERE cache_key = ? AND payload_json IS NOT NULL AND expires_at > CURRENT_TIMESTAMP
				""",
			(rs, rowNum) -> new CachedLeg(
				rs.getString("cache_key"),
				rs.getString("normalized_query_json"),
				rs.getString("payload_json"),
				rs.getString("payload_sha256"),
				rs.getTimestamp("observed_at").toInstant(),
				rs.getTimestamp("expires_at").toInstant()
			),
			key
		);
		return rows.stream().findFirst();
	}

	@Override
	@Transactional(timeout = 2)
	public boolean tryAcquireLease(String key, String owner, Instant now, Duration ttl) {
		Objects.requireNonNull(key, "key must not be null");
		Objects.requireNonNull(owner, "owner must not be null");
		Objects.requireNonNull(now, "now must not be null");
		Objects.requireNonNull(ttl, "ttl must not be null");
		if (ttl.isZero() || ttl.isNegative()) {
			throw new IllegalArgumentException("ttl must be positive");
		}
		Instant databaseNow = databaseNow();
		jdbcTemplate.update(
			"""
				INSERT INTO train_search_cache (cache_key, last_access_at)
				VALUES (?, ?)
				ON CONFLICT DO NOTHING
				""",
			key,
			Timestamp.from(databaseNow)
		);
		return jdbcTemplate.update(
			"""
				UPDATE train_search_cache
				SET lease_owner = ?, lease_expires_at = ?
				WHERE cache_key = ?
					AND (lease_owner IS NULL OR lease_expires_at <= ?)
					AND (payload_json IS NULL OR expires_at <= ?)
				""",
			owner,
			Timestamp.from(databaseNow.plus(ttl)),
			key,
			Timestamp.from(databaseNow),
			Timestamp.from(databaseNow)
		) == 1;
	}

	@Override
	@Transactional(timeout = 2)
	public void releaseLease(String key, String owner) {
		jdbcTemplate.update(
			"""
				UPDATE train_search_cache SET lease_owner = NULL, lease_expires_at = NULL
				WHERE cache_key = ? AND lease_owner = ?
				""",
			key,
			owner
		);
	}

	@Override
	@Transactional(timeout = 2)
	public boolean storeLegAndRelease(String key, String owner, CachedLeg leg) {
		Objects.requireNonNull(leg, "leg must not be null");
		if (!Objects.equals(key, leg.key())) {
			throw new IllegalArgumentException("leg key must match lease key");
		}
		return jdbcTemplate.update(
			"""
				UPDATE train_search_cache
				SET normalized_query_json = ?, payload_json = ?, payload_sha256 = ?, observed_at = ?, expires_at = ?,
					last_access_at = CURRENT_TIMESTAMP, lease_owner = NULL, lease_expires_at = NULL
				WHERE cache_key = ? AND lease_owner = ?
				""",
			leg.normalizedQueryJson(),
			leg.payloadJson(),
			leg.payloadSha256(),
			Timestamp.from(leg.observedAt()),
			Timestamp.from(leg.expiresAt()),
			key,
			owner
		) == 1;
	}

	@Override
	@Transactional(timeout = 2)
	public boolean tryAcquireProviderCall(String providerId, ZoneId providerZone, int minuteLimit, int dayLimit) {
		Objects.requireNonNull(providerId, "providerId must not be null");
		Objects.requireNonNull(providerZone, "providerZone must not be null");
		if (minuteLimit <= 0 || dayLimit <= 0) {
			throw new IllegalArgumentException("quota limits must be positive");
		}
		Instant databaseNow = databaseNow();
		long minuteWindow = databaseNow.getEpochSecond() / 60;
		long dayWindow = databaseNow.atZone(providerZone).toLocalDate().toEpochDay();
		jdbcTemplate.update(
			"""
				INSERT INTO train_provider_call_quota_state
				(provider_id, minute_window, minute_calls, day_window, daily_calls, updated_at)
				VALUES (?, ?, 0, ?, 0, ?)
				ON CONFLICT DO NOTHING
				""",
			providerId,
			minuteWindow,
			dayWindow,
			Timestamp.from(databaseNow)
		);
		QuotaState state = jdbcTemplate.queryForObject(
			"""
				SELECT minute_window, minute_calls, day_window, daily_calls
				FROM train_provider_call_quota_state WHERE provider_id = ? FOR UPDATE
				""",
			(rs, rowNum) -> new QuotaState(
				rs.getLong("minute_window"),
				rs.getInt("minute_calls"),
				rs.getLong("day_window"),
				rs.getInt("daily_calls")
			),
			providerId
		);
		int minuteCalls = state.minuteWindow() == minuteWindow ? state.minuteCalls() : 0;
		int dailyCalls = state.dayWindow() == dayWindow ? state.dailyCalls() : 0;
		if (minuteCalls >= minuteLimit || dailyCalls >= dayLimit) {
			return false;
		}
		jdbcTemplate.update(
			"""
				UPDATE train_provider_call_quota_state
				SET minute_window = ?, minute_calls = ?, day_window = ?, daily_calls = ?, updated_at = ?
				WHERE provider_id = ?
				""",
			minuteWindow,
			minuteCalls + 1,
			dayWindow,
			dailyCalls + 1,
			Timestamp.from(databaseNow),
			providerId
		);
		return true;
	}

	@Override
	@Transactional(timeout = 2)
	public int purgeExpiredBefore(Instant cutoff) {
		Objects.requireNonNull(cutoff, "cutoff must not be null");
		return jdbcTemplate.update(
			"""
				DELETE FROM train_search_cache
				WHERE ((expires_at IS NOT NULL AND expires_at < ?)
						OR (expires_at IS NULL AND last_access_at < ?))
					AND (lease_owner IS NULL OR lease_expires_at <= CURRENT_TIMESTAMP)
				""",
			Timestamp.from(cutoff),
			Timestamp.from(cutoff)
		);
	}

	private Instant databaseNow() {
		return Objects.requireNonNull(
			jdbcTemplate.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class)
		).toInstant();
	}

	private record QuotaState(long minuteWindow, int minuteCalls, long dayWindow, int dailyCalls) {}
}

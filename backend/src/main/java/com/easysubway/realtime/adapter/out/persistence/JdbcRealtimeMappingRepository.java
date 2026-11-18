package com.easysubway.realtime.adapter.out.persistence;

import com.easysubway.realtime.application.RealtimeQuery;
import com.easysubway.realtime.application.port.out.RealtimeMappingPort;
import com.easysubway.realtime.domain.RealtimeMapping;
import com.easysubway.realtime.domain.RealtimeTripMapping;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("prod | staging | release | prod-like")
public class JdbcRealtimeMappingRepository implements RealtimeMappingPort {

	private static final int MAX_CACHE_ENTRIES = 4_096;
	private static final Duration CACHE_TTL = Duration.ofSeconds(30);

	private final JdbcTemplate jdbcTemplate;
	private final Clock clock;
	private final Object cacheLock = new Object();
	private final Map<Object, CachedLookup> lookupCache = new LinkedHashMap<>(256, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Object, CachedLookup> eldest) {
			return size() > MAX_CACHE_ENTRIES;
		}
	};

	@Autowired
	public JdbcRealtimeMappingRepository(DataSource dataSource) {
		this(new JdbcTemplate(dataSource), Clock.systemUTC());
	}

	JdbcRealtimeMappingRepository(JdbcTemplate jdbcTemplate) {
		this(jdbcTemplate, Clock.systemUTC());
	}

	JdbcRealtimeMappingRepository(JdbcTemplate jdbcTemplate, Clock clock) {
		this.jdbcTemplate = jdbcTemplate;
		this.clock = clock;
	}

	public void clearCache() {
		synchronized (cacheLock) {
			lookupCache.clear();
		}
	}

	@Override
	public Optional<RealtimeMapping> findArrivalMapping(String providerId, RealtimeQuery query) {
		ArrivalCacheKey key = new ArrivalCacheKey(providerId, query.stationId(), query.lineId(), query.providerLineId());
		CachedLookup cached = cachedLookup(key);
		if (cached != null) {
			@SuppressWarnings("unchecked")
			Optional<RealtimeMapping> hit = (Optional<RealtimeMapping>) cached.value();
			return hit;
		}
		String escapedLineId = escapeLike(query.lineId());
		String escapedProviderLineId = escapeLike(query.providerLineId());
		Optional<RealtimeMapping> loaded = jdbcTemplate.query(
			"""
				SELECT sm.provider_id,
					sm.station_id,
					sm.line_id,
					sm.provider_line_id,
					sm.provider_station_id,
					sm.query_name,
					lm.provider_line_name,
					(sm.supports_arrivals AND lm.supports_arrivals) AS supports_arrivals,
					(sm.supports_train_positions AND lm.supports_train_positions) AS supports_train_positions,
					CASE
						WHEN sm.mapping_confidence IN ('OFFICIAL', 'MANUAL')
							AND lm.mapping_confidence IN ('OFFICIAL', 'MANUAL')
							THEN sm.mapping_confidence
						ELSE 'UNKNOWN'
					END AS mapping_confidence,
					CASE
						WHEN sm.cache_version > lm.cache_version THEN sm.cache_version
						ELSE lm.cache_version
					END AS cache_version
				FROM realtime_provider_station_mappings sm
				JOIN realtime_provider_line_mappings lm
					ON lm.provider_id = sm.provider_id
					AND lm.provider_line_id = sm.provider_line_id
					AND lm.line_id = sm.line_id
				WHERE sm.provider_id = ?
					AND sm.station_id = ?
					AND (CAST(? AS VARCHAR) IS NULL OR CAST(? AS VARCHAR) = '' OR sm.line_id = ? OR sm.line_id LIKE CONCAT('%-', ?) ESCAPE '!')
					AND (CAST(? AS VARCHAR) IS NULL OR CAST(? AS VARCHAR) = '' OR sm.provider_line_id = ? OR sm.provider_station_id LIKE CONCAT('%', ?) ESCAPE '!')
					AND (lm.valid_from IS NULL OR lm.valid_from <= CURRENT_TIMESTAMP)
					AND (lm.valid_until IS NULL OR lm.valid_until > CURRENT_TIMESTAMP)
				ORDER BY
					CASE WHEN sm.line_id = CAST(? AS VARCHAR) THEN 1 ELSE 0 END DESC,
					CASE WHEN sm.provider_line_id = CAST(? AS VARCHAR) THEN 1 ELSE 0 END DESC,
					(CASE WHEN sm.cache_version > lm.cache_version THEN sm.cache_version ELSE lm.cache_version END) DESC,
					sm.line_id ASC,
					sm.provider_line_id ASC,
					sm.provider_station_id ASC
				LIMIT 1
				""",
			this::mapMapping,
			providerId,
			query.stationId(),
			query.lineId(),
			query.lineId(),
			query.lineId(),
			escapedLineId,
			query.providerLineId(),
			query.providerLineId(),
			query.providerLineId(),
			escapedProviderLineId,
			query.lineId(),
			query.providerLineId()
		).stream().findFirst();
		storeLookup(key, loaded);
		return loaded;
	}

	@Override
	public Optional<RealtimeMapping> findTrainPositionMapping(String providerId, RealtimeQuery query) {
		PositionCacheKey key = new PositionCacheKey(providerId, query.lineId(), query.lineName(), query.providerLineId());
		CachedLookup cached = cachedLookup(key);
		if (cached != null) {
			@SuppressWarnings("unchecked")
			Optional<RealtimeMapping> hit = (Optional<RealtimeMapping>) cached.value();
			return hit;
		}
		String escapedLineId = escapeLike(query.lineId());
		Optional<RealtimeMapping> loaded = jdbcTemplate.query(
			"""
				SELECT lm.provider_id,
					'' AS station_id,
					lm.line_id,
					lm.provider_line_id,
					'' AS provider_station_id,
					'' AS query_name,
					lm.provider_line_name,
					lm.supports_arrivals,
					lm.supports_train_positions,
					lm.mapping_confidence,
					lm.cache_version
				FROM realtime_provider_line_mappings lm
				WHERE lm.provider_id = ?
					AND (
						(CAST(? AS VARCHAR) IS NOT NULL AND CAST(? AS VARCHAR) <> '' AND (lm.line_id = ? OR lm.line_id LIKE CONCAT('%-', ?) ESCAPE '!'))
						OR ((CAST(? AS VARCHAR) IS NULL OR CAST(? AS VARCHAR) = '') AND lm.provider_line_name = ?)
					)
					AND (CAST(? AS VARCHAR) IS NULL OR CAST(? AS VARCHAR) = '' OR lm.provider_line_id = ?)
					AND (lm.valid_from IS NULL OR lm.valid_from <= CURRENT_TIMESTAMP)
					AND (lm.valid_until IS NULL OR lm.valid_until > CURRENT_TIMESTAMP)
				ORDER BY
					CASE WHEN lm.line_id = CAST(? AS VARCHAR) THEN 1 ELSE 0 END DESC,
					CASE WHEN lm.provider_line_id = CAST(? AS VARCHAR) THEN 1 ELSE 0 END DESC,
					lm.cache_version DESC,
					lm.line_id ASC,
					lm.provider_line_id ASC
				LIMIT 1
				""",
			this::mapMapping,
			providerId,
			query.lineId(),
			query.lineId(),
			query.lineId(),
			escapedLineId,
			query.lineId(),
			query.lineId(),
			query.lineName(),
			query.providerLineId(),
			query.providerLineId(),
			query.providerLineId(),
			query.lineId(),
			query.providerLineId()
		).stream().findFirst();
		storeLookup(key, loaded);
		return loaded;
	}

	@Override
	public Optional<RealtimeTripMapping> findTripMapping(
		String providerId,
		String lineId,
		String providerLineId,
		String rawDirection,
		String rawDestination,
		String rawServicePattern
	) {
		TripCacheKey key = new TripCacheKey(
			providerId,
			lineId,
			providerLineId,
			rawDirection,
			rawDestination,
			rawServicePattern
		);
		CachedLookup cached = cachedLookup(key);
		if (cached != null) {
			@SuppressWarnings("unchecked")
			Optional<RealtimeTripMapping> hit = (Optional<RealtimeTripMapping>) cached.value();
			return hit;
		}
		String escapedLineId = escapeLike(lineId);
		Optional<RealtimeTripMapping> loaded = jdbcTemplate.query(
			"""
				SELECT provider_id,
					line_id,
					provider_line_id,
					raw_direction,
					canonical_direction,
					raw_destination,
					canonical_destination,
					raw_service_pattern,
					canonical_service_pattern,
					mapping_confidence,
					cache_version
				FROM realtime_provider_trip_mappings
				WHERE provider_id = ?
					AND (CAST(? AS VARCHAR) IS NULL OR CAST(? AS VARCHAR) = '' OR line_id = ? OR line_id LIKE CONCAT('%-', ?) ESCAPE '!')
					AND (CAST(? AS VARCHAR) IS NULL OR CAST(? AS VARCHAR) = '' OR provider_line_id = ?)
					AND (raw_direction = ? OR raw_direction = '')
					AND (raw_destination = ? OR raw_destination = '')
					AND (raw_service_pattern = ? OR raw_service_pattern = '')
					AND mapping_confidence IN ('OFFICIAL', 'MANUAL')
					AND (valid_from IS NULL OR valid_from <= CURRENT_TIMESTAMP)
					AND (valid_until IS NULL OR valid_until > CURRENT_TIMESTAMP)
				ORDER BY
					(CASE WHEN raw_direction = '' THEN 0 ELSE 1 END
					 + CASE WHEN raw_destination = '' THEN 0 ELSE 1 END
					 + CASE WHEN raw_service_pattern = '' THEN 0 ELSE 1 END) DESC,
					CASE WHEN line_id = CAST(? AS VARCHAR) THEN 1 ELSE 0 END DESC,
					cache_version DESC
				LIMIT 1
				""",
			this::mapTripMapping,
			providerId,
			lineId,
			lineId,
			lineId,
			escapedLineId,
			providerLineId,
			providerLineId,
			providerLineId,
			rawDirection,
			rawDestination,
			rawServicePattern,
			lineId
		).stream().findFirst();
		storeLookup(key, loaded);
		return loaded;
	}

	private CachedLookup cachedLookup(Object key) {
		Instant now = clock.instant();
		synchronized (cacheLock) {
			CachedLookup entry = lookupCache.get(key);
			if (entry == null) {
				return null;
			}
			if (!entry.expiresAt().isAfter(now)) {
				lookupCache.remove(key);
				return null;
			}
			return entry;
		}
	}

	private void storeLookup(Object key, Optional<?> value) {
		Instant expiresAt = clock.instant().plus(CACHE_TTL);
		synchronized (cacheLock) {
			lookupCache.put(key, new CachedLookup(value, expiresAt));
		}
	}

	private static String escapeLike(String value) {
		if (value == null || value.isEmpty()) {
			return "";
		}
		StringBuilder escaped = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c == '!' || c == '%' || c == '_') {
				escaped.append('!');
			}
			escaped.append(c);
		}
		return escaped.toString();
	}

	private record CachedLookup(Optional<?> value, Instant expiresAt) {}

	private record ArrivalCacheKey(String providerId, String stationId, String lineId, String providerLineId) {}

	private record PositionCacheKey(String providerId, String lineId, String lineName, String providerLineId) {}

	private record TripCacheKey(
		String providerId,
		String lineId,
		String providerLineId,
		String rawDirection,
		String rawDestination,
		String rawServicePattern
	) {}

	private RealtimeMapping mapMapping(ResultSet resultSet, int rowNumber) throws SQLException {
		return new RealtimeMapping(
			resultSet.getString("provider_id"),
			resultSet.getString("station_id"),
			resultSet.getString("line_id"),
			resultSet.getString("provider_line_id"),
			resultSet.getString("provider_station_id"),
			resultSet.getString("query_name"),
			resultSet.getString("provider_line_name"),
			resultSet.getBoolean("supports_arrivals"),
			resultSet.getBoolean("supports_train_positions"),
			resultSet.getString("mapping_confidence"),
			resultSet.getLong("cache_version")
		);
	}

	private RealtimeTripMapping mapTripMapping(ResultSet resultSet, int rowNumber) throws SQLException {
		return new RealtimeTripMapping(
			resultSet.getString("provider_id"),
			resultSet.getString("line_id"),
			resultSet.getString("provider_line_id"),
			resultSet.getString("raw_direction"),
			resultSet.getString("canonical_direction"),
			resultSet.getString("raw_destination"),
			resultSet.getString("canonical_destination"),
			resultSet.getString("raw_service_pattern"),
			resultSet.getString("canonical_service_pattern"),
			resultSet.getString("mapping_confidence"),
			resultSet.getLong("cache_version")
		);
	}
}

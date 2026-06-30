package com.easysubway.realtime.adapter.out.persistence;

import com.easysubway.realtime.application.RealtimeQuery;
import com.easysubway.realtime.application.port.out.RealtimeMappingPort;
import com.easysubway.realtime.domain.RealtimeMapping;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("prod | staging | release | prod-like")
public class JdbcRealtimeMappingRepository implements RealtimeMappingPort {

	private final JdbcTemplate jdbcTemplate;

	@Autowired
	public JdbcRealtimeMappingRepository(DataSource dataSource) {
		this(new JdbcTemplate(dataSource));
	}

	JdbcRealtimeMappingRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public Optional<RealtimeMapping> findArrivalMapping(String providerId, RealtimeQuery query) {
		try {
			return Optional.ofNullable(jdbcTemplate.queryForObject(
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
						AND sm.line_id = ?
						AND (? IS NULL OR ? = '' OR sm.provider_line_id = ?)
						AND (lm.valid_from IS NULL OR lm.valid_from <= CURRENT_TIMESTAMP)
						AND (lm.valid_until IS NULL OR lm.valid_until > CURRENT_TIMESTAMP)
					""",
				this::mapMapping,
				providerId,
				query.stationId(),
				query.lineId(),
				query.providerLineId(),
				query.providerLineId(),
				query.providerLineId()
			));
		} catch (EmptyResultDataAccessException exception) {
			return Optional.empty();
		}
	}

	@Override
	public Optional<RealtimeMapping> findTrainPositionMapping(String providerId, RealtimeQuery query) {
		try {
			return Optional.ofNullable(jdbcTemplate.queryForObject(
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
						AND lm.line_id = ?
						AND (? IS NULL OR ? = '' OR lm.provider_line_id = ?)
						AND (lm.valid_from IS NULL OR lm.valid_from <= CURRENT_TIMESTAMP)
						AND (lm.valid_until IS NULL OR lm.valid_until > CURRENT_TIMESTAMP)
					""",
				this::mapMapping,
				providerId,
				query.lineId(),
				query.providerLineId(),
				query.providerLineId(),
				query.providerLineId()
			));
		} catch (EmptyResultDataAccessException exception) {
			return Optional.empty();
		}
	}

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
}

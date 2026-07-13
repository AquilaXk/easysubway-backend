package com.easysubway.realtime.adapter.out.persistence;

import com.easysubway.realtime.application.port.out.RealtimeArrivalArchivePort;
import com.easysubway.realtime.domain.RealtimeArrivalObservation;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile("prod | staging | release | prod-like")
public class JdbcRealtimeArrivalArchiveRepository implements RealtimeArrivalArchivePort {

	private final JdbcTemplate jdbcTemplate;

	@Autowired
	public JdbcRealtimeArrivalArchiveRepository(DataSource dataSource) {
		this(new JdbcTemplate(dataSource));
	}

	JdbcRealtimeArrivalArchiveRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	@Transactional
	public void saveAll(List<RealtimeArrivalObservation> observations) {
		Objects.requireNonNull(observations, "observations must not be null");
		if (observations.isEmpty()) {
			return;
		}
		jdbcTemplate.batchUpdate(
			"""
				INSERT INTO realtime_arrival_observations (
					provider_id, station_id, line_id, provider_line_id, provider_station_id,
					train_no, provider_observed_at, backend_received_at,
					raw_eta_seconds, adjusted_eta_seconds, raw_direction, raw_destination,
					retained_until
				) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""",
			observations,
			Math.max(1, observations.size()),
			(statement, observation) -> {
				statement.setString(1, observation.providerId());
				statement.setString(2, observation.stationId());
				statement.setString(3, observation.lineId());
				statement.setString(4, observation.providerLineId());
				statement.setString(5, observation.providerStationId());
				statement.setString(6, observation.trainNo());
				statement.setObject(7, observation.providerObservedAt().atOffset(ZoneOffset.UTC));
				statement.setObject(8, observation.backendReceivedAt().atOffset(ZoneOffset.UTC));
				statement.setObject(9, observation.rawEtaSeconds(), Types.INTEGER);
				statement.setObject(10, observation.adjustedEtaSeconds(), Types.INTEGER);
				statement.setString(11, observation.rawDirection());
				statement.setString(12, observation.rawDestination());
				statement.setObject(13, observation.retainedUntil().atOffset(ZoneOffset.UTC));
			}
			);
	}

	@Override
	@Transactional
	public int deleteExpired(Instant now) {
		Objects.requireNonNull(now, "now must not be null");
		return jdbcTemplate.update(
			"DELETE FROM realtime_arrival_observations WHERE retained_until <= ?",
			now.atOffset(ZoneOffset.UTC)
		);
	}
}

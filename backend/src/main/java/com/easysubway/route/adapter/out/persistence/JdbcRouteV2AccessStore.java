package com.easysubway.route.adapter.out.persistence;

import com.easysubway.route.application.port.out.RouteV2AccessStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile("prod | staging | release | prod-like")
public class JdbcRouteV2AccessStore implements RouteV2AccessStore {

	private static final int DEFAULT_MAX_SESSION_REQUESTS = 50;

	private final JdbcTemplate jdbcTemplate;
	private final int maxSessionRequests;

	@Autowired
	public JdbcRouteV2AccessStore(
		DataSource dataSource,
		@Value("${easysubway.route-v2.session-max-requests:50}") int maxSessionRequests
	) {
		this(new JdbcTemplate(dataSource), maxSessionRequests);
	}

	JdbcRouteV2AccessStore(JdbcTemplate jdbcTemplate) {
		this(jdbcTemplate, DEFAULT_MAX_SESSION_REQUESTS);
	}

	JdbcRouteV2AccessStore(JdbcTemplate jdbcTemplate, int maxSessionRequests) {
		if (maxSessionRequests < 1 || maxSessionRequests > DEFAULT_MAX_SESSION_REQUESTS) {
			throw new IllegalArgumentException("Route V2 session request limit must be between 1 and 50");
		}
		this.jdbcTemplate = jdbcTemplate;
		this.maxSessionRequests = maxSessionRequests;
	}

	@Override
	public void saveSession(RouteV2Session session) {
		jdbcTemplate.update(
			"""
				INSERT INTO route_v2_sessions (token_sha256, scope, issued_at, expires_at, request_count)
				VALUES (?, ?, ?, ?, ?)
				""",
			session.tokenSha256(),
			session.scope(),
			Timestamp.from(session.issuedAt()),
			Timestamp.from(session.expiresAt()),
			session.requestCount()
		);
	}

	@Override
	public SessionUse consumeSession(String tokenSha256, Instant now) {
		int updated = jdbcTemplate.update(
			"""
				UPDATE route_v2_sessions
				SET request_count = request_count + 1
				WHERE token_sha256 = ?
					AND expires_at > ?
					AND request_count < ?
				""",
			tokenSha256,
			Timestamp.from(now),
			maxSessionRequests
		);
		Optional<SessionRow> session = findSession(tokenSha256);
		if (updated == 1 && session.isPresent()) {
			return new SessionUse(SessionStatus.VALID, session.get().scope(), session.get().expiresAt());
		}
		if (session.isEmpty()) {
			return new SessionUse(SessionStatus.MISSING, null, null);
		}
		SessionRow row = session.get();
		if (!row.expiresAt().isAfter(now)) {
			return new SessionUse(SessionStatus.EXPIRED, row.scope(), row.expiresAt());
		}
		return new SessionUse(SessionStatus.LIMITED, row.scope(), row.expiresAt());
	}

	@Override
	public boolean claimNonce(String nonceSha256, Instant expiresAt, Instant now) {
		jdbcTemplate.update(
			"DELETE FROM route_v2_nonce_replays WHERE nonce_sha256 = ? AND expires_at <= ?",
			nonceSha256,
			Timestamp.from(now)
		);
		try {
			return jdbcTemplate.update(
				"INSERT INTO route_v2_nonce_replays (nonce_sha256, expires_at) VALUES (?, ?)",
				nonceSha256,
				Timestamp.from(expiresAt)
			) == 1;
		} catch (DuplicateKeyException exception) {
			return false;
		}
	}

	@Override
	@Transactional
	public boolean claimNonceAndSaveSession(
		String nonceSha256,
		Instant nonceExpiresAt,
		Instant now,
		RouteV2Session session
	) {
		if (!claimNonce(nonceSha256, nonceExpiresAt, now)) {
			return false;
		}
		saveSession(session);
		return true;
	}

	@Override
	public void saveState(RouteV2State state) {
		jdbcTemplate.update(
			"""
				INSERT INTO route_v2_states (
					route_state_id,
					origin_station_id,
					destination_station_id,
					transport_scope,
					requested_departure_at,
					itinerary_json,
					timetable_artifact_id,
					created_at,
					planned_arrival_at,
					expires_at
				) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""",
			state.routeStateId(),
			state.originStationId(),
			state.destinationStationId(),
			state.transportScope(),
			Timestamp.from(state.requestedDepartureAt()),
			state.itineraryJson(),
			state.timetableArtifactId(),
			Timestamp.from(state.createdAt()),
			Timestamp.from(state.plannedArrivalAt()),
			Timestamp.from(state.expiresAt())
		);
	}

	@Override
	public Optional<RouteV2State> loadState(String routeStateId, Instant now) {
		List<RouteV2State> states = jdbcTemplate.query(
			"""
				SELECT route_state_id,
					origin_station_id,
					destination_station_id,
					transport_scope,
					requested_departure_at,
					itinerary_json,
					timetable_artifact_id,
					created_at,
					planned_arrival_at,
					expires_at
				FROM route_v2_states
				WHERE route_state_id = ? AND expires_at > ?
				""",
			this::mapState,
			routeStateId,
			Timestamp.from(now)
		);
		return states.stream().findFirst();
	}

	@Override
	@Transactional
	public int purgeExpired(Instant now) {
		Timestamp cutoff = Timestamp.from(now);
		int purged = jdbcTemplate.update("DELETE FROM route_v2_states WHERE expires_at <= ?", cutoff);
		purged += jdbcTemplate.update("DELETE FROM route_v2_nonce_replays WHERE expires_at <= ?", cutoff);
		purged += jdbcTemplate.update("DELETE FROM route_v2_sessions WHERE expires_at <= ?", cutoff);
		return purged;
	}

	private Optional<SessionRow> findSession(String tokenSha256) {
		return jdbcTemplate.query(
			"SELECT scope, expires_at FROM route_v2_sessions WHERE token_sha256 = ?",
			(resultSet, rowNumber) -> new SessionRow(
				resultSet.getString("scope"),
				resultSet.getTimestamp("expires_at").toInstant()
			),
			tokenSha256
		).stream().findFirst();
	}

	private RouteV2State mapState(ResultSet resultSet, int rowNumber) throws SQLException {
		return new RouteV2State(
			resultSet.getString("route_state_id"),
			resultSet.getString("origin_station_id"),
			resultSet.getString("destination_station_id"),
			resultSet.getString("transport_scope"),
			resultSet.getTimestamp("requested_departure_at").toInstant(),
			resultSet.getString("itinerary_json"),
			resultSet.getString("timetable_artifact_id"),
			resultSet.getTimestamp("created_at").toInstant(),
			resultSet.getTimestamp("planned_arrival_at").toInstant(),
			resultSet.getTimestamp("expires_at").toInstant()
		);
	}

	private record SessionRow(String scope, Instant expiresAt) {
	}
}

package com.easysubway.transit.adapter.out.persistence;

import com.easysubway.transit.application.port.out.FacilityOperationalStatusStore;
import com.easysubway.transit.domain.FacilityOperationalState;
import com.easysubway.transit.domain.FacilityOperationalStatus;
import com.easysubway.transit.domain.FacilityOperationalStatusPriority;
import com.easysubway.transit.domain.FacilityOperationalStatusPriority.FeedAction;
import com.easysubway.transit.domain.FacilityStatusSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code facility_operational_status}·{@code facility_status_feed_heartbeat} JDBC 저장소(#419).
 *
 * <p>한 회차 반영은 하나의 트랜잭션이다. 현재 행을 읽어 {@link FacilityOperationalStatusPriority}로 행마다 동작을 정하고,
 * 갱신은 읽은 출처·관측 시각이 그대로일 때만 적용되게 조건을 건다(그 사이 관리자 기록이 끼어들면 그 행은 이번 회차에 건너뛴다).
 * 새 행 삽입이 동시에 겹치면 트랜잭션 전체가 실패해 심장박동도 옮기지 않는다.
 *
 * <p>이번 회차 관측에 없는 시설(원천에서 빠졌거나 {@code D}, 식별 불가·알 수 없는 코드가 된 시설)의 {@code SEOUL_METRO_FEED}
 * 행은 같은 트랜잭션에서 지운다. 지우기도 읽은 출처·관측 시각이 그대로일 때만 적용한다. {@code ADMIN_VERIFIED} 행은 지우지 않고
 * 수만 센다.
 */
@Repository
public class JdbcFacilityOperationalStatusRepository implements FacilityOperationalStatusStore {

	private static final String SELECT_COLUMNS =
		"SELECT facility_id, status, source, source_code, observed_at, updated_at FROM facility_operational_status";

	private final JdbcTemplate jdbcTemplate;
	private final TransactionTemplate transactions;

	@Autowired
	public JdbcFacilityOperationalStatusRepository(DataSource dataSource, PlatformTransactionManager transactionManager) {
		this.jdbcTemplate = new JdbcTemplate(dataSource);
		this.transactions = new TransactionTemplate(transactionManager);
	}

	JdbcFacilityOperationalStatusRepository(DataSource dataSource) {
		this(dataSource, new DataSourceTransactionManager(dataSource));
	}

	JdbcFacilityOperationalStatusRepository(JdbcTemplate jdbcTemplate, TransactionTemplate transactions) {
		this.jdbcTemplate = jdbcTemplate;
		this.transactions = transactions;
	}

	@Override
	public List<FacilityOperationalStatus> loadStatuses() {
		return jdbcTemplate.query(SELECT_COLUMNS + " ORDER BY facility_id", JdbcFacilityOperationalStatusRepository::mapStatus);
	}

	@Override
	public Optional<Instant> lastSuccessfulCollectionAt(String feed) {
		return jdbcTemplate.query(
			"SELECT last_success_at FROM facility_status_feed_heartbeat WHERE feed = ?",
			(resultSet, rowNumber) -> instant(resultSet, "last_success_at"),
			feed
		).stream().findFirst();
	}

	@Override
	public FeedApplyResult applyFeedCollection(String feed, List<FeedObservation> observations, Instant observedAt) {
		return transactions.execute(status -> {
			Map<String, FacilityOperationalStatus> existing = new HashMap<>();
			for (FacilityOperationalStatus row : loadStatuses()) {
				existing.put(row.facilityId(), row);
			}
			List<Object[]> inserts = new ArrayList<>();
			List<Object[]> overwrites = new ArrayList<>();
			List<Object[]> sourceCodes = new ArrayList<>();
			int keptAdminVerified = 0;
			OffsetDateTime at = timestamp(observedAt);
			Set<String> observedFacilities = new HashSet<>();
			for (FeedObservation observation : observations) {
				observedFacilities.add(observation.facilityId());
				FacilityOperationalStatus current = existing.get(observation.facilityId());
				FeedAction action = FacilityOperationalStatusPriority.resolveFeed(current, observation.sourceCode(), observedAt);
				if (action == FeedAction.INSERT) {
					inserts.add(new Object[] {
						observation.facilityId(), observation.state().name(), FacilityStatusSource.SEOUL_METRO_FEED.name(),
						observation.sourceCode(), at, at
					});
				} else if (action == FeedAction.OVERWRITE) {
					overwrites.add(new Object[] {
						observation.state().name(), FacilityStatusSource.SEOUL_METRO_FEED.name(), observation.sourceCode(), at, at,
						observation.facilityId(), current.source().name(), timestamp(current.observedAt())
					});
				} else if (action == FeedAction.RECORD_SOURCE_CODE) {
					keptAdminVerified++;
					sourceCodes.add(new Object[] {
						observation.sourceCode(), at, observation.facilityId(), timestamp(current.observedAt())
					});
				} else if (current.source() == FacilityStatusSource.ADMIN_VERIFIED) {
					keptAdminVerified++;
				}
			}
			int written = sum(jdbcTemplate.batchUpdate(
				"""
					INSERT INTO facility_operational_status (facility_id, status, source, source_code, observed_at, updated_at)
					VALUES (?, ?, ?, ?, ?, ?)
					""",
				inserts
			));
			written += sum(jdbcTemplate.batchUpdate(
				"""
					UPDATE facility_operational_status
					SET status = ?, source = ?, source_code = ?, observed_at = ?, updated_at = ?
					WHERE facility_id = ? AND source = ? AND observed_at = ?
					""",
				overwrites
			));
			jdbcTemplate.batchUpdate(
				"""
					UPDATE facility_operational_status
					SET source_code = ?, updated_at = ?
					WHERE facility_id = ? AND source = 'ADMIN_VERIFIED' AND observed_at = ?
					""",
				sourceCodes
			);
			List<Object[]> absentFeedRows = new ArrayList<>();
			int adminVerifiedAbsent = 0;
			for (FacilityOperationalStatus row : existing.values()) {
				if (observedFacilities.contains(row.facilityId())) {
					continue;
				}
				if (row.source() == FacilityStatusSource.SEOUL_METRO_FEED) {
					absentFeedRows.add(new Object[] {row.facilityId(), timestamp(row.observedAt())});
				} else {
					adminVerifiedAbsent++;
				}
			}
			int removed = sum(jdbcTemplate.batchUpdate(
				"""
					DELETE FROM facility_operational_status
					WHERE facility_id = ? AND source = 'SEOUL_METRO_FEED' AND observed_at = ?
					""",
				absentFeedRows
			));
			advanceHeartbeat(feed, at);
			return new FeedApplyResult(written, keptAdminVerified, removed, adminVerifiedAbsent);
		});
	}

	@Override
	public AdminVerifiedResult recordAdminVerified(String facilityId, FacilityOperationalState state, Instant verifiedAt) {
		AdminVerifiedResult result = transactions.execute(status -> {
			OffsetDateTime at = timestamp(verifiedAt);
			List<PreviousStatus> previousRows = jdbcTemplate.query(
				"SELECT status, source FROM facility_operational_status WHERE facility_id = ? FOR UPDATE",
				(rs, rowNum) -> new PreviousStatus(
					FacilityOperationalState.valueOf(rs.getString("status")),
					FacilityStatusSource.valueOf(rs.getString("source"))
				),
				facilityId
			);
			if (!previousRows.isEmpty()) {
				PreviousStatus previous = previousRows.get(0);
				int updated = jdbcTemplate.update(
					"""
						UPDATE facility_operational_status
						SET status = ?, source = ?, observed_at = ?, updated_at = ?
						WHERE facility_id = ? AND observed_at <= ?
						""",
					state.name(), FacilityStatusSource.ADMIN_VERIFIED.name(), at, at, facilityId, at
				);
				return new AdminVerifiedResult(
					updated == 1,
					Optional.of(previous.state()),
					Optional.of(previous.source())
				);
			}
			try {
				int inserted = jdbcTemplate.update(
					"""
						INSERT INTO facility_operational_status (facility_id, status, source, source_code, observed_at, updated_at)
						VALUES (?, ?, ?, NULL, ?, ?)
						""",
					facilityId, state.name(), FacilityStatusSource.ADMIN_VERIFIED.name(), at, at
				);
				if (inserted == 0) {
					return new AdminVerifiedResult(false, Optional.empty(), Optional.empty());
				}
				return new AdminVerifiedResult(true, Optional.empty(), Optional.empty());
			} catch (DuplicateKeyException exception) {
				return new AdminVerifiedResult(false, Optional.empty(), Optional.empty());
			}
		});
		return result != null ? result : new AdminVerifiedResult(false, Optional.empty(), Optional.empty());
	}

	private record PreviousStatus(FacilityOperationalState state, FacilityStatusSource source) {
	}

	private void advanceHeartbeat(String feed, OffsetDateTime at) {
		int updated = jdbcTemplate.update(
			"UPDATE facility_status_feed_heartbeat SET last_success_at = ? WHERE feed = ? AND last_success_at <= ?",
			at, feed, at
		);
		if (updated == 0 && lastSuccessfulCollectionAt(feed).isEmpty()) {
			jdbcTemplate.update(
				"INSERT INTO facility_status_feed_heartbeat (feed, last_success_at) VALUES (?, ?)",
				feed, at
			);
		}
	}

	private static int sum(int[] counts) {
		return Arrays.stream(counts).sum();
	}

	private static FacilityOperationalStatus mapStatus(ResultSet resultSet, int rowNumber) throws SQLException {
		return new FacilityOperationalStatus(
			resultSet.getString("facility_id"),
			FacilityOperationalState.valueOf(resultSet.getString("status")),
			FacilityStatusSource.valueOf(resultSet.getString("source")),
			resultSet.getString("source_code"),
			instant(resultSet, "observed_at"),
			instant(resultSet, "updated_at")
		);
	}

	private static Instant instant(ResultSet resultSet, String column) throws SQLException {
		return resultSet.getObject(column, OffsetDateTime.class).toInstant();
	}

	private static OffsetDateTime timestamp(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}
}

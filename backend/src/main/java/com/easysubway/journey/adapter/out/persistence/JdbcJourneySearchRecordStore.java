package com.easysubway.journey.adapter.out.persistence;

import com.easysubway.journey.analytics.JourneySearchAggregateRow;
import com.easysubway.journey.analytics.JourneySearchKind;
import com.easysubway.journey.analytics.JourneySearchOutcome;
import com.easysubway.journey.analytics.JourneySearchRecord;
import com.easysubway.journey.analytics.JourneySearchRecordStore;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("prod | staging | release | prod-like")
public class JdbcJourneySearchRecordStore implements JourneySearchRecordStore {

	private final JdbcTemplate jdbcTemplate;

	@Autowired
	public JdbcJourneySearchRecordStore(DataSource dataSource) {
		this.jdbcTemplate = new JdbcTemplate(dataSource);
	}

	@Override
	public void save(JourneySearchRecord record) {
		jdbcTemplate.update("""
			INSERT INTO journey_v3_search_records (record_id, recorded_at, recorded_on, search_kind, outcome,
				http_status, machine_code, engine_version, mobility_profile, alternative_categories, stair_free_status)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			""",
			record.recordId(), Timestamp.from(record.recordedAt()), Date.valueOf(record.recordedOn()),
			record.kind().name(), record.outcome().name(), record.httpStatus(), record.machineCode(),
			record.engineVersion(), record.mobilityProfile(), record.alternativeCategoriesText(),
			record.stairFreeStatus());
	}

	@Override
	public List<JourneySearchAggregateRow> aggregate(LocalDate fromInclusive, LocalDate toInclusive) {
		return jdbcTemplate.query("""
			SELECT recorded_on, search_kind, outcome, engine_version, mobility_profile, alternative_categories,
				stair_free_status, COUNT(*) AS record_count
			FROM journey_v3_search_records
			WHERE recorded_on >= ? AND recorded_on <= ?
			GROUP BY recorded_on, search_kind, outcome, engine_version, mobility_profile, alternative_categories,
				stair_free_status
			ORDER BY recorded_on
			""",
			(rs, rowNum) -> new JourneySearchAggregateRow(
				rs.getDate("recorded_on").toLocalDate(),
				JourneySearchKind.valueOf(rs.getString("search_kind")),
				JourneySearchOutcome.valueOf(rs.getString("outcome")),
				rs.getString("engine_version"), rs.getString("mobility_profile"),
				rs.getString("alternative_categories"), rs.getString("stair_free_status"),
				rs.getLong("record_count")),
			Date.valueOf(fromInclusive), Date.valueOf(toInclusive));
	}
}

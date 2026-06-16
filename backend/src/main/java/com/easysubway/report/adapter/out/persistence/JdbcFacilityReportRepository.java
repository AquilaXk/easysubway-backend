package com.easysubway.report.adapter.out.persistence;

import com.easysubway.report.application.port.out.LoadFacilityReportPort;
import com.easysubway.report.application.port.out.SaveFacilityReportPort;
import com.easysubway.report.domain.FacilityReport;
import com.easysubway.report.domain.FacilityReportStatus;
import com.easysubway.report.domain.FacilityReportType;
import com.easysubway.user.application.port.out.AnonymizeUserFacilityReportPort;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("prod")
public class JdbcFacilityReportRepository implements
	LoadFacilityReportPort,
	SaveFacilityReportPort,
	AnonymizeUserFacilityReportPort {

	private static final String DELETED_DESCRIPTION = "사용자 데이터 삭제로 신고 내용이 삭제되었습니다.";

	private final JdbcTemplate jdbcTemplate;
	private final DatabaseDialect databaseDialect;

	@Autowired
	public JdbcFacilityReportRepository(DataSource dataSource) {
		this(new JdbcTemplate(dataSource));
	}

	JdbcFacilityReportRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
		this.databaseDialect = detectDatabaseDialect(jdbcTemplate);
	}

	@Override
	public Optional<FacilityReport> loadReport(String reportId) {
		try {
			return Optional.ofNullable(jdbcTemplate.queryForObject(
				"""
					SELECT report_id,
						user_id,
						station_id,
						facility_id,
						report_type,
						description,
						photo_file_name,
						photo_content_type,
						photo_data_base64,
						latitude,
						longitude,
						duplicate_of_report_id,
						status,
						created_at,
						reviewed_at,
						reviewed_by
					FROM facility_reports
					WHERE report_id = ?
					""",
				this::mapFacilityReport,
				reportId
			));
		} catch (EmptyResultDataAccessException exception) {
			return Optional.empty();
		}
	}

	@Override
	public List<FacilityReport> loadReports() {
		return jdbcTemplate.query(
			"""
				SELECT report_id,
					user_id,
					station_id,
					facility_id,
					report_type,
					description,
					photo_file_name,
					photo_content_type,
					photo_data_base64,
					latitude,
					longitude,
					duplicate_of_report_id,
					status,
					created_at,
					reviewed_at,
					reviewed_by
				FROM facility_reports
				ORDER BY created_at DESC, report_id ASC
				""",
			this::mapFacilityReport
		);
	}

	@Override
	public FacilityReport saveReport(FacilityReport report) {
		upsertReport(report);
		return report;
	}

	@Override
	public int anonymizeFacilityReportsByUserId(String userId) {
		return jdbcTemplate.update(
			"""
				UPDATE facility_reports
				SET user_id = ?,
					description = ?,
					photo_file_name = NULL,
					photo_content_type = NULL,
					photo_data_base64 = NULL,
					latitude = NULL,
					longitude = NULL
				WHERE user_id = ?
				""",
			FacilityReport.ANONYMIZED_USER_ID,
			DELETED_DESCRIPTION,
			userId
		);
	}

	private void upsertReport(FacilityReport report) {
		if (databaseDialect == DatabaseDialect.H2) {
			upsertReportWithH2Merge(report);
			return;
		}
		upsertReportWithPostgresql(report);
	}

	private void upsertReportWithPostgresql(FacilityReport report) {
		// 검수 상태 갱신과 재저장을 같은 SQL 경로로 처리해 운영 저장소의 행 단위 일관성을 유지한다.
		jdbcTemplate.update(
			"""
				INSERT INTO facility_reports (
					report_id,
					user_id,
					station_id,
					facility_id,
					report_type,
					description,
					photo_file_name,
					photo_content_type,
					photo_data_base64,
					latitude,
					longitude,
					duplicate_of_report_id,
					status,
					created_at,
					reviewed_at,
					reviewed_by
				)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT (report_id) DO UPDATE
				SET user_id = EXCLUDED.user_id,
					station_id = EXCLUDED.station_id,
					facility_id = EXCLUDED.facility_id,
					report_type = EXCLUDED.report_type,
					description = EXCLUDED.description,
					photo_file_name = EXCLUDED.photo_file_name,
					photo_content_type = EXCLUDED.photo_content_type,
					photo_data_base64 = EXCLUDED.photo_data_base64,
					latitude = EXCLUDED.latitude,
					longitude = EXCLUDED.longitude,
					duplicate_of_report_id = EXCLUDED.duplicate_of_report_id,
					status = EXCLUDED.status,
					created_at = EXCLUDED.created_at,
					reviewed_at = EXCLUDED.reviewed_at,
					reviewed_by = EXCLUDED.reviewed_by
				""",
			reportParameters(report)
		);
	}

	private void upsertReportWithH2Merge(FacilityReport report) {
		jdbcTemplate.update(
			"""
				MERGE INTO facility_reports (
					report_id,
					user_id,
					station_id,
					facility_id,
					report_type,
					description,
					photo_file_name,
					photo_content_type,
					photo_data_base64,
					latitude,
					longitude,
					duplicate_of_report_id,
					status,
					created_at,
					reviewed_at,
					reviewed_by
				)
				KEY (report_id)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""",
			reportParameters(report)
		);
	}

	private Object[] reportParameters(FacilityReport report) {
		return new Object[] {
			report.id(),
			report.userId(),
			report.stationId(),
			report.facilityId(),
			report.reportType().name(),
			report.description(),
			report.photoFileName(),
			report.photoContentType(),
			report.photoDataBase64(),
			report.latitude(),
			report.longitude(),
			report.duplicateOfReportId(),
			report.status().name(),
			report.createdAt(),
			report.reviewedAt(),
			report.reviewedBy()
		};
	}

	private FacilityReport mapFacilityReport(ResultSet resultSet, int rowNumber) throws SQLException {
		return new FacilityReport(
			resultSet.getString("report_id"),
			resultSet.getString("user_id"),
			resultSet.getString("station_id"),
			resultSet.getString("facility_id"),
			FacilityReportType.valueOf(resultSet.getString("report_type")),
			resultSet.getString("description"),
			resultSet.getString("photo_file_name"),
			resultSet.getString("photo_content_type"),
			resultSet.getString("photo_data_base64"),
			resultSet.getBigDecimal("latitude"),
			resultSet.getBigDecimal("longitude"),
			resultSet.getString("duplicate_of_report_id"),
			FacilityReportStatus.valueOf(resultSet.getString("status")),
			resultSet.getTimestamp("created_at").toLocalDateTime(),
			resultSet.getTimestamp("reviewed_at") == null ? null : resultSet.getTimestamp("reviewed_at").toLocalDateTime(),
			resultSet.getString("reviewed_by")
		);
	}

	private DatabaseDialect detectDatabaseDialect(JdbcTemplate jdbcTemplate) {
		DatabaseDialect dialect = jdbcTemplate.execute((ConnectionCallback<DatabaseDialect>) connection -> {
			String productName = connection.getMetaData().getDatabaseProductName();
			return "H2".equalsIgnoreCase(productName) ? DatabaseDialect.H2 : DatabaseDialect.POSTGRESQL;
		});
		return dialect == null ? DatabaseDialect.POSTGRESQL : dialect;
	}

	private enum DatabaseDialect {
		POSTGRESQL,
		H2
	}
}

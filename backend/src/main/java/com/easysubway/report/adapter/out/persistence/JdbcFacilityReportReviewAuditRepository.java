package com.easysubway.report.adapter.out.persistence;

import com.easysubway.report.application.port.out.LoadFacilityReportReviewAuditPort;
import com.easysubway.report.application.port.out.SaveFacilityReportReviewAuditPort;
import com.easysubway.report.domain.FacilityReportReviewAudit;
import com.easysubway.report.domain.FacilityReportReviewDecision;
import com.easysubway.report.domain.FacilityReportStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("prod")
public class JdbcFacilityReportReviewAuditRepository implements
	LoadFacilityReportReviewAuditPort,
	SaveFacilityReportReviewAuditPort {

	private final JdbcTemplate jdbcTemplate;

	public JdbcFacilityReportReviewAuditRepository(DataSource dataSource) {
		this(new JdbcTemplate(dataSource));
	}

	JdbcFacilityReportReviewAuditRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public FacilityReportReviewAudit saveAudit(FacilityReportReviewAudit audit) {
		jdbcTemplate.update(
			"""
				INSERT INTO facility_report_review_audits (
					audit_id,
					report_id,
					reviewer_id,
					decision,
					previous_status,
					next_status,
					created_at
				)
				VALUES (?, ?, ?, ?, ?, ?, ?)
				""",
			audit.id(),
			audit.reportId(),
			audit.reviewerId(),
			audit.decision().name(),
			audit.previousStatus().name(),
			audit.nextStatus().name(),
			audit.createdAt()
		);
		return audit;
	}

	@Override
	public List<FacilityReportReviewAudit> loadAuditsByReportId(String reportId) {
		return jdbcTemplate.query(
			"""
				SELECT audit_id,
					report_id,
					reviewer_id,
					decision,
					previous_status,
					next_status,
					created_at
				FROM facility_report_review_audits
				WHERE report_id = ?
				ORDER BY created_at ASC, audit_id ASC
				""",
			this::mapAudit,
			reportId
		);
	}

	private FacilityReportReviewAudit mapAudit(ResultSet resultSet, int rowNumber) throws SQLException {
		return new FacilityReportReviewAudit(
			resultSet.getString("audit_id"),
			resultSet.getString("report_id"),
			resultSet.getString("reviewer_id"),
			FacilityReportReviewDecision.valueOf(resultSet.getString("decision")),
			FacilityReportStatus.valueOf(resultSet.getString("previous_status")),
			FacilityReportStatus.valueOf(resultSet.getString("next_status")),
			resultSet.getTimestamp("created_at").toLocalDateTime()
		);
	}
}

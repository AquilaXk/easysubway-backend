package com.easysubway.report.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.report.domain.FacilityReportReviewAudit;
import com.easysubway.report.domain.FacilityReportReviewDecision;
import com.easysubway.report.domain.FacilityReportStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@DisplayName("JDBC 신고 검수 감사 로그 저장소")
class JdbcFacilityReportReviewAuditRepositoryTest {

	private JdbcFacilityReportReviewAuditRepository repository;
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		var dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:facility-report-review-audits;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		jdbcTemplate = new JdbcTemplate(dataSource);
		jdbcTemplate.execute("DROP TABLE IF EXISTS facility_report_review_audits");
		jdbcTemplate.execute("""
			CREATE TABLE facility_report_review_audits (
				audit_id VARCHAR(120) NOT NULL PRIMARY KEY,
				report_id VARCHAR(120) NOT NULL,
				reviewer_id VARCHAR(120) NOT NULL,
				decision VARCHAR(40) NOT NULL,
				previous_status VARCHAR(40) NOT NULL,
				next_status VARCHAR(40) NOT NULL,
				created_at TIMESTAMP NOT NULL,
				CONSTRAINT chk_facility_report_review_audits_decision
					CHECK (decision IN ('ACCEPT', 'REJECT', 'MARK_DUPLICATE')),
				CONSTRAINT chk_facility_report_review_audits_previous_status
					CHECK (previous_status IN ('SUBMITTED', 'DUPLICATE', 'UNDER_REVIEW', 'ACCEPTED', 'REJECTED', 'RESOLVED')),
				CONSTRAINT chk_facility_report_review_audits_next_status
					CHECK (next_status IN ('SUBMITTED', 'DUPLICATE', 'UNDER_REVIEW', 'ACCEPTED', 'REJECTED', 'RESOLVED'))
			)
			""");
		repository = new JdbcFacilityReportReviewAuditRepository(jdbcTemplate);
	}

	@Test
	@DisplayName("신고 검수 감사 로그를 저장하고 신고 식별자 기준 생성순으로 조회한다")
	void saveAuditAndLoadAuditsByReportIdOldestFirst() {
		repository.saveAudit(audit(
			"audit-new",
			"report-target",
			FacilityReportReviewDecision.REJECT,
			FacilityReportStatus.UNDER_REVIEW,
			FacilityReportStatus.REJECTED,
			LocalDateTime.of(2026, 6, 19, 11, 0)
		));
		repository.saveAudit(audit(
			"audit-old",
			"report-target",
			FacilityReportReviewDecision.ACCEPT,
			FacilityReportStatus.SUBMITTED,
			FacilityReportStatus.ACCEPTED,
			LocalDateTime.of(2026, 6, 19, 10, 0)
		));
		repository.saveAudit(audit(
			"audit-other-report",
			"report-other",
			FacilityReportReviewDecision.MARK_DUPLICATE,
			FacilityReportStatus.SUBMITTED,
			FacilityReportStatus.DUPLICATE,
			LocalDateTime.of(2026, 6, 19, 12, 0)
		));

		var audits = repository.loadAuditsByReportId("report-target");

		assertThat(audits)
			.extracting(FacilityReportReviewAudit::id)
			.containsExactly("audit-old", "audit-new");
		assertThat(audits.get(0)).satisfies(audit -> {
			assertThat(audit.reportId()).isEqualTo("report-target");
			assertThat(audit.reviewerId()).isEqualTo("admin-reviewer");
			assertThat(audit.decision()).isEqualTo(FacilityReportReviewDecision.ACCEPT);
			assertThat(audit.previousStatus()).isEqualTo(FacilityReportStatus.SUBMITTED);
			assertThat(audit.nextStatus()).isEqualTo(FacilityReportStatus.ACCEPTED);
			assertThat(audit.createdAt()).isEqualTo(LocalDateTime.of(2026, 6, 19, 10, 0));
		});
	}

	@Test
	@DisplayName("저장소를 재생성해도 저장된 신고 검수 감사 로그를 조회한다")
	void loadAuditsByReportIdAfterRepositoryRecreation() {
		repository.saveAudit(audit(
			"audit-persisted",
			"report-persisted",
			FacilityReportReviewDecision.REJECT,
			FacilityReportStatus.UNDER_REVIEW,
			FacilityReportStatus.REJECTED,
			LocalDateTime.of(2026, 6, 19, 13, 0)
		));

		var recreatedRepository = new JdbcFacilityReportReviewAuditRepository(jdbcTemplate);

		assertThat(recreatedRepository.loadAuditsByReportId("report-persisted"))
			.extracting(FacilityReportReviewAudit::id)
			.containsExactly("audit-persisted");
	}

	@Test
	@DisplayName("감사 로그가 없는 신고는 빈 목록을 반환한다")
	void loadAuditsByReportIdReturnsEmptyWhenAuditDoesNotExist() {
		assertThat(repository.loadAuditsByReportId("missing-report")).isEmpty();
	}

	private FacilityReportReviewAudit audit(
		String auditId,
		String reportId,
		FacilityReportReviewDecision decision,
		FacilityReportStatus previousStatus,
		FacilityReportStatus nextStatus,
		LocalDateTime createdAt
	) {
		return new FacilityReportReviewAudit(
			auditId,
			reportId,
			"admin-reviewer",
			decision,
			previousStatus,
			nextStatus,
			createdAt
		);
	}
}

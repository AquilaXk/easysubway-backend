package com.easysubway.report.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.easysubway.report.application.port.in.FacilityReportPageRequest;
import com.easysubway.report.domain.FacilityReport;
import com.easysubway.report.domain.FacilityReportStatus;
import com.easysubway.report.domain.FacilityReportType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@DisplayName("JDBC 시설 신고 저장소")
class JdbcFacilityReportRepositoryTest {

	private JdbcFacilityReportRepository repository;
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		var dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:facility-reports;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		jdbcTemplate = new JdbcTemplate(dataSource);
		jdbcTemplate.execute("DROP TABLE IF EXISTS facility_reports");
		jdbcTemplate.execute("""
			CREATE TABLE facility_reports (
				report_id VARCHAR(120) NOT NULL PRIMARY KEY,
				public_receipt_code VARCHAR(16) NOT NULL UNIQUE,
				user_id VARCHAR(120) NOT NULL,
				station_id VARCHAR(120) NOT NULL,
				facility_id VARCHAR(120) NOT NULL,
				report_type VARCHAR(40) NOT NULL,
				description VARCHAR(1000),
				photo_file_name VARCHAR(255),
				photo_content_type VARCHAR(80),
				photo_object_key VARCHAR(255),
				photo_thumbnail_object_key VARCHAR(255),
				photo_sha256 CHAR(64),
				photo_size_bytes BIGINT,
				latitude DECIMAL(10, 7),
				longitude DECIMAL(10, 7),
				duplicate_of_report_id VARCHAR(120),
				status VARCHAR(40) NOT NULL,
				created_at TIMESTAMP NOT NULL,
				reviewed_at TIMESTAMP,
				reviewed_by VARCHAR(120),
				client_submission_id VARCHAR(120),
				receipt_token_hash CHAR(64),
				CONSTRAINT ux_facility_reports_client_submission UNIQUE (client_submission_id),
				CONSTRAINT fk_facility_reports_duplicate
					FOREIGN KEY (duplicate_of_report_id) REFERENCES facility_reports(report_id)
					ON DELETE SET NULL ON UPDATE CASCADE,
				CONSTRAINT chk_facility_reports_report_type
					CHECK (report_type IN ('BROKEN', 'UNDER_CONSTRUCTION', 'CLOSED', 'LOCATION_WRONG', 'INFORMATION_WRONG', 'RECOVERED')),
				CONSTRAINT chk_facility_reports_status
					CHECK (status IN ('SUBMITTED', 'DUPLICATE', 'UNDER_REVIEW', 'ACCEPTED', 'REJECTED', 'RESOLVED'))
			)
			""");
		repository = new JdbcFacilityReportRepository(jdbcTemplate);
	}

	@Test
	@DisplayName("시설 신고를 저장하고 신고 식별자로 조회한다")
	void saveFacilityReportAndLoadByReportId() {
		var report = submittedReport("report-1", "anonymous-user-1", 9);

		repository.saveReport(report);

		assertThat(repository.loadReport("report-1")).contains(report);
	}

	@Test
	@DisplayName("시설 신고 목록은 생성 시각 최신순과 신고 식별자 순서로 조회한다")
	void loadReportsOrdersByCreatedAtDescAndReportId() {
		var olderReport = submittedReport("report-2", "anonymous-user-1", 8);
		var sameTimeSecondReport = submittedReport("report-3", "anonymous-user-2", 9);
		var sameTimeFirstReport = submittedReport("report-1", "anonymous-user-3", 9);
		repository.saveReport(olderReport);
		repository.saveReport(sameTimeSecondReport);
		repository.saveReport(sameTimeFirstReport);

		assertThat(repository.loadReports())
			.containsExactly(sameTimeFirstReport, sameTimeSecondReport, olderReport);
	}

	@Test
	@DisplayName("사용자 신고 summary 목록은 사용자와 page 범위로 제한한다")
	void loadUserReportSummariesReturnsRequestedUserPage() {
		var olderReport = submittedReport("report-1", "anonymous-user-1", 8);
		var middleReport = submittedReport("report-2", "anonymous-user-1", 9);
		var newerReport = submittedReport("report-3", "anonymous-user-1", 10);
		var otherUserReport = submittedReport("report-4", "anonymous-user-2", 11);
		repository.saveReport(olderReport);
		repository.saveReport(middleReport);
		repository.saveReport(newerReport);
		repository.saveReport(otherUserReport);

		var page = repository.loadUserReportSummaries(
			"anonymous-user-1",
			new FacilityReportPageRequest(0, 2)
		);

		assertThat(page.items())
			.extracting("id")
			.containsExactly(newerReport.id(), middleReport.id());
		assertThat(page.hasNext()).isTrue();
	}

	@Test
	@DisplayName("관리자 신고 summary 목록은 상태와 page 범위로 제한한다")
	void loadReportSummariesReturnsStatusPage() {
		var originalReport = submittedReport("report-0", "anonymous-user-0", 7);
		var firstSubmittedReport = submittedReport("report-1", "anonymous-user-1", 8);
		var secondSubmittedReport = submittedReport("report-2", "anonymous-user-2", 9);
		var reviewedReport = reviewedReport("report-3");
		repository.saveReport(originalReport);
		repository.saveReport(firstSubmittedReport);
		repository.saveReport(secondSubmittedReport);
		repository.saveReport(reviewedReport);

		var page = repository.loadReportSummaries(
			FacilityReportStatus.SUBMITTED,
			new FacilityReportPageRequest(0, 1)
		);

		assertThat(page.items())
			.extracting("id")
			.containsExactly(secondSubmittedReport.id());
		assertThat(page.hasNext()).isTrue();
		assertThat(page.items().getFirst().hasPhoto()).isTrue();
	}

	@Test
	@DisplayName("시설 신고 상태별 집계는 상태와 개수만 조회한다")
	void loadReportStatusCountsAggregatesByStatus() {
		repository.saveReport(submittedReport("report-0", "anonymous-user-0", 8));
		repository.saveReport(submittedReport("report-1", "anonymous-user-1", 9));
		repository.saveReport(submittedReport("report-2", "anonymous-user-2", 10));
		repository.saveReport(reviewedReport("report-3"));

		assertThat(repository.loadReportStatusCounts())
			.containsExactlyInAnyOrderEntriesOf(Map.of(
				FacilityReportStatus.SUBMITTED, 3L,
				FacilityReportStatus.DUPLICATE, 1L
			));
	}

	@Test
	@DisplayName("시설 신고 저장소는 반복 고장 신고 시설을 집계한다")
	void loadRepeatedBrokenReportFacilitiesAggregatesBrokenReportsByFacility() {
		repository.saveReport(submittedReport("report-1", "anonymous-user-1", 9));
		repository.saveReport(submittedReport("report-2", "anonymous-user-2", 10));
		repository.saveReport(new FacilityReport(
			"report-3",
			"anonymous-user-3",
			"station-sangnoksu",
			"facility-elevator-1",
			FacilityReportType.INFORMATION_WRONG,
			"시설 정보가 다릅니다.",
			null,
			null,
			null,
			null,
			null,
			null,
			FacilityReportStatus.SUBMITTED,
			LocalDateTime.of(2026, 6, 17, 11, 0),
			null,
			null
		));

		assertThat(repository.loadRepeatedBrokenReportFacilities())
			.extracting("stationId", "facilityId", "reportCount")
			.containsExactly(tuple("station-sangnoksu", "facility-elevator-1", 2L));
	}

	@Test
	@DisplayName("이미 저장된 시설 신고는 검수 상태와 중복 신고 정보를 갱신한다")
	void saveReportUpdatesExistingReport() {
		repository.saveReport(submittedReport("report-0", "anonymous-user-2", 8));
		repository.saveReport(submittedReport("report-1", "anonymous-user-1", 9));
		var reviewedReport = reviewedReport("report-1");

		repository.saveReport(reviewedReport);

		assertThat(repository.loadReport("report-1")).contains(reviewedReport);
	}

	@Test
	@DisplayName("이미 저장된 시설 신고는 처리 결과 확인 완료 상태로 갱신된다")
	void saveReportUpdatesExistingReportToResolvedStatus() {
		var submittedReport = submittedReport("report-1", "anonymous-user-1", 9);
		var confirmedReport = new FacilityReport(
			"report-1",
			"anonymous-user-1",
			"station-sangnoksu",
			"facility-elevator-1",
			FacilityReportType.BROKEN,
			"엘리베이터가 멈춰 있습니다.",
			"elevator.jpg",
			"image/jpeg",
			"aW1hZ2UtYnl0ZXM=",
			new BigDecimal("37.3123450"),
			new BigDecimal("126.9876540"),
			null,
			FacilityReportStatus.RESOLVED,
			LocalDateTime.of(2026, 6, 17, 9, 0),
			LocalDateTime.of(2026, 6, 17, 10, 0),
			"admin-user"
		);
		repository.saveReport(submittedReport);

		repository.saveReport(confirmedReport);

		assertThat(repository.loadReport("report-1")).contains(confirmedReport);
	}

	@Test
	@DisplayName("이미 저장된 시설 신고 갱신은 최초 생성 시각을 유지한다")
	void saveReportKeepsOriginalCreatedAtWhenUpdatingExistingReport() {
		var originalReport = submittedReport("report-1", "anonymous-user-1", 9);
		var updatedReport = new FacilityReport(
			"report-1",
			"anonymous-user-1",
			"station-sangnoksu",
			"facility-elevator-1",
			FacilityReportType.BROKEN,
			"엘리베이터가 다시 움직이지 않습니다.",
			"elevator-updated.jpg",
			"image/jpeg",
			"dXBkYXRlZC1pbWFnZQ==",
			new BigDecimal("37.3123450"),
			new BigDecimal("126.9876540"),
			null,
			FacilityReportStatus.UNDER_REVIEW,
			LocalDateTime.of(2026, 6, 17, 11, 0),
			LocalDateTime.of(2026, 6, 17, 11, 10),
			"admin-user"
		);
		repository.saveReport(originalReport);

		repository.saveReport(updatedReport);

		FacilityReport savedReport = repository.loadReport("report-1").orElseThrow();
		assertThat(savedReport.createdAt()).isEqualTo(originalReport.createdAt());
		assertThat(savedReport.status()).isEqualTo(FacilityReportStatus.UNDER_REVIEW);
		assertThat(savedReport.reviewedAt()).isEqualTo(updatedReport.reviewedAt());
	}

	@Test
	@DisplayName("검수 저장은 기대 상태와 일치할 때만 신고 상태를 바꾼다")
	void saveReviewedReportIfStatusUpdatesOnlyWhenExpectedStatusMatches() {
		var submittedReport = submittedReport("report-1", "anonymous-user-1", 9);
		var reviewedReport = new FacilityReport(
			"report-1",
			"anonymous-user-1",
			"station-sangnoksu",
			"facility-elevator-1",
			FacilityReportType.BROKEN,
			"엘리베이터가 멈춰 있습니다.",
			"elevator.jpg",
			"image/jpeg",
			"aW1hZ2UtYnl0ZXM=",
			new BigDecimal("37.3123450"),
			new BigDecimal("126.9876540"),
			null,
			FacilityReportStatus.ACCEPTED,
			LocalDateTime.of(2026, 6, 17, 9, 0),
			LocalDateTime.of(2026, 6, 17, 10, 0),
			"admin-user"
		);
		repository.saveReport(submittedReport);

		var savedReport = repository.saveReviewedReportIfStatus(reviewedReport, FacilityReportStatus.SUBMITTED);
		var repeatedSave = repository.saveReviewedReportIfStatus(reviewedReport, FacilityReportStatus.SUBMITTED);

		assertThat(savedReport).contains(reviewedReport);
		assertThat(repeatedSave).isEmpty();
		assertThat(repository.loadReport("report-1")).contains(reviewedReport);
	}

	@Test
	@DisplayName("사용자 데이터 삭제 요청은 미검수 신고 본문과 사진과 위치를 익명화한다")
	void anonymizeFacilityReportsByUserIdClearsSubmittedReportPersonalData() {
		var targetReport = submittedReport("report-1", "anonymous-user-1", 9);
		var otherUserReport = submittedReport("report-2", "anonymous-user-2", 10);
		repository.saveReport(targetReport);
		repository.saveReport(otherUserReport);

		int anonymizedCount = repository.anonymizeFacilityReportsByUserId("anonymous-user-1");
		int anonymizedAgainCount = repository.anonymizeFacilityReportsByUserId("anonymous-user-1");

		assertThat(anonymizedCount).isEqualTo(1);
		assertThat(anonymizedAgainCount).isZero();
		assertThat(repository.loadReport("report-1")).contains(new FacilityReport(
			"report-1",
			FacilityReport.ANONYMIZED_USER_ID,
			targetReport.stationId(),
			targetReport.facilityId(),
			targetReport.reportType(),
			"사용자 데이터 삭제로 신고 내용이 삭제되었습니다.",
			null,
			null,
			null,
			null,
			null,
			targetReport.duplicateOfReportId(),
			targetReport.status(),
			targetReport.createdAt(),
			targetReport.reviewedAt(),
			targetReport.reviewedBy()
		));
		assertThat(repository.loadReport("report-2")).contains(otherUserReport);
	}

	@Test
	@DisplayName("사용자 데이터 삭제 요청은 검수 완료 신고의 검수 정보를 유지한다")
	void anonymizeFacilityReportsByUserIdKeepsReviewedReportMetadata() {
		repository.saveReport(submittedReport("report-0", "anonymous-user-2", 8));
		var targetReport = reviewedReport("report-1");
		repository.saveReport(targetReport);

		int anonymizedCount = repository.anonymizeFacilityReportsByUserId("anonymous-user-1");

		assertThat(anonymizedCount).isEqualTo(1);
		FacilityReport anonymizedReport = repository.loadReport("report-1").orElseThrow();
		assertThat(anonymizedReport.userId()).isEqualTo(FacilityReport.ANONYMIZED_USER_ID);
		assertThat(anonymizedReport.description()).isEqualTo("사용자 데이터 삭제로 신고 내용이 삭제되었습니다.");
		assertThat(anonymizedReport.photoObjectKey()).isNull();
		assertThat(anonymizedReport.photoThumbnailObjectKey()).isNull();
		assertThat(anonymizedReport.photoSha256()).isNull();
		assertThat(anonymizedReport.photoSizeBytes()).isNull();
		assertThat(anonymizedReport.latitude()).isNull();
		assertThat(anonymizedReport.longitude()).isNull();
		assertThat(anonymizedReport.duplicateOfReportId()).isEqualTo(targetReport.duplicateOfReportId());
		assertThat(anonymizedReport.status()).isEqualTo(targetReport.status());
		assertThat(anonymizedReport.reviewedAt()).isEqualTo(targetReport.reviewedAt());
		assertThat(anonymizedReport.reviewedBy()).isEqualTo(targetReport.reviewedBy());
	}

	private FacilityReport submittedReport(String reportId, String userId, int hour) {
		return new FacilityReport(
			reportId,
			userId,
			"station-sangnoksu",
			"facility-elevator-1",
			FacilityReportType.BROKEN,
			"엘리베이터가 멈춰 있습니다.",
			"elevator.jpg",
			"image/jpeg",
			"aW1hZ2UtYnl0ZXM=",
			new BigDecimal("37.3123450"),
			new BigDecimal("126.9876540"),
			null,
			FacilityReportStatus.SUBMITTED,
			LocalDateTime.of(2026, 6, 17, hour, 0),
			null,
			null
		);
	}

	private FacilityReport reviewedReport(String reportId) {
		return new FacilityReport(
			reportId,
			"anonymous-user-1",
			"station-sangnoksu",
			"facility-elevator-1",
			FacilityReportType.BROKEN,
			"엘리베이터가 멈춰 있습니다.",
			"elevator.jpg",
			"image/jpeg",
			"aW1hZ2UtYnl0ZXM=",
			new BigDecimal("37.3123450"),
			new BigDecimal("126.9876540"),
			"report-0",
			FacilityReportStatus.DUPLICATE,
			LocalDateTime.of(2026, 6, 17, 9, 0),
			LocalDateTime.of(2026, 6, 17, 10, 0),
			"admin-user"
		);
	}
}

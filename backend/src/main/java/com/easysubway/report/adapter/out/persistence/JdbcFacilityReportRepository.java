package com.easysubway.report.adapter.out.persistence;

import com.easysubway.common.domain.PageResult;
import com.easysubway.report.application.port.in.FacilityReportPageRequest;
import com.easysubway.report.application.port.out.DeleteFacilityReportPhotoPort;
import com.easysubway.report.application.port.out.LoadFacilityReportPort;
import com.easysubway.report.application.port.out.SaveFacilityReportPort;
import com.easysubway.report.domain.FacilityReport;
import com.easysubway.report.domain.FacilityReportSummary;
import com.easysubway.report.domain.FacilityReportStatus;
import com.easysubway.report.domain.FacilityReportType;
import com.easysubway.report.domain.RepeatedBrokenFacilityReportSummary;
import com.easysubway.report.domain.ReportProcessingTimeSummary;
import com.easysubway.user.application.port.out.AnonymizeUserFacilityReportPort;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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
	private final DeleteFacilityReportPhotoPort deleteFacilityReportPhotoPort;

	@Autowired
	public JdbcFacilityReportRepository(
		DataSource dataSource,
		DeleteFacilityReportPhotoPort deleteFacilityReportPhotoPort
	) {
		this(new JdbcTemplate(dataSource), deleteFacilityReportPhotoPort);
	}

	JdbcFacilityReportRepository(JdbcTemplate jdbcTemplate) {
		this(jdbcTemplate, objectKey -> {
		});
	}

	JdbcFacilityReportRepository(
		JdbcTemplate jdbcTemplate,
		DeleteFacilityReportPhotoPort deleteFacilityReportPhotoPort
	) {
		this.jdbcTemplate = jdbcTemplate;
		this.databaseDialect = detectDatabaseDialect(jdbcTemplate);
		this.deleteFacilityReportPhotoPort = deleteFacilityReportPhotoPort;
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
						photo_object_key,
						photo_thumbnail_object_key,
						photo_sha256,
						photo_size_bytes,
						latitude,
						longitude,
						duplicate_of_report_id,
						status,
						created_at,
						reviewed_at,
						reviewed_by,
						client_submission_id,
						receipt_token_hash
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
	public Optional<FacilityReport> loadReportByClientSubmissionId(String clientSubmissionId) {
		if (clientSubmissionId == null || clientSubmissionId.isBlank()) {
			return Optional.empty();
		}
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
						photo_object_key,
						photo_thumbnail_object_key,
						photo_sha256,
						photo_size_bytes,
						latitude,
						longitude,
						duplicate_of_report_id,
						status,
						created_at,
						reviewed_at,
						reviewed_by,
						client_submission_id,
						receipt_token_hash
					FROM facility_reports
					WHERE client_submission_id = ?
					""",
				this::mapFacilityReport,
				clientSubmissionId.trim()
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
					photo_object_key,
					photo_thumbnail_object_key,
					photo_sha256,
					photo_size_bytes,
					latitude,
					longitude,
					duplicate_of_report_id,
					status,
					created_at,
					reviewed_at,
					reviewed_by,
					client_submission_id,
					receipt_token_hash
				FROM facility_reports
				ORDER BY created_at DESC, report_id ASC
				""",
			this::mapFacilityReport
		);
	}

	@Override
	public PageResult<FacilityReportSummary> loadUserReportSummaries(
		String userId,
		FacilityReportPageRequest pageRequest
	) {
		List<FacilityReportSummary> summaries = jdbcTemplate.query(
			"""
				SELECT report_id,
					user_id,
					station_id,
					facility_id,
					report_type,
					description,
					CASE
						WHEN photo_file_name IS NOT NULL
							AND photo_content_type IS NOT NULL
							AND photo_object_key IS NOT NULL
						THEN TRUE
						ELSE FALSE
					END AS has_photo,
					latitude,
					longitude,
					duplicate_of_report_id,
					status,
					created_at,
					reviewed_at,
					reviewed_by
				FROM facility_reports
				WHERE user_id = ?
					AND user_id <> ?
				ORDER BY created_at DESC, report_id ASC
				LIMIT ? OFFSET ?
				""",
			this::mapFacilityReportSummary,
			userId,
			FacilityReport.ANONYMIZED_USER_ID,
			pageRequest.limitForHasNext(),
			pageRequest.offset()
		);
		return page(summaries, pageRequest);
	}

	@Override
	public PageResult<FacilityReportSummary> loadReportSummaries(
		FacilityReportStatus status,
		FacilityReportPageRequest pageRequest
	) {
		if (status == null) {
			return loadAllReportSummaries(pageRequest);
		}
		List<FacilityReportSummary> summaries = jdbcTemplate.query(
			"""
				SELECT report_id,
					user_id,
					station_id,
					facility_id,
					report_type,
					description,
					CASE
						WHEN photo_file_name IS NOT NULL
							AND photo_content_type IS NOT NULL
							AND photo_object_key IS NOT NULL
						THEN TRUE
						ELSE FALSE
					END AS has_photo,
					latitude,
					longitude,
					duplicate_of_report_id,
					status,
					created_at,
					reviewed_at,
					reviewed_by
				FROM facility_reports
				WHERE status = ?
				ORDER BY created_at DESC, report_id ASC
				LIMIT ? OFFSET ?
				""",
			this::mapFacilityReportSummary,
			status.name(),
			pageRequest.limitForHasNext(),
			pageRequest.offset()
		);
		return page(summaries, pageRequest);
	}

	@Override
	public Map<FacilityReportStatus, Long> loadReportStatusCounts() {
		return jdbcTemplate.query(
			"""
				SELECT status,
					COUNT(*) AS report_count
				FROM facility_reports
				GROUP BY status
				""",
			resultSet -> {
				Map<FacilityReportStatus, Long> counts = new EnumMap<>(FacilityReportStatus.class);
				while (resultSet.next()) {
					counts.put(
						FacilityReportStatus.valueOf(resultSet.getString("status")),
						resultSet.getLong("report_count")
					);
				}
				return Map.copyOf(counts);
			}
		);
	}

	@Override
	public long countReportsCreatedSince(LocalDateTime cutoff) {
		Long count = jdbcTemplate.queryForObject(
			"""
				SELECT COUNT(*)
				FROM facility_reports
				WHERE created_at >= ?
				""",
			Long.class,
			cutoff
		);
		return count == null ? 0 : count;
	}

	@Override
	public ReportProcessingTimeSummary loadReportProcessingTimeSummary() {
		List<Long> processingMinutes = jdbcTemplate.query(
			"""
				SELECT created_at,
					reviewed_at
				FROM facility_reports
				WHERE reviewed_at IS NOT NULL
				""",
			(resultSet, rowNumber) -> Duration.between(
				resultSet.getTimestamp("created_at").toLocalDateTime(),
				resultSet.getTimestamp("reviewed_at").toLocalDateTime()
			).toMinutes()
		).stream()
			.filter(minutes -> minutes >= 0)
			.toList();
		if (processingMinutes.isEmpty()) {
			return ReportProcessingTimeSummary.empty();
		}
		long averageMinutes = processingMinutes.stream()
			.mapToLong(Long::longValue)
			.sum() / processingMinutes.size();
		return new ReportProcessingTimeSummary(processingMinutes.size(), averageMinutes);
	}

	@Override
	public List<RepeatedBrokenFacilityReportSummary> loadRepeatedBrokenReportFacilities() {
		return jdbcTemplate.query(
			"""
				SELECT station_id,
					facility_id,
					COUNT(*) AS report_count
				FROM facility_reports
				WHERE report_type = ?
				GROUP BY station_id, facility_id
				HAVING COUNT(*) >= 2
				ORDER BY report_count DESC, station_id ASC, facility_id ASC
				""",
			(resultSet, rowNumber) -> new RepeatedBrokenFacilityReportSummary(
				resultSet.getString("station_id"),
				resultSet.getString("facility_id"),
				resultSet.getLong("report_count")
			),
			FacilityReportType.BROKEN.name()
		);
	}

	@Override
	public FacilityReport saveReport(FacilityReport report) {
		upsertReport(report);
		return report;
	}

	@Override
	public Optional<FacilityReport> saveReviewedReportIfStatus(
		FacilityReport report,
		FacilityReportStatus expectedStatus
	) {
		int updatedCount = jdbcTemplate.update(
			"""
				UPDATE facility_reports
				SET user_id = ?,
					station_id = ?,
					facility_id = ?,
					report_type = ?,
					description = ?,
					photo_file_name = ?,
					photo_content_type = ?,
					photo_object_key = ?,
					photo_thumbnail_object_key = ?,
					photo_sha256 = ?,
					photo_size_bytes = ?,
					latitude = ?,
					longitude = ?,
					duplicate_of_report_id = ?,
					status = ?,
					reviewed_at = ?,
					reviewed_by = ?,
					client_submission_id = ?,
					receipt_token_hash = ?
				WHERE report_id = ?
					AND status = ?
				""",
			report.userId(),
			report.stationId(),
			report.facilityId(),
			report.reportType().name(),
			report.description(),
			report.photoFileName(),
			report.photoContentType(),
			report.photoObjectKey(),
			report.photoThumbnailObjectKey(),
			report.photoSha256(),
			report.photoSizeBytes(),
			report.latitude(),
			report.longitude(),
			report.duplicateOfReportId(),
			report.status().name(),
			report.reviewedAt(),
			report.reviewedBy(),
			report.clientSubmissionId(),
			report.receiptTokenHash(),
			report.id(),
			expectedStatus.name()
		);
		if (updatedCount == 0) {
			return Optional.empty();
		}
		return loadReport(report.id());
	}

	@Override
	public int anonymizeFacilityReportsByUserId(String userId) {
		List<String> photoObjectKeys = loadPhotoObjectKeysByUserId(userId);
		int anonymizedCount = jdbcTemplate.update(
			"""
				UPDATE facility_reports
				SET user_id = ?,
					description = ?,
					photo_file_name = NULL,
					photo_content_type = NULL,
					photo_object_key = NULL,
					photo_thumbnail_object_key = NULL,
					photo_sha256 = NULL,
					photo_size_bytes = NULL,
					latitude = NULL,
					longitude = NULL,
					client_submission_id = NULL,
					receipt_token_hash = NULL
				WHERE user_id = ?
				""",
			FacilityReport.ANONYMIZED_USER_ID,
			DELETED_DESCRIPTION,
			userId
		);
		if (anonymizedCount > 0) {
			photoObjectKeys.forEach(deleteFacilityReportPhotoPort::deleteFacilityReportPhoto);
		}
		return anonymizedCount;
	}

	private List<String> loadPhotoObjectKeysByUserId(String userId) {
		return jdbcTemplate.query(
			"""
				SELECT photo_object_key,
					photo_thumbnail_object_key
				FROM facility_reports
				WHERE user_id = ?
					AND (photo_object_key IS NOT NULL OR photo_thumbnail_object_key IS NOT NULL)
				""",
			resultSet -> {
				java.util.ArrayList<String> objectKeys = new java.util.ArrayList<>();
				while (resultSet.next()) {
					addObjectKey(objectKeys, resultSet.getString("photo_object_key"));
					addObjectKey(objectKeys, resultSet.getString("photo_thumbnail_object_key"));
				}
				return List.copyOf(objectKeys);
			},
			userId
		);
	}

	private void addObjectKey(List<String> objectKeys, String objectKey) {
		if (objectKey != null && !objectKey.isBlank()) {
			objectKeys.add(objectKey);
		}
	}

	private void upsertReport(FacilityReport report) {
		if (databaseDialect == DatabaseDialect.H2) {
			if (updateReportPreservingCreatedAt(report) == 0) {
				insertReport(report);
			}
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
					photo_object_key,
					photo_thumbnail_object_key,
					photo_sha256,
					photo_size_bytes,
					latitude,
					longitude,
					duplicate_of_report_id,
					status,
					created_at,
					reviewed_at,
					reviewed_by,
					client_submission_id,
					receipt_token_hash
				)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT (report_id) DO UPDATE
				SET user_id = EXCLUDED.user_id,
					station_id = EXCLUDED.station_id,
					facility_id = EXCLUDED.facility_id,
					report_type = EXCLUDED.report_type,
					description = EXCLUDED.description,
					photo_file_name = EXCLUDED.photo_file_name,
					photo_content_type = EXCLUDED.photo_content_type,
					photo_object_key = EXCLUDED.photo_object_key,
					photo_thumbnail_object_key = EXCLUDED.photo_thumbnail_object_key,
					photo_sha256 = EXCLUDED.photo_sha256,
					photo_size_bytes = EXCLUDED.photo_size_bytes,
					latitude = EXCLUDED.latitude,
					longitude = EXCLUDED.longitude,
					duplicate_of_report_id = EXCLUDED.duplicate_of_report_id,
					status = EXCLUDED.status,
					reviewed_at = EXCLUDED.reviewed_at,
					reviewed_by = EXCLUDED.reviewed_by,
					client_submission_id = EXCLUDED.client_submission_id,
					receipt_token_hash = EXCLUDED.receipt_token_hash
				""",
			reportParameters(report)
		);
	}

	private int updateReportPreservingCreatedAt(FacilityReport report) {
		return jdbcTemplate.update(
			"""
				UPDATE facility_reports
				SET user_id = ?,
					station_id = ?,
					facility_id = ?,
					report_type = ?,
					description = ?,
					photo_file_name = ?,
					photo_content_type = ?,
					photo_object_key = ?,
					photo_thumbnail_object_key = ?,
					photo_sha256 = ?,
					photo_size_bytes = ?,
					latitude = ?,
					longitude = ?,
					duplicate_of_report_id = ?,
					status = ?,
					reviewed_at = ?,
					reviewed_by = ?,
					client_submission_id = ?,
					receipt_token_hash = ?
				WHERE report_id = ?
				""",
			report.userId(),
			report.stationId(),
			report.facilityId(),
			report.reportType().name(),
			report.description(),
			report.photoFileName(),
			report.photoContentType(),
			report.photoObjectKey(),
			report.photoThumbnailObjectKey(),
			report.photoSha256(),
			report.photoSizeBytes(),
			report.latitude(),
			report.longitude(),
			report.duplicateOfReportId(),
			report.status().name(),
			report.reviewedAt(),
			report.reviewedBy(),
			report.clientSubmissionId(),
			report.receiptTokenHash(),
			report.id()
		);
	}

	private void insertReport(FacilityReport report) {
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
					photo_object_key,
					photo_thumbnail_object_key,
					photo_sha256,
					photo_size_bytes,
					latitude,
					longitude,
					duplicate_of_report_id,
					status,
					created_at,
					reviewed_at,
					reviewed_by,
					client_submission_id,
					receipt_token_hash
				)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""",
			reportParameters(report)
		);
	}

	private PageResult<FacilityReportSummary> loadAllReportSummaries(FacilityReportPageRequest pageRequest) {
		List<FacilityReportSummary> summaries = jdbcTemplate.query(
			"""
				SELECT report_id,
					user_id,
					station_id,
					facility_id,
					report_type,
					description,
					CASE
						WHEN photo_file_name IS NOT NULL
							AND photo_content_type IS NOT NULL
							AND photo_object_key IS NOT NULL
						THEN TRUE
						ELSE FALSE
					END AS has_photo,
					latitude,
					longitude,
					duplicate_of_report_id,
					status,
					created_at,
					reviewed_at,
					reviewed_by
				FROM facility_reports
				ORDER BY created_at DESC, report_id ASC
				LIMIT ? OFFSET ?
				""",
			this::mapFacilityReportSummary,
			pageRequest.limitForHasNext(),
			pageRequest.offset()
		);
		return page(summaries, pageRequest);
	}

	private PageResult<FacilityReportSummary> page(
		List<FacilityReportSummary> summaries,
		FacilityReportPageRequest pageRequest
	) {
		boolean hasNext = summaries.size() > pageRequest.size();
		List<FacilityReportSummary> items = hasNext
			? summaries.subList(0, pageRequest.size())
			: summaries;
		return new PageResult<>(items, pageRequest.page(), pageRequest.size(), hasNext);
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
			report.photoObjectKey(),
			report.photoThumbnailObjectKey(),
			report.photoSha256(),
			report.photoSizeBytes(),
			report.latitude(),
			report.longitude(),
			report.duplicateOfReportId(),
			report.status().name(),
			report.createdAt(),
			report.reviewedAt(),
			report.reviewedBy(),
			report.clientSubmissionId(),
			report.receiptTokenHash()
		};
	}

	private FacilityReportSummary mapFacilityReportSummary(ResultSet resultSet, int rowNumber) throws SQLException {
		return new FacilityReportSummary(
			resultSet.getString("report_id"),
			resultSet.getString("user_id"),
			resultSet.getString("station_id"),
			resultSet.getString("facility_id"),
			FacilityReportType.valueOf(resultSet.getString("report_type")),
			resultSet.getString("description"),
			resultSet.getBoolean("has_photo"),
			resultSet.getBigDecimal("latitude"),
			resultSet.getBigDecimal("longitude"),
			resultSet.getString("duplicate_of_report_id"),
			FacilityReportStatus.valueOf(resultSet.getString("status")),
			resultSet.getTimestamp("created_at").toLocalDateTime(),
			timestampOrNull(resultSet, "reviewed_at"),
			resultSet.getString("reviewed_by")
		);
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
			resultSet.getString("photo_object_key"),
			resultSet.getString("photo_thumbnail_object_key"),
			resultSet.getString("photo_sha256"),
			photoSizeBytes(resultSet),
			resultSet.getBigDecimal("latitude"),
			resultSet.getBigDecimal("longitude"),
			resultSet.getString("duplicate_of_report_id"),
			FacilityReportStatus.valueOf(resultSet.getString("status")),
			resultSet.getTimestamp("created_at").toLocalDateTime(),
			timestampOrNull(resultSet, "reviewed_at"),
			resultSet.getString("reviewed_by"),
			resultSet.getString("client_submission_id"),
			resultSet.getString("receipt_token_hash")
		);
	}

	private LocalDateTime timestampOrNull(ResultSet resultSet, String columnLabel) throws SQLException {
		var timestamp = resultSet.getTimestamp(columnLabel);
		return timestamp == null ? null : timestamp.toLocalDateTime();
	}

	private Long photoSizeBytes(ResultSet resultSet) throws SQLException {
		Number sizeBytes = (Number) resultSet.getObject("photo_size_bytes");
		return sizeBytes == null ? null : sizeBytes.longValue();
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

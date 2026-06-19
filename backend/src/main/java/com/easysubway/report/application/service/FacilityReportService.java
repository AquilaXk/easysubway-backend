package com.easysubway.report.application.service;

import com.easysubway.common.domain.PageResult;
import com.easysubway.report.application.port.in.CreateFacilityReportCommand;
import com.easysubway.report.application.port.in.CreatedFacilityReport;
import com.easysubway.report.application.port.in.FacilityReportPageRequest;
import com.easysubway.report.application.port.in.FacilityReportUseCase;
import com.easysubway.report.application.port.in.ReviewFacilityReportCommand;
import com.easysubway.report.application.port.out.LoadFacilityReportPort;
import com.easysubway.report.application.port.out.LoadFacilityReportReviewAuditPort;
import com.easysubway.report.application.port.out.SaveFacilityReportPort;
import com.easysubway.report.application.port.out.SaveFacilityReportReviewAuditPort;
import com.easysubway.report.application.port.out.StoreFacilityReportPhotoPort;
import com.easysubway.report.application.port.out.StoreFacilityReportPhotoPort.StoreFacilityReportPhotoCommand;
import com.easysubway.report.application.port.out.StoreFacilityReportPhotoPort.StoredFacilityReportPhoto;
import com.easysubway.report.domain.FacilityReport;
import com.easysubway.report.domain.FacilityReportNotFoundException;
import com.easysubway.report.domain.FacilityReportReviewAudit;
import com.easysubway.report.domain.FacilityReportReviewConflictException;
import com.easysubway.report.domain.FacilityReportReviewDecision;
import com.easysubway.report.domain.FacilityReportSummary;
import com.easysubway.report.domain.FacilityReportStatus;
import com.easysubway.report.domain.FacilityReportTargetNotFoundException;
import com.easysubway.report.domain.FacilityReportType;
import com.easysubway.report.domain.InvalidFacilityReportException;
import com.easysubway.report.domain.RepeatedBrokenFacilityReportSummary;
import com.easysubway.report.domain.ReportProcessingTimeSummary;
import com.easysubway.notification.application.port.in.FacilityStatusAlertUseCase;
import com.easysubway.notification.application.port.in.FacilityStatusChangedAlertCommand;
import com.easysubway.notification.application.port.in.ReportStatusAlertUseCase;
import com.easysubway.notification.application.port.in.ReportStatusChangedAlertCommand;
import com.easysubway.transit.application.port.out.LoadTransitMasterPort;
import com.easysubway.transit.application.port.out.SaveAccessibilityFacilityStatusPort;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import com.easysubway.transit.domain.Station;
import com.easysubway.transit.domain.StationNotFoundException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FacilityReportService implements FacilityReportUseCase {

	private static final Logger log = LoggerFactory.getLogger(FacilityReportService.class);

	private final LoadTransitMasterPort loadTransitMasterPort;
	private final SaveAccessibilityFacilityStatusPort saveAccessibilityFacilityStatusPort;
	private final LoadFacilityReportPort loadFacilityReportPort;
	private final SaveFacilityReportPort saveFacilityReportPort;
	private final StoreFacilityReportPhotoPort storeFacilityReportPhotoPort;
	private final LoadFacilityReportReviewAuditPort loadFacilityReportReviewAuditPort;
	private final FacilityStatusAlertUseCase facilityStatusAlertUseCase;
	private final ReportStatusAlertUseCase reportStatusAlertUseCase;
	private final SaveFacilityReportReviewAuditPort saveFacilityReportReviewAuditPort;
	private final FacilityReportPhotoProcessor photoProcessor;
	private final Clock clock;
	private final FacilityReportReceiptTokens receiptTokens;

	@Autowired
	public FacilityReportService(
		LoadTransitMasterPort loadTransitMasterPort,
		SaveAccessibilityFacilityStatusPort saveAccessibilityFacilityStatusPort,
		LoadFacilityReportPort loadFacilityReportPort,
		SaveFacilityReportPort saveFacilityReportPort,
		StoreFacilityReportPhotoPort storeFacilityReportPhotoPort,
		FacilityStatusAlertUseCase facilityStatusAlertUseCase,
		ReportStatusAlertUseCase reportStatusAlertUseCase,
		SaveFacilityReportReviewAuditPort saveFacilityReportReviewAuditPort,
		LoadFacilityReportReviewAuditPort loadFacilityReportReviewAuditPort,
		@Value("${easysubway.report.receipt-token-pepper:local-dev-report-receipt-pepper}") String receiptTokenPepper
	) {
		this(
			loadTransitMasterPort,
			saveAccessibilityFacilityStatusPort,
			loadFacilityReportPort,
			saveFacilityReportPort,
			storeFacilityReportPhotoPort,
			facilityStatusAlertUseCase,
			reportStatusAlertUseCase,
			saveFacilityReportReviewAuditPort,
			loadFacilityReportReviewAuditPort,
			Clock.systemDefaultZone(),
			receiptTokenPepper
		);
	}

	public FacilityReportService(
		LoadTransitMasterPort loadTransitMasterPort,
		SaveAccessibilityFacilityStatusPort saveAccessibilityFacilityStatusPort,
		LoadFacilityReportPort loadFacilityReportPort,
		SaveFacilityReportPort saveFacilityReportPort,
		FacilityStatusAlertUseCase facilityStatusAlertUseCase
	) {
		this(
			loadTransitMasterPort,
			saveAccessibilityFacilityStatusPort,
			loadFacilityReportPort,
			saveFacilityReportPort,
			defaultPhotoStoragePort(),
			facilityStatusAlertUseCase,
			command -> {
			},
			audit -> audit,
			reportId -> List.of(),
			Clock.systemDefaultZone()
		);
	}

	public FacilityReportService(
		LoadTransitMasterPort loadTransitMasterPort,
		SaveAccessibilityFacilityStatusPort saveAccessibilityFacilityStatusPort,
		LoadFacilityReportPort loadFacilityReportPort,
		SaveFacilityReportPort saveFacilityReportPort,
		Clock clock
	) {
		this(
			loadTransitMasterPort,
			saveAccessibilityFacilityStatusPort,
			loadFacilityReportPort,
			saveFacilityReportPort,
			defaultPhotoStoragePort(),
			command -> {
			},
			command -> {
			},
			audit -> audit,
			reportId -> List.of(),
			clock
		);
	}

	public FacilityReportService(
		LoadTransitMasterPort loadTransitMasterPort,
		SaveAccessibilityFacilityStatusPort saveAccessibilityFacilityStatusPort,
		LoadFacilityReportPort loadFacilityReportPort,
		SaveFacilityReportPort saveFacilityReportPort,
		FacilityStatusAlertUseCase facilityStatusAlertUseCase,
		ReportStatusAlertUseCase reportStatusAlertUseCase,
		SaveFacilityReportReviewAuditPort saveFacilityReportReviewAuditPort,
		LoadFacilityReportReviewAuditPort loadFacilityReportReviewAuditPort,
		Clock clock
	) {
		this(
			loadTransitMasterPort,
			saveAccessibilityFacilityStatusPort,
			loadFacilityReportPort,
			saveFacilityReportPort,
			defaultPhotoStoragePort(),
			facilityStatusAlertUseCase,
			reportStatusAlertUseCase,
			saveFacilityReportReviewAuditPort,
			loadFacilityReportReviewAuditPort,
			clock
		);
	}

	public FacilityReportService(
		LoadTransitMasterPort loadTransitMasterPort,
		SaveAccessibilityFacilityStatusPort saveAccessibilityFacilityStatusPort,
		LoadFacilityReportPort loadFacilityReportPort,
		SaveFacilityReportPort saveFacilityReportPort,
		FacilityStatusAlertUseCase facilityStatusAlertUseCase,
		Clock clock
	) {
		this(
			loadTransitMasterPort,
			saveAccessibilityFacilityStatusPort,
			loadFacilityReportPort,
			saveFacilityReportPort,
			defaultPhotoStoragePort(),
			facilityStatusAlertUseCase,
			command -> {
			},
			audit -> audit,
			reportId -> List.of(),
			clock
		);
	}

	public FacilityReportService(
		LoadTransitMasterPort loadTransitMasterPort,
		SaveAccessibilityFacilityStatusPort saveAccessibilityFacilityStatusPort,
		LoadFacilityReportPort loadFacilityReportPort,
		SaveFacilityReportPort saveFacilityReportPort,
		FacilityStatusAlertUseCase facilityStatusAlertUseCase,
		ReportStatusAlertUseCase reportStatusAlertUseCase,
		Clock clock
	) {
		this(
			loadTransitMasterPort,
			saveAccessibilityFacilityStatusPort,
			loadFacilityReportPort,
			saveFacilityReportPort,
			defaultPhotoStoragePort(),
			facilityStatusAlertUseCase,
			reportStatusAlertUseCase,
			audit -> audit,
			reportId -> List.of(),
			clock
		);
	}

	public FacilityReportService(
		LoadTransitMasterPort loadTransitMasterPort,
		SaveAccessibilityFacilityStatusPort saveAccessibilityFacilityStatusPort,
		LoadFacilityReportPort loadFacilityReportPort,
		SaveFacilityReportPort saveFacilityReportPort,
		FacilityStatusAlertUseCase facilityStatusAlertUseCase,
		ReportStatusAlertUseCase reportStatusAlertUseCase,
		SaveFacilityReportReviewAuditPort saveFacilityReportReviewAuditPort,
		Clock clock
	) {
		this(
			loadTransitMasterPort,
			saveAccessibilityFacilityStatusPort,
			loadFacilityReportPort,
			saveFacilityReportPort,
			defaultPhotoStoragePort(),
			facilityStatusAlertUseCase,
			reportStatusAlertUseCase,
			saveFacilityReportReviewAuditPort,
			reportId -> List.of(),
			clock
		);
	}

	public FacilityReportService(
		LoadTransitMasterPort loadTransitMasterPort,
		SaveAccessibilityFacilityStatusPort saveAccessibilityFacilityStatusPort,
		LoadFacilityReportPort loadFacilityReportPort,
		SaveFacilityReportPort saveFacilityReportPort,
		StoreFacilityReportPhotoPort storeFacilityReportPhotoPort,
		FacilityStatusAlertUseCase facilityStatusAlertUseCase,
		ReportStatusAlertUseCase reportStatusAlertUseCase,
		SaveFacilityReportReviewAuditPort saveFacilityReportReviewAuditPort,
		LoadFacilityReportReviewAuditPort loadFacilityReportReviewAuditPort,
		Clock clock
	) {
		this(
			loadTransitMasterPort,
			saveAccessibilityFacilityStatusPort,
			loadFacilityReportPort,
			saveFacilityReportPort,
			storeFacilityReportPhotoPort,
			facilityStatusAlertUseCase,
			reportStatusAlertUseCase,
			saveFacilityReportReviewAuditPort,
			loadFacilityReportReviewAuditPort,
			clock,
			"local-dev-report-receipt-pepper"
		);
	}

	FacilityReportService(
		LoadTransitMasterPort loadTransitMasterPort,
		SaveAccessibilityFacilityStatusPort saveAccessibilityFacilityStatusPort,
		LoadFacilityReportPort loadFacilityReportPort,
		SaveFacilityReportPort saveFacilityReportPort,
		StoreFacilityReportPhotoPort storeFacilityReportPhotoPort,
		FacilityStatusAlertUseCase facilityStatusAlertUseCase,
		ReportStatusAlertUseCase reportStatusAlertUseCase,
		SaveFacilityReportReviewAuditPort saveFacilityReportReviewAuditPort,
		LoadFacilityReportReviewAuditPort loadFacilityReportReviewAuditPort,
		Clock clock,
		String receiptTokenPepper
	) {
		this.loadTransitMasterPort = loadTransitMasterPort;
		this.saveAccessibilityFacilityStatusPort = saveAccessibilityFacilityStatusPort;
		this.loadFacilityReportPort = loadFacilityReportPort;
		this.saveFacilityReportPort = saveFacilityReportPort;
		this.storeFacilityReportPhotoPort = storeFacilityReportPhotoPort;
		this.loadFacilityReportReviewAuditPort = loadFacilityReportReviewAuditPort;
		this.facilityStatusAlertUseCase = facilityStatusAlertUseCase;
		this.reportStatusAlertUseCase = reportStatusAlertUseCase;
		this.saveFacilityReportReviewAuditPort = saveFacilityReportReviewAuditPort;
		this.photoProcessor = new FacilityReportPhotoProcessor();
		this.clock = clock;
		this.receiptTokens = new FacilityReportReceiptTokens(receiptTokenPepper);
	}

	@Override
	public FacilityReport createReport(CreateFacilityReportCommand command) {
		return createReport(command, null);
	}

	@Override
	public CreatedFacilityReport createReportWithReceipt(CreateFacilityReportCommand command) {
		requireClientSubmissionId(command.clientSubmissionId());
		FacilityReportReceiptTokens.IssuedReceiptToken receiptToken = receiptTokens.issue(command.clientSubmissionId());
		Optional<FacilityReport> existing = loadFacilityReportPort.loadReportByClientSubmissionId(
			command.clientSubmissionId().trim()
		);
		if (existing.isPresent()) {
			return new CreatedFacilityReport(existing.get(), receiptToken.token());
		}
		return new CreatedFacilityReport(createReport(command, receiptToken.hash()), receiptToken.token());
	}

	private FacilityReport createReport(CreateFacilityReportCommand command, String receiptTokenHash) {
		requireReporter(command, receiptTokenHash);
		requireReportType(command);
		requireActiveStation(command.stationId());
		// 신고 대상 시설이 요청한 역에 속해야 다른 역 시설 상태가 잘못 갱신되는 일을 막을 수 있다.
		requireFacilityInStation(command.stationId(), command.facilityId());
		String reportId = "report-" + UUID.randomUUID();
		FacilityReportPhotoAttachment photo = preparePhoto(command);
		StoredFacilityReportPhoto storedPhoto = photo == null ? null : storePhoto(reportId, photo);
		String storedUserId = hasText(command.userId())
			? command.userId()
			: "receipt:" + receiptTokenHash.substring(0, 16);
		String photoObjectKey = hasText(command.photoObjectKey())
			? command.photoObjectKey().trim()
			: storedPhoto == null ? null : storedPhoto.objectKey();
		String photoSha256 = hasText(command.photoSha256())
			? command.photoSha256().trim()
			: photo == null ? null : photo.sha256();
		Long photoSizeBytes = command.photoSizeBytes() == null
			? photo == null ? null : photo.sizeBytes()
			: command.photoSizeBytes();
		String photoFileName = photo == null
			? hasText(command.photoFileName()) ? command.photoFileName().trim() : null
			: photo.fileName();
		String photoContentType = photo == null
			? hasText(command.photoContentType()) ? command.photoContentType().trim() : null
			: photo.contentType();

		FacilityReport report = new FacilityReport(
			reportId,
			storedUserId,
			command.stationId(),
			command.facilityId(),
			command.reportType(),
			command.description(),
			photoFileName,
			photoContentType,
			photoObjectKey,
			storedPhoto == null ? null : storedPhoto.thumbnailObjectKey(),
			photoSha256,
			photoSizeBytes,
			command.latitude(),
			command.longitude(),
			null,
			FacilityReportStatus.SUBMITTED,
			LocalDateTime.now(clock),
			null,
			null,
			hasText(command.clientSubmissionId()) ? command.clientSubmissionId().trim() : null,
			receiptTokenHash
		);

		return saveFacilityReportPort.saveReport(report);
	}

	@Override
	public FacilityReport getReport(String reportId) {
		return loadFacilityReportPort.loadReport(reportId)
			.orElseThrow(FacilityReportNotFoundException::new);
	}

	@Override
	public FacilityReport getUserReport(String reportId, String userId) {
		FacilityReport report = getReport(reportId);
		requireReportOwner(report, userId);
		return report;
	}

	@Override
	public FacilityReport getReportByReceiptToken(String reportId, String receiptToken) {
		FacilityReport report = getReport(reportId);
		if (!receiptTokens.matches(receiptToken, report.receiptTokenHash())) {
			throw new FacilityReportNotFoundException();
		}
		return report;
	}

	@Override
	public List<FacilityReport> listUserReports(String userId) {
		return sortedReports()
			.stream()
			// 익명화된 신고는 운영 검수 이력만 보존하므로 어떤 사용자 계정의 내역에도 다시 연결하지 않는다.
			.filter(report -> !report.isAnonymizedUserData())
			.filter(report -> userId.equals(report.userId()))
			.toList();
	}

	@Override
	public PageResult<FacilityReportSummary> listUserReportSummaries(
		String userId,
		FacilityReportPageRequest pageRequest
	) {
		return loadFacilityReportPort.loadUserReportSummaries(userId, pageRequest);
	}

	@Override
	public List<FacilityReport> listReports(FacilityReportStatus status) {
		return sortedReports()
			.stream()
			.filter(report -> status == null || report.status() == status)
			.toList();
	}

	@Override
	public PageResult<FacilityReportSummary> listReportSummaries(
		FacilityReportStatus status,
		FacilityReportPageRequest pageRequest
	) {
		return loadFacilityReportPort.loadReportSummaries(status, pageRequest);
	}

	@Override
	public Map<FacilityReportStatus, Long> countReportsByStatus() {
		return loadFacilityReportPort.loadReportStatusCounts();
	}

	@Override
	public long countReportsCreatedSince(LocalDateTime cutoff) {
		return loadFacilityReportPort.countReportsCreatedSince(cutoff);
	}

	@Override
	public ReportProcessingTimeSummary summarizeReportProcessingTime() {
		return loadFacilityReportPort.loadReportProcessingTimeSummary();
	}

	@Override
	public List<RepeatedBrokenFacilityReportSummary> listRepeatedBrokenReportFacilities() {
		return loadFacilityReportPort.loadRepeatedBrokenReportFacilities();
	}

	@Override
	@Transactional
	public FacilityReport reviewReport(ReviewFacilityReportCommand command) {
		requireReviewDecision(command);
		requireReviewer(command);

		FacilityReport report = getReport(command.reportId());
		requireReviewableStatus(report);
		String duplicateOfReportId = resolveDuplicateOfReportId(command, report);
		FacilityReport reviewed = new FacilityReport(
			report.id(),
			report.userId(),
			report.stationId(),
			report.facilityId(),
			report.reportType(),
			report.description(),
			report.photoFileName(),
			report.photoContentType(),
			report.photoObjectKey(),
			report.photoThumbnailObjectKey(),
			report.photoSha256(),
			report.photoSizeBytes(),
			report.latitude(),
			report.longitude(),
			duplicateOfReportId,
			toStatus(command.decision()),
			report.createdAt(),
			LocalDateTime.now(clock),
			command.reviewedBy(),
			report.clientSubmissionId(),
			report.receiptTokenHash()
		);

		// 감사 로그 저장 실패가 신고 상태 변경을 남기지 않도록 같은 트랜잭션에서 먼저 기록한다.
		saveFacilityReportReviewAuditPort.saveAudit(new FacilityReportReviewAudit(
			"audit-" + UUID.randomUUID(),
			reviewed.id(),
			command.reviewedBy(),
			command.decision(),
			report.status(),
			reviewed.status(),
			reviewed.reviewedAt()
		));
		FacilityReport saved = saveFacilityReportPort
			.saveReviewedReportIfStatus(reviewed, FacilityReportStatus.SUBMITTED)
			.orElseThrow(FacilityReportReviewConflictException::new);
		// 같은 결과로 재검수한 경우 사용자가 중복 처리 알림을 받지 않도록 상태 변경만 알린다.
		if (report.status() != saved.status()) {
			alertReportStatusChanged(saved);
		}
		// 승인된 상태 신고만 실제 시설 운영 상태에 반영한다.
		applyAcceptedReportToFacilityStatus(report, command.decision());
		return saved;
	}

	@Override
	public FacilityReport confirmReportResult(String reportId, String userId) {
		FacilityReport report = getReport(reportId);
		requireReportOwner(report, userId);
		requireConfirmableStatus(report);
		if (report.status() == FacilityReportStatus.RESOLVED) {
			return report;
		}
		return saveFacilityReportPort.saveReport(new FacilityReport(
			report.id(),
			report.userId(),
			report.stationId(),
			report.facilityId(),
			report.reportType(),
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
			FacilityReportStatus.RESOLVED,
			report.createdAt(),
			report.reviewedAt(),
			report.reviewedBy(),
			report.clientSubmissionId(),
			report.receiptTokenHash()
		));
	}

	@Override
	public List<FacilityReportReviewAudit> listReviewAudits(String reportId) {
		getReport(reportId);
		return loadFacilityReportReviewAuditPort.loadAuditsByReportId(reportId)
			.stream()
			.sorted(Comparator.comparing(FacilityReportReviewAudit::createdAt))
			.toList();
	}

	private void requireReporter(CreateFacilityReportCommand command, String receiptTokenHash) {
		if ((command.userId() == null || command.userId().isBlank()) && !hasText(receiptTokenHash)) {
			throw new InvalidFacilityReportException("사용자 식별자가 필요합니다.");
		}
	}

	private void requireClientSubmissionId(String clientSubmissionId) {
		if (!hasText(clientSubmissionId)) {
			throw new InvalidFacilityReportException("신고 제출 식별자가 필요합니다.");
		}
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	private FacilityReportPhotoAttachment preparePhoto(CreateFacilityReportCommand command) {
		if (hasText(command.photoObjectKey())) {
			requireObjectPhotoMetadata(command);
			return null;
		}
		if (!photoProcessor.hasAnyPhotoField(command.photoFileName(), command.photoContentType(), command.photoDataBase64())) {
			return null;
		}
		return photoProcessor.process(command.photoFileName(), command.photoContentType(), command.photoDataBase64());
	}

	private void requireObjectPhotoMetadata(CreateFacilityReportCommand command) {
		if (!hasText(command.photoFileName())
			|| !hasText(command.photoContentType())
			|| !hasText(command.photoSha256())
			|| command.photoSizeBytes() == null) {
			throw new InvalidFacilityReportException("사진 첨부 정보를 확인해야 합니다.");
		}
		if (!command.photoSha256().trim().matches("[0-9a-f]{64}")) {
			throw new InvalidFacilityReportException("사진 첨부 정보를 확인해야 합니다.");
		}
		if (command.photoSizeBytes() < 1 || command.photoSizeBytes() > 900L * 1024L) {
			throw new InvalidFacilityReportException("사진 파일 크기를 줄여야 합니다.");
		}
	}

	private StoredFacilityReportPhoto storePhoto(String reportId, FacilityReportPhotoAttachment photo) {
		return storeFacilityReportPhotoPort.storeFacilityReportPhoto(new StoreFacilityReportPhotoCommand(
			reportId,
			photo.fileName(),
			photo.contentType(),
			photo.storedBytes(),
			photo.thumbnailBytes(),
			photo.sha256(),
			photo.sizeBytes()
		));
	}

	private List<FacilityReport> sortedReports() {
		// 사용자 화면과 관리자 화면 모두 최신 처리 상태를 먼저 보도록 같은 정렬 기준을 쓴다.
		return loadFacilityReportPort.loadReports()
			.stream()
			.sorted(Comparator.comparing(FacilityReport::createdAt).reversed())
			.toList();
	}

	private void alertReportStatusChanged(FacilityReport saved) {
		try {
			reportStatusAlertUseCase.alertReportStatusChanged(
				new ReportStatusChangedAlertCommand(saved.userId(), saved.id(), saved.status())
			);
		} catch (RuntimeException exception) {
			// 알림은 저장 이후 부수 효과이므로 실패해도 검수 결과 응답과 시설 상태 반영을 막지 않는다.
			log.warn(
				"신고 처리 결과 알림 발송에 실패했습니다. reportId={}, userId={}, status={}",
				saved.id(),
				saved.userId(),
				saved.status(),
				exception
			);
		}
	}

	private void requireReportType(CreateFacilityReportCommand command) {
		if (command.reportType() == null) {
			throw new InvalidFacilityReportException("신고 유형을 선택해야 합니다.");
		}
	}

	private void requireReviewDecision(ReviewFacilityReportCommand command) {
		if (command.decision() == null) {
			throw new InvalidFacilityReportException("검수 결과를 선택해야 합니다.");
		}
	}

	private void requireReviewer(ReviewFacilityReportCommand command) {
		if (command.reviewedBy() == null || command.reviewedBy().isBlank()) {
			throw new InvalidFacilityReportException("검수자 식별자가 필요합니다.");
		}
	}

	private void requireReportOwner(FacilityReport report, String userId) {
		if (userId == null || userId.isBlank() || !userId.equals(report.userId())) {
			throw new FacilityReportNotFoundException();
		}
	}

	private void requireConfirmableStatus(FacilityReport report) {
		if (report.status() == FacilityReportStatus.ACCEPTED
			|| report.status() == FacilityReportStatus.REJECTED
			|| report.status() == FacilityReportStatus.RESOLVED) {
			return;
		}
		throw new InvalidFacilityReportException("검수 완료된 신고만 확인할 수 있습니다.");
	}

	private void requireReviewableStatus(FacilityReport report) {
		if (report.status() == FacilityReportStatus.SUBMITTED) {
			return;
		}
		throw new FacilityReportReviewConflictException();
	}

	private String resolveDuplicateOfReportId(ReviewFacilityReportCommand command, FacilityReport report) {
		if (command.decision() != FacilityReportReviewDecision.MARK_DUPLICATE) {
			return null;
		}
		String duplicateOfReportId = normalizeDuplicateOfReportId(command.duplicateOfReportId());
		if (duplicateOfReportId == null || duplicateOfReportId.equals(report.id())) {
			throw new InvalidFacilityReportException("기준 신고를 확인해야 합니다.");
		}
		getReport(duplicateOfReportId);
		return duplicateOfReportId;
	}

	private String normalizeDuplicateOfReportId(String duplicateOfReportId) {
		return hasText(duplicateOfReportId) ? duplicateOfReportId.trim() : null;
	}

	private void requireActiveStation(String stationId) {
		loadTransitMasterPort.loadStations()
			.stream()
			.filter(Station::active)
			.filter(station -> station.id().equals(stationId))
			.findFirst()
			.orElseThrow(StationNotFoundException::new);
	}

	private void requireFacilityInStation(String stationId, String facilityId) {
		boolean exists = loadTransitMasterPort.loadAccessibilityFacilities()
			.stream()
			.anyMatch(facility -> facility.id().equals(facilityId) && facility.stationId().equals(stationId));

		if (!exists) {
			throw new FacilityReportTargetNotFoundException();
		}
	}

	private FacilityReportStatus toStatus(FacilityReportReviewDecision decision) {
		// 외부 요청의 검수 결정값을 내부 신고 상태로 한 곳에서만 변환한다.
		return switch (decision) {
			case ACCEPT -> FacilityReportStatus.ACCEPTED;
			case REJECT -> FacilityReportStatus.REJECTED;
			case MARK_DUPLICATE -> FacilityReportStatus.DUPLICATE;
		};
	}

	private void applyAcceptedReportToFacilityStatus(FacilityReport report, FacilityReportReviewDecision decision) {
		if (decision != FacilityReportReviewDecision.ACCEPT) {
			return;
		}

		toFacilityStatus(report.reportType()).ifPresent(status -> {
			boolean statusChanged = isFacilityStatusChanged(report.facilityId(), status);
			saveAccessibilityFacilityStatusPort.saveFacilityStatus(
				report.facilityId(),
				status,
				LocalDate.now(clock)
			);
			if (!statusChanged) {
				return;
			}
			// 신고 승인과 관리자 직접 수정은 같은 알림 정책을 사용해야 사용자 경험이 일관된다.
			// 현재는 outbox 생성까지 같은 요청에서 처리하며, 외부 push 발송 어댑터는 별도 재시도/트랜잭션 경계를 둔다.
			facilityStatusAlertUseCase.alertFacilityStatusChanged(
				new FacilityStatusChangedAlertCommand(report.facilityId(), status)
			);
		});
	}

	private boolean isFacilityStatusChanged(String facilityId, AccessibilityFacilityStatus status) {
		return loadTransitMasterPort.loadAccessibilityFacilities()
			.stream()
			.filter(facility -> facility.id().equals(facilityId))
			.findFirst()
			.map(facility -> facility.status() != status)
			.orElseThrow(FacilityReportTargetNotFoundException::new);
	}

	private Optional<AccessibilityFacilityStatus> toFacilityStatus(FacilityReportType reportType) {
		return switch (reportType) {
			case BROKEN -> Optional.of(AccessibilityFacilityStatus.BROKEN);
			case UNDER_CONSTRUCTION -> Optional.of(AccessibilityFacilityStatus.UNDER_CONSTRUCTION);
			case CLOSED -> Optional.of(AccessibilityFacilityStatus.CLOSED);
			case RECOVERED -> Optional.of(AccessibilityFacilityStatus.NORMAL);
			case LOCATION_WRONG, INFORMATION_WRONG -> Optional.empty();
		};
	}

	private static StoreFacilityReportPhotoPort defaultPhotoStoragePort() {
		return command -> new StoredFacilityReportPhoto(
			"facility-reports/%s/%s".formatted(command.reportId(), command.sha256()),
			"facility-reports/%s/%s-thumbnail".formatted(command.reportId(), command.sha256())
		);
	}
}

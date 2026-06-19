package com.easysubway.report.application.service;

import com.easysubway.report.application.port.in.CreateFacilityReportCommand;
import com.easysubway.report.application.port.in.FacilityReportUseCase;
import com.easysubway.report.application.port.in.ReviewFacilityReportCommand;
import com.easysubway.report.application.port.out.LoadFacilityReportPort;
import com.easysubway.report.application.port.out.LoadFacilityReportReviewAuditPort;
import com.easysubway.report.application.port.out.SaveFacilityReportPort;
import com.easysubway.report.application.port.out.SaveFacilityReportReviewAuditPort;
import com.easysubway.report.domain.FacilityReport;
import com.easysubway.report.domain.FacilityReportNotFoundException;
import com.easysubway.report.domain.FacilityReportReviewAudit;
import com.easysubway.report.domain.FacilityReportReviewDecision;
import com.easysubway.report.domain.FacilityReportStatus;
import com.easysubway.report.domain.FacilityReportTargetNotFoundException;
import com.easysubway.report.domain.FacilityReportType;
import com.easysubway.report.domain.InvalidFacilityReportException;
import com.easysubway.report.domain.RepeatedBrokenFacilityReportSummary;
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
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class FacilityReportService implements FacilityReportUseCase {

	private static final Logger log = LoggerFactory.getLogger(FacilityReportService.class);
	private static final int MAX_PHOTO_BYTES = 900 * 1024;
	private static final int MAX_PHOTO_BASE64_CHARS = ((MAX_PHOTO_BYTES + 2) / 3) * 4;
	private static final Set<String> ALLOWED_PHOTO_CONTENT_TYPES = Set.of(
		"image/jpeg",
		"image/png",
		"image/webp"
	);

	private final LoadTransitMasterPort loadTransitMasterPort;
	private final SaveAccessibilityFacilityStatusPort saveAccessibilityFacilityStatusPort;
	private final LoadFacilityReportPort loadFacilityReportPort;
	private final SaveFacilityReportPort saveFacilityReportPort;
	private final LoadFacilityReportReviewAuditPort loadFacilityReportReviewAuditPort;
	private final FacilityStatusAlertUseCase facilityStatusAlertUseCase;
	private final ReportStatusAlertUseCase reportStatusAlertUseCase;
	private final SaveFacilityReportReviewAuditPort saveFacilityReportReviewAuditPort;
	private final Clock clock;

	@Autowired
	public FacilityReportService(
		LoadTransitMasterPort loadTransitMasterPort,
		SaveAccessibilityFacilityStatusPort saveAccessibilityFacilityStatusPort,
		LoadFacilityReportPort loadFacilityReportPort,
		SaveFacilityReportPort saveFacilityReportPort,
		FacilityStatusAlertUseCase facilityStatusAlertUseCase,
		ReportStatusAlertUseCase reportStatusAlertUseCase,
		SaveFacilityReportReviewAuditPort saveFacilityReportReviewAuditPort,
		LoadFacilityReportReviewAuditPort loadFacilityReportReviewAuditPort
	) {
		this(
			loadTransitMasterPort,
			saveAccessibilityFacilityStatusPort,
			loadFacilityReportPort,
			saveFacilityReportPort,
			facilityStatusAlertUseCase,
			reportStatusAlertUseCase,
			saveFacilityReportReviewAuditPort,
			loadFacilityReportReviewAuditPort,
			Clock.systemDefaultZone()
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
		Clock clock
	) {
		this(
			loadTransitMasterPort,
			saveAccessibilityFacilityStatusPort,
			loadFacilityReportPort,
			saveFacilityReportPort,
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
		FacilityStatusAlertUseCase facilityStatusAlertUseCase,
		ReportStatusAlertUseCase reportStatusAlertUseCase,
		SaveFacilityReportReviewAuditPort saveFacilityReportReviewAuditPort,
		LoadFacilityReportReviewAuditPort loadFacilityReportReviewAuditPort,
		Clock clock
	) {
		this.loadTransitMasterPort = loadTransitMasterPort;
		this.saveAccessibilityFacilityStatusPort = saveAccessibilityFacilityStatusPort;
		this.loadFacilityReportPort = loadFacilityReportPort;
		this.saveFacilityReportPort = saveFacilityReportPort;
		this.loadFacilityReportReviewAuditPort = loadFacilityReportReviewAuditPort;
		this.facilityStatusAlertUseCase = facilityStatusAlertUseCase;
		this.reportStatusAlertUseCase = reportStatusAlertUseCase;
		this.saveFacilityReportReviewAuditPort = saveFacilityReportReviewAuditPort;
		this.clock = clock;
	}

	@Override
	public FacilityReport createReport(CreateFacilityReportCommand command) {
		requireReporter(command);
		requireReportType(command);
		requireActiveStation(command.stationId());
		// 신고 대상 시설이 요청한 역에 속해야 다른 역 시설 상태가 잘못 갱신되는 일을 막을 수 있다.
		requireFacilityInStation(command.stationId(), command.facilityId());
		validatePhotoAttachment(command);
		String photoFileName = normalizePhotoFileName(command.photoFileName());
		String photoContentType = normalizePhotoContentType(command.photoContentType());
		String photoDataBase64 = normalizePhotoDataBase64(command.photoDataBase64());

		FacilityReport report = new FacilityReport(
			"report-" + UUID.randomUUID(),
			command.userId(),
			command.stationId(),
			command.facilityId(),
			command.reportType(),
			command.description(),
			photoFileName,
			photoContentType,
			photoDataBase64,
			command.latitude(),
			command.longitude(),
			null,
			FacilityReportStatus.SUBMITTED,
			LocalDateTime.now(clock),
			null,
			null
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
	public List<FacilityReport> listUserReports(String userId) {
		return sortedReports()
			.stream()
			// 익명화된 신고는 운영 검수 이력만 보존하므로 어떤 사용자 계정의 내역에도 다시 연결하지 않는다.
			.filter(report -> !report.isAnonymizedUserData())
			.filter(report -> userId.equals(report.userId()))
			.toList();
	}

	@Override
	public List<FacilityReport> listReports(FacilityReportStatus status) {
		return sortedReports()
			.stream()
			.filter(report -> status == null || report.status() == status)
			.toList();
	}

	@Override
	public Map<FacilityReportStatus, Long> countReportsByStatus() {
		return loadFacilityReportPort.loadReportStatusCounts();
	}

	@Override
	public List<RepeatedBrokenFacilityReportSummary> listRepeatedBrokenReportFacilities() {
		return loadFacilityReportPort.loadRepeatedBrokenReportFacilities();
	}

	@Override
	public FacilityReport reviewReport(ReviewFacilityReportCommand command) {
		requireReviewDecision(command);
		requireReviewer(command);

		FacilityReport report = getReport(command.reportId());
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
			report.photoDataBase64(),
			report.latitude(),
			report.longitude(),
			duplicateOfReportId,
			toStatus(command.decision()),
			report.createdAt(),
			LocalDateTime.now(clock),
			command.reviewedBy()
		);

		FacilityReport saved = saveFacilityReportPort.saveReport(reviewed);
		// 신고 상태 변경과 별개로 관리자 검수 행동 자체를 추적할 수 있게 별도 감사 로그를 남긴다.
		saveFacilityReportReviewAuditPort.saveAudit(new FacilityReportReviewAudit(
			"audit-" + UUID.randomUUID(),
			saved.id(),
			command.reviewedBy(),
			command.decision(),
			report.status(),
			saved.status(),
			saved.reviewedAt()
		));
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
			report.photoDataBase64(),
			report.latitude(),
			report.longitude(),
			report.duplicateOfReportId(),
			FacilityReportStatus.RESOLVED,
			report.createdAt(),
			report.reviewedAt(),
			report.reviewedBy()
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

	private void requireReporter(CreateFacilityReportCommand command) {
		if (command.userId() == null || command.userId().isBlank()) {
			throw new InvalidFacilityReportException("사용자 식별자가 필요합니다.");
		}
	}

	private void validatePhotoAttachment(CreateFacilityReportCommand command) {
		boolean hasAnyPhotoField = hasText(command.photoFileName())
			|| hasText(command.photoContentType())
			|| hasText(command.photoDataBase64());
		if (!hasAnyPhotoField) {
			return;
		}
		if (!hasText(command.photoFileName())
			|| !hasText(command.photoContentType())
			|| !hasText(command.photoDataBase64())) {
			throw new InvalidFacilityReportException("사진 첨부 정보를 확인해야 합니다.");
		}

		String contentType = command.photoContentType().trim().toLowerCase();
		if (!ALLOWED_PHOTO_CONTENT_TYPES.contains(contentType)) {
			throw new InvalidFacilityReportException("사진 파일 형식을 확인해야 합니다.");
		}

		String photoDataBase64 = command.photoDataBase64().trim();
		if (photoDataBase64.length() > MAX_PHOTO_BASE64_CHARS) {
			throw new InvalidFacilityReportException("사진 파일 크기를 줄여야 합니다.");
		}
		try {
			byte[] photoBytes = Base64.getDecoder().decode(photoDataBase64);
			if (photoBytes.length > MAX_PHOTO_BYTES) {
				throw new InvalidFacilityReportException("사진 파일 크기를 줄여야 합니다.");
			}
		} catch (IllegalArgumentException exception) {
			throw new InvalidFacilityReportException("사진 첨부 정보를 확인해야 합니다.");
		}
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	private String normalizePhotoFileName(String value) {
		return hasText(value) ? value.trim() : null;
	}

	private String normalizePhotoContentType(String value) {
		return hasText(value) ? value.trim().toLowerCase() : null;
	}

	private String normalizePhotoDataBase64(String value) {
		return hasText(value) ? value.trim() : null;
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
}

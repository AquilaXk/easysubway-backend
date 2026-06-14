package com.easysubway.report.application.service;

import com.easysubway.report.application.port.in.CreateFacilityReportCommand;
import com.easysubway.report.application.port.in.FacilityReportUseCase;
import com.easysubway.report.application.port.in.ReviewFacilityReportCommand;
import com.easysubway.report.application.port.out.LoadFacilityReportPort;
import com.easysubway.report.application.port.out.SaveFacilityReportPort;
import com.easysubway.report.domain.FacilityReport;
import com.easysubway.report.domain.FacilityReportNotFoundException;
import com.easysubway.report.domain.FacilityReportReviewDecision;
import com.easysubway.report.domain.FacilityReportStatus;
import com.easysubway.report.domain.FacilityReportTargetNotFoundException;
import com.easysubway.report.domain.FacilityReportType;
import com.easysubway.report.domain.InvalidFacilityReportException;
import com.easysubway.notification.application.port.in.FacilityStatusAlertUseCase;
import com.easysubway.notification.application.port.in.FacilityStatusChangedAlertCommand;
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
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class FacilityReportService implements FacilityReportUseCase {

	private final LoadTransitMasterPort loadTransitMasterPort;
	private final SaveAccessibilityFacilityStatusPort saveAccessibilityFacilityStatusPort;
	private final LoadFacilityReportPort loadFacilityReportPort;
	private final SaveFacilityReportPort saveFacilityReportPort;
	private final FacilityStatusAlertUseCase facilityStatusAlertUseCase;
	private final Clock clock;

	@Autowired
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
		this.loadTransitMasterPort = loadTransitMasterPort;
		this.saveAccessibilityFacilityStatusPort = saveAccessibilityFacilityStatusPort;
		this.loadFacilityReportPort = loadFacilityReportPort;
		this.saveFacilityReportPort = saveFacilityReportPort;
		this.facilityStatusAlertUseCase = facilityStatusAlertUseCase;
		this.clock = clock;
	}

	@Override
	public FacilityReport createReport(CreateFacilityReportCommand command) {
		requireReportType(command);
		requireActiveStation(command.stationId());
		// 신고 대상 시설이 요청한 역에 속해야 다른 역 시설 상태가 잘못 갱신되는 일을 막을 수 있다.
		requireFacilityInStation(command.stationId(), command.facilityId());

		FacilityReport report = new FacilityReport(
			"report-" + UUID.randomUUID(),
			command.userId(),
			command.stationId(),
			command.facilityId(),
			command.reportType(),
			command.description(),
			command.photoUrl(),
			command.latitude(),
			command.longitude(),
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
	public List<FacilityReport> listReports(FacilityReportStatus status) {
		return loadFacilityReportPort.loadReports()
			.stream()
			.filter(report -> status == null || report.status() == status)
			.sorted(Comparator.comparing(FacilityReport::createdAt).reversed())
			.toList();
	}

	@Override
	public FacilityReport reviewReport(ReviewFacilityReportCommand command) {
		requireReviewDecision(command);
		requireReviewer(command);

		FacilityReport report = getReport(command.reportId());
		FacilityReport reviewed = new FacilityReport(
			report.id(),
			report.userId(),
			report.stationId(),
			report.facilityId(),
			report.reportType(),
			report.description(),
			report.photoUrl(),
			report.latitude(),
			report.longitude(),
			toStatus(command.decision()),
			report.createdAt(),
			LocalDateTime.now(clock),
			command.reviewedBy()
		);

		FacilityReport saved = saveFacilityReportPort.saveReport(reviewed);
		// 승인된 상태 신고만 실제 시설 운영 상태에 반영한다.
		applyAcceptedReportToFacilityStatus(report, command.decision());
		return saved;
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

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
import com.easysubway.report.domain.InvalidFacilityReportException;
import com.easysubway.transit.application.port.out.LoadTransitMasterPort;
import com.easysubway.transit.domain.Station;
import com.easysubway.transit.domain.StationNotFoundException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class FacilityReportService implements FacilityReportUseCase {

	private final LoadTransitMasterPort loadTransitMasterPort;
	private final LoadFacilityReportPort loadFacilityReportPort;
	private final SaveFacilityReportPort saveFacilityReportPort;
	private final Clock clock;

	@Autowired
	public FacilityReportService(
		LoadTransitMasterPort loadTransitMasterPort,
		LoadFacilityReportPort loadFacilityReportPort,
		SaveFacilityReportPort saveFacilityReportPort
	) {
		this(loadTransitMasterPort, loadFacilityReportPort, saveFacilityReportPort, Clock.systemDefaultZone());
	}

	public FacilityReportService(
		LoadTransitMasterPort loadTransitMasterPort,
		LoadFacilityReportPort loadFacilityReportPort,
		SaveFacilityReportPort saveFacilityReportPort,
		Clock clock
	) {
		this.loadTransitMasterPort = loadTransitMasterPort;
		this.loadFacilityReportPort = loadFacilityReportPort;
		this.saveFacilityReportPort = saveFacilityReportPort;
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

		return saveFacilityReportPort.saveReport(reviewed);
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
}

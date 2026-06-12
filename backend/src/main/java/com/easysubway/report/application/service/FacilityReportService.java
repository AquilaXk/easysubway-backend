package com.easysubway.report.application.service;

import com.easysubway.report.application.port.in.CreateFacilityReportCommand;
import com.easysubway.report.application.port.in.FacilityReportUseCase;
import com.easysubway.report.application.port.out.LoadFacilityReportPort;
import com.easysubway.report.application.port.out.SaveFacilityReportPort;
import com.easysubway.report.domain.FacilityReport;
import com.easysubway.report.domain.FacilityReportNotFoundException;
import com.easysubway.report.domain.FacilityReportStatus;
import com.easysubway.report.domain.FacilityReportTargetNotFoundException;
import com.easysubway.report.domain.InvalidFacilityReportException;
import com.easysubway.transit.application.port.out.LoadTransitMasterPort;
import com.easysubway.transit.domain.Station;
import com.easysubway.transit.domain.StationNotFoundException;
import java.time.Clock;
import java.time.LocalDateTime;
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

	private void requireReportType(CreateFacilityReportCommand command) {
		if (command.reportType() == null) {
			throw new InvalidFacilityReportException("신고 유형을 선택해야 합니다.");
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
}

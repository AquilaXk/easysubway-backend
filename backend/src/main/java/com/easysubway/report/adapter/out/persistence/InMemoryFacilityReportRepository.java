package com.easysubway.report.adapter.out.persistence;

import com.easysubway.report.application.port.out.LoadFacilityReportPort;
import com.easysubway.report.application.port.out.SaveFacilityReportPort;
import com.easysubway.report.domain.FacilityReport;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryFacilityReportRepository implements LoadFacilityReportPort, SaveFacilityReportPort {

	private final Map<String, FacilityReport> reports = new ConcurrentHashMap<>();

	@Override
	public Optional<FacilityReport> loadReport(String reportId) {
		return Optional.ofNullable(reports.get(reportId));
	}

	@Override
	public List<FacilityReport> loadReports() {
		return List.copyOf(reports.values());
	}

	@Override
	public FacilityReport saveReport(FacilityReport report) {
		reports.put(report.id(), report);
		return report;
	}
}

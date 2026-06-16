package com.easysubway.report.adapter.out.persistence;

import com.easysubway.report.application.port.out.LoadFacilityReportReviewAuditPort;
import com.easysubway.report.application.port.out.SaveFacilityReportReviewAuditPort;
import com.easysubway.report.domain.FacilityReportReviewAudit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!prod")
public class InMemoryFacilityReportReviewAuditRepository implements
	LoadFacilityReportReviewAuditPort,
	SaveFacilityReportReviewAuditPort {

	private final List<FacilityReportReviewAudit> audits = Collections.synchronizedList(new ArrayList<>());

	@Override
	public FacilityReportReviewAudit saveAudit(FacilityReportReviewAudit audit) {
		audits.add(audit);
		return audit;
	}

	@Override
	public List<FacilityReportReviewAudit> loadAuditsByReportId(String reportId) {
		synchronized (audits) {
			return audits.stream()
				.filter(audit -> audit.reportId().equals(reportId))
				.toList();
		}
	}
}

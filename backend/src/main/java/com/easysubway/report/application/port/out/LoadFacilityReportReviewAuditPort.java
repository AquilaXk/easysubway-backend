package com.easysubway.report.application.port.out;

import com.easysubway.report.domain.FacilityReportReviewAudit;
import java.util.List;

public interface LoadFacilityReportReviewAuditPort {

	List<FacilityReportReviewAudit> loadAuditsByReportId(String reportId);
}

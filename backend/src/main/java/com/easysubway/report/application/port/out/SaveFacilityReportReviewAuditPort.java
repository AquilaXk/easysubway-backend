package com.easysubway.report.application.port.out;

import com.easysubway.report.domain.FacilityReportReviewAudit;

public interface SaveFacilityReportReviewAuditPort {

	FacilityReportReviewAudit saveAudit(FacilityReportReviewAudit audit);
}

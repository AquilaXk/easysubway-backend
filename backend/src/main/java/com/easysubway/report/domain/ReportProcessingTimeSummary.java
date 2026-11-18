package com.easysubway.report.domain;

public record ReportProcessingTimeSummary(
	long reviewedReportCount,
	long averageProcessingMinutes
) {

	public static ReportProcessingTimeSummary empty() {
		return new ReportProcessingTimeSummary(0, 0);
	}
}

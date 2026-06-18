package com.easysubway.operator.adapter.in.web;

import java.util.List;

record OperatorPushNotificationReportView(
	long totalCount,
	long pendingCount,
	long sentCount,
	long failedCount,
	String latestFailureReason,
	List<StatusCountRow> statusRows
) {

	record StatusCountRow(
		String label,
		String description,
		long count
	) {
	}
}

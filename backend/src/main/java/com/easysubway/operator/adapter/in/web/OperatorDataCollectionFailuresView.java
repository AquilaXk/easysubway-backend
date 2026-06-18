package com.easysubway.operator.adapter.in.web;

import java.util.List;

record OperatorDataCollectionFailuresView(
	int totalRunCount,
	long failedRunCount,
	long retryableRunCount,
	List<DataCollectionRunRow> rows
) {

	record DataCollectionRunRow(
		String sourceLabel,
		String statusLabel,
		String startedAtLabel,
		String completedAtLabel,
		int collectedCount,
		String failureMessage,
		boolean retryable,
		String operatorAction
	) {
	}
}

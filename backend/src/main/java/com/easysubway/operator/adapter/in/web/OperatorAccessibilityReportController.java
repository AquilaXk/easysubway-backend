package com.easysubway.operator.adapter.in.web;

import com.easysubway.common.web.ApiResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class OperatorAccessibilityReportController {

	private static final String TEXT_CSV_UTF8 = "text/csv;charset=UTF-8";
	private static final String PROPOSAL_FILENAME = "easysubway-operator-accessibility-proposal.csv";

	private final OperatorAccessibilityReportAssembler reportAssembler;

	OperatorAccessibilityReportController(OperatorAccessibilityReportAssembler reportAssembler) {
		this.reportAssembler = reportAssembler;
	}

	@GetMapping("/operator/api/accessibility-report")
	ApiResponse<OperatorAccessibilityReportView> accessibilityReport() {
		return ApiResponse.ok(reportAssembler.assemble());
	}

	@GetMapping("/operator/api/accessibility-report/proposal.csv")
	ResponseEntity<String> partnershipProposalCsv() {
		HttpHeaders headers = new HttpHeaders();
		headers.add(HttpHeaders.CONTENT_TYPE, TEXT_CSV_UTF8);
		headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + PROPOSAL_FILENAME + "\"");
		return new ResponseEntity<>(toCsv(reportAssembler.assemble()), headers, HttpStatus.OK);
	}

	private String toCsv(OperatorAccessibilityReportView report) {
		StringBuilder csv = new StringBuilder("section,metric,value,detail\n");
		appendRow(csv, "summary", "totalStations", report.totalStations(), "");
		appendRow(csv, "summary", "totalFacilities", report.totalFacilities(), "");
		appendRow(csv, "summary", "needsVerificationFacilityCount", report.needsVerificationFacilityCount(), "");
		appendRow(csv, "summary", "delayedFacilityStatusCount", report.delayedFacilityStatusCount(), "");
		appendRow(csv, "summary", "missingStationVerificationDateCount", report.missingStationVerificationDateCount(), "");
		report.stationAccessibilityScoreRows()
			.forEach(row -> appendRow(csv, "stationScore", row.stationName(), row.score(), row.region() + " - " + row.reasonText()));
		report.accessibilityImprovementPriorityRows()
			.forEach(row -> appendRow(csv, "priority", row.stationName(), row.facilityName(), row.priorityScore() + " - " + row.reasonText()));
		return csv.toString();
	}

	private void appendRow(StringBuilder csv, String section, String metric, Object value, String detail) {
		csv.append(csvValue(section))
			.append(',')
			.append(csvValue(metric))
			.append(',')
			.append(csvValue(String.valueOf(value)))
			.append(',')
			.append(csvValue(detail))
			.append('\n');
	}

	private String csvValue(String value) {
		if (value == null) {
			return "";
		}
		if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
			return "\"" + value.replace("\"", "\"\"") + "\"";
		}
		return value;
	}
}

package com.easysubway.operator.adapter.in.web;

import com.easysubway.common.web.ApiResponse;
import com.easysubway.common.web.export.EgovExcelExportSupport;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class OperatorAccessibilityReportController {

	private static final String TEXT_CSV_UTF8 = "text/csv;charset=UTF-8";
	private static final String XLSX_CONTENT_TYPE =
		"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
	private static final String PROPOSAL_FILENAME = "easysubway-operator-accessibility-proposal.csv";
	private static final String PROPOSAL_XLSX_FILENAME = "easysubway-operator-accessibility-proposal.xlsx";
	private static final List<String> XLSX_HEADER = List.of("section", "metric", "value", "detail");

	private final OperatorAccessibilityReportAssembler reportAssembler;
	private final EgovExcelExportSupport excelExportSupport;

	OperatorAccessibilityReportController(
		OperatorAccessibilityReportAssembler reportAssembler,
		EgovExcelExportSupport excelExportSupport
	) {
		this.reportAssembler = reportAssembler;
		this.excelExportSupport = excelExportSupport;
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

	@GetMapping("/operator/api/accessibility-report/proposal.xlsx")
	ResponseEntity<byte[]> partnershipProposalXlsx() {
		byte[] xlsx = excelExportSupport.toXlsx(
			"accessibility-proposal", XLSX_HEADER, toXlsxRows(reportAssembler.assemble()));
		HttpHeaders headers = new HttpHeaders();
		headers.add(HttpHeaders.CONTENT_TYPE, XLSX_CONTENT_TYPE);
		headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + PROPOSAL_XLSX_FILENAME + "\"");
		return new ResponseEntity<>(xlsx, headers, HttpStatus.OK);
	}

	// CSV와 동일 데이터·순서로 셀 값을 구성한다(운영기관 CSV는 수식 이스케이프를 적용하지 않음 — 동일 정책 유지).
	private List<List<String>> toXlsxRows(OperatorAccessibilityReportView report) {
		List<List<String>> rows = new ArrayList<>();
		rows.add(xlsxRow("summary", "totalStations", report.totalStations(), ""));
		rows.add(xlsxRow("summary", "totalFacilities", report.totalFacilities(), ""));
		rows.add(xlsxRow("summary", "needsVerificationFacilityCount", report.needsVerificationFacilityCount(), ""));
		rows.add(xlsxRow("summary", "delayedFacilityStatusCount", report.delayedFacilityStatusCount(), ""));
		rows.add(xlsxRow("summary", "missingStationVerificationDateCount", report.missingStationVerificationDateCount(), ""));
		report.stationAccessibilityScoreRows()
			.forEach(row -> rows.add(xlsxRow(
				"stationScore", row.stationName(), row.score(), row.region() + " - " + row.reasonText())));
		report.accessibilityImprovementPriorityRows()
			.forEach(row -> rows.add(xlsxRow(
				"priority", row.stationName(), row.facilityName(), row.priorityScore() + " - " + row.reasonText())));
		return rows;
	}

	private List<String> xlsxRow(String section, String metric, Object value, String detail) {
		return List.of(section, metric, String.valueOf(value), detail);
	}

	private String toCsv(OperatorAccessibilityReportView report) {
		List<List<String>> rows = new ArrayList<>();
		rows.add(row("summary", "totalStations", String.valueOf(report.totalStations()), ""));
		rows.add(row("summary", "totalFacilities", String.valueOf(report.totalFacilities()), ""));
		rows.add(row("summary", "needsVerificationFacilityCount",
			String.valueOf(report.needsVerificationFacilityCount()), ""));
		rows.add(row("summary", "delayedFacilityStatusCount",
			String.valueOf(report.delayedFacilityStatusCount()), ""));
		rows.add(row("summary", "missingStationVerificationDateCount",
			String.valueOf(report.missingStationVerificationDateCount()), ""));
		report.stationAccessibilityScoreRows().forEach(scoreRow -> rows.add(row(
			"stationScore",
			scoreRow.stationName(),
			String.valueOf(scoreRow.score()),
			scoreRow.region() + " - " + scoreRow.reasonText())));
		report.accessibilityImprovementPriorityRows().forEach(priorityRow -> rows.add(row(
			"priority",
			priorityRow.stationName(),
			priorityRow.facilityName(),
			priorityRow.priorityScore() + " - " + priorityRow.reasonText())));
		return OperatorReportCsv.document(List.of("section", "metric", "value", "detail"), rows);
	}

	private static List<String> row(String section, String metric, String value, String detail) {
		return List.of(section, metric, value, detail);
	}
}

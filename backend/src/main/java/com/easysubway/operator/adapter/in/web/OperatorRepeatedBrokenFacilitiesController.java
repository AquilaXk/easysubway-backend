package com.easysubway.operator.adapter.in.web;

import com.easysubway.common.web.ApiResponse;
import com.easysubway.operator.adapter.in.web.OperatorRepeatedBrokenFacilitiesView.RepeatedBrokenFacilityRow;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class OperatorRepeatedBrokenFacilitiesController {

	private static final String CSV_FILENAME = "easysubway-operator-repeated-broken-facilities.csv";

	private final OperatorRepeatedBrokenFacilitiesAssembler repeatedBrokenFacilitiesAssembler;

	OperatorRepeatedBrokenFacilitiesController(
		OperatorRepeatedBrokenFacilitiesAssembler repeatedBrokenFacilitiesAssembler
	) {
		this.repeatedBrokenFacilitiesAssembler = repeatedBrokenFacilitiesAssembler;
	}

	@GetMapping("/operator/api/repeated-broken-facilities")
	ApiResponse<OperatorRepeatedBrokenFacilitiesView> repeatedBrokenFacilities() {
		return ApiResponse.ok(repeatedBrokenFacilitiesAssembler.assemble());
	}

	@GetMapping("/operator/api/repeated-broken-facilities.csv")
	ResponseEntity<byte[]> repeatedBrokenFacilitiesCsv() {
		OperatorRepeatedBrokenFacilitiesView report = repeatedBrokenFacilitiesAssembler.assemble();
		List<List<String>> rows = report.rows().stream()
			.map(OperatorRepeatedBrokenFacilitiesController::csvRow)
			.toList();
		byte[] body = OperatorReportCsv.document(
			List.of("역", "시설", "상태", "신고 건수"),
			rows
		).getBytes(StandardCharsets.UTF_8);

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
		headers.setContentDisposition(ContentDisposition.attachment().filename(CSV_FILENAME).build());
		return ResponseEntity.ok().headers(headers).body(body);
	}

	private static List<String> csvRow(RepeatedBrokenFacilityRow row) {
		return List.of(
			row.stationName(),
			row.facilityName(),
			row.statusLabel(),
			String.valueOf(row.reportCount())
		);
	}
}

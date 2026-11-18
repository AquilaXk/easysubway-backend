package com.easysubway.operator.adapter.in.web;

import com.easysubway.common.web.ApiResponse;
import com.easysubway.operator.adapter.in.web.OperatorDataCollectionFailuresView.DataCollectionRunRow;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class OperatorDataCollectionFailuresController {

	private static final String CSV_FILENAME = "easysubway-operator-data-collection-failures.csv";

	private final OperatorDataCollectionFailuresAssembler dataCollectionFailuresAssembler;

	OperatorDataCollectionFailuresController(
		OperatorDataCollectionFailuresAssembler dataCollectionFailuresAssembler
	) {
		this.dataCollectionFailuresAssembler = dataCollectionFailuresAssembler;
	}

	@GetMapping("/operator/api/data-collection-failures")
	ApiResponse<OperatorDataCollectionFailuresView> dataCollectionFailures() {
		return ApiResponse.ok(dataCollectionFailuresAssembler.assemble());
	}

	@GetMapping("/operator/api/data-collection-failures.csv")
	ResponseEntity<byte[]> dataCollectionFailuresCsv() {
		OperatorDataCollectionFailuresView report = dataCollectionFailuresAssembler.assemble();
		List<List<String>> rows = report.rows().stream()
			.map(OperatorDataCollectionFailuresController::csvRow)
			.toList();
		byte[] body = OperatorReportCsv.document(
			List.of("수집 대상", "상태", "시작", "완료", "수집 건수", "실패 사유", "재시도 가능", "운영 안내"),
			rows
		).getBytes(StandardCharsets.UTF_8);

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
		headers.setContentDisposition(ContentDisposition.attachment().filename(CSV_FILENAME).build());
		return ResponseEntity.ok().headers(headers).body(body);
	}

	private static List<String> csvRow(DataCollectionRunRow row) {
		return List.of(
			row.sourceLabel(),
			row.statusLabel(),
			row.startedAtLabel(),
			row.completedAtLabel(),
			String.valueOf(row.collectedCount()),
			row.failureMessage(),
			row.retryable() ? "예" : "아니오",
			row.operatorAction()
		);
	}
}

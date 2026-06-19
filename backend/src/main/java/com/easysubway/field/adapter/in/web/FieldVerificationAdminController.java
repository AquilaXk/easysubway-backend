package com.easysubway.field.adapter.in.web;

import com.easysubway.common.error.InvalidRequestException;
import com.easysubway.common.web.ApiResponse;
import com.easysubway.field.application.port.in.FieldVerificationUseCase;
import com.easysubway.field.application.port.in.UpdateFieldVerificationItemStatusCommand;
import com.easysubway.field.domain.FieldVerificationChangeHistory;
import com.easysubway.field.domain.FieldVerificationItem;
import com.easysubway.field.domain.FieldVerificationItemType;
import com.easysubway.field.domain.FieldVerificationSession;
import com.easysubway.field.domain.FieldVerificationStatus;
import java.security.Principal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class FieldVerificationAdminController {

	private static final String TEXT_CSV_UTF8 = "text/csv;charset=UTF-8";
	private static final String CSV_HEADER = "sessionId,stationId,stationName,verifiedAt,verifiedBy,sessionStatus,itemType,itemLabel,targetName,itemStatus,note";

	private final FieldVerificationUseCase fieldVerificationUseCase;

	FieldVerificationAdminController(FieldVerificationUseCase fieldVerificationUseCase) {
		this.fieldVerificationUseCase = fieldVerificationUseCase;
	}

	@GetMapping("/admin/field-verifications/stations")
	ApiResponse<List<FieldVerificationView>> stationFieldVerifications() {
		return ApiResponse.ok(fieldVerificationUseCase.listStationVerifications().stream()
			.map(FieldVerificationView::from)
			.toList());
	}

	@GetMapping("/admin/field-verifications/stations/{stationId}")
	ApiResponse<FieldVerificationView> stationFieldVerification(@PathVariable String stationId) {
		return ApiResponse.ok(FieldVerificationView.from(fieldVerificationUseCase.getStationVerification(stationId)));
	}

	@GetMapping("/admin/field-verifications/stations/{stationId}/history")
	ApiResponse<List<FieldVerificationChangeHistoryView>> stationFieldVerificationChangeHistory(
		@PathVariable String stationId
	) {
		return ApiResponse.ok(fieldVerificationUseCase.listStationChangeHistory(stationId).stream()
			.map(FieldVerificationChangeHistoryView::from)
			.toList());
	}

	@PatchMapping("/admin/field-verifications/stations/{stationId}/items/{itemId}/status")
	ApiResponse<FieldVerificationView> updateFieldVerificationItemStatus(
		@PathVariable String stationId,
		@PathVariable String itemId,
		@RequestBody UpdateFieldVerificationItemStatusRequest request,
		Principal principal
	) {
		FieldVerificationSession session = fieldVerificationUseCase.updateItemStatus(
			request.toCommand(stationId, itemId, principal.getName())
		);
		return ApiResponse.ok(FieldVerificationView.from(session));
	}

	@GetMapping("/admin/field-verifications/stations/{stationId}/export.csv")
	ResponseEntity<String> stationFieldVerificationCsv(@PathVariable String stationId) {
		FieldVerificationSession session = fieldVerificationUseCase.getStationVerification(stationId);
		HttpHeaders headers = new HttpHeaders();
		headers.add(HttpHeaders.CONTENT_TYPE, TEXT_CSV_UTF8);
		headers.add(
			HttpHeaders.CONTENT_DISPOSITION,
			"attachment; filename=\"easysubway-field-verification-" + safeFilenameStationId(stationId) + ".csv\""
		);
		return new ResponseEntity<>(toCsv(session), headers, HttpStatus.OK);
	}

	private String toCsv(FieldVerificationSession session) {
		StringBuilder csv = new StringBuilder(CSV_HEADER).append('\n');
		session.items().forEach(item -> appendItemRow(csv, session, item));
		return csv.toString();
	}

	private void appendItemRow(StringBuilder csv, FieldVerificationSession session, FieldVerificationItem item) {
		csv.append(csvValue(session.id()))
			.append(',')
			.append(csvValue(session.stationId()))
			.append(',')
			.append(csvValue(session.stationName()))
			.append(',')
			.append(csvValue(String.valueOf(session.verifiedAt())))
			.append(',')
			.append(csvValue(session.verifiedBy()))
			.append(',')
			.append(csvValue(session.status().name()))
			.append(',')
			.append(csvValue(item.type().name()))
			.append(',')
			.append(csvValue(item.type().label()))
			.append(',')
			.append(csvValue(item.targetName()))
			.append(',')
			.append(csvValue(item.status().name()))
			.append(',')
			.append(csvValue(item.note()))
			.append('\n');
	}

	private String csvValue(String value) {
		if (value == null) {
			return "";
		}
		String safe = escapeSpreadsheetFormula(value);
		if (safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")) {
			return "\"" + safe.replace("\"", "\"\"") + "\"";
		}
		return safe;
	}

	private String escapeSpreadsheetFormula(String value) {
		if (value.isEmpty()) {
			return value;
		}
		char first = value.charAt(0);
		if (first == '=' || first == '+' || first == '-' || first == '@') {
			return "'" + value;
		}
		return value;
	}

	private String safeFilenameStationId(String stationId) {
		return stationId.replaceAll("[^A-Za-z0-9_-]", "_");
	}

	record FieldVerificationView(
		String sessionId,
		String stationId,
		String stationName,
		LocalDate verifiedAt,
		String verifiedBy,
		FieldVerificationStatus status,
		String note,
		List<FieldVerificationItemView> items
	) {

		static FieldVerificationView from(FieldVerificationSession session) {
			return new FieldVerificationView(
				session.id(),
				session.stationId(),
				session.stationName(),
				session.verifiedAt(),
				session.verifiedBy(),
				session.status(),
				session.note(),
				session.items().stream()
					.map(FieldVerificationItemView::from)
					.toList()
			);
		}
	}

	record FieldVerificationItemView(
		String itemId,
		FieldVerificationItemType type,
		String label,
		String targetName,
		FieldVerificationStatus status,
		String note
	) {

		static FieldVerificationItemView from(FieldVerificationItem item) {
			return new FieldVerificationItemView(
				item.id(),
				item.type(),
				item.type().label(),
				item.targetName(),
				item.status(),
				item.note()
			);
		}
	}

	record FieldVerificationChangeHistoryView(
		String historyId,
		String sessionId,
		String stationId,
		String itemId,
		FieldVerificationStatus previousStatus,
		FieldVerificationStatus newStatus,
		String previousNote,
		String newNote,
		String changedBy,
		LocalDateTime changedAt
	) {

		static FieldVerificationChangeHistoryView from(FieldVerificationChangeHistory history) {
			return new FieldVerificationChangeHistoryView(
				history.id(),
				history.sessionId(),
				history.stationId(),
				history.itemId(),
				history.previousStatus(),
				history.newStatus(),
				history.previousNote(),
				history.newNote(),
				history.changedBy(),
				history.changedAt()
			);
		}
	}

	record UpdateFieldVerificationItemStatusRequest(
		FieldVerificationStatus status,
		String note
	) {

		UpdateFieldVerificationItemStatusCommand toCommand(String stationId, String itemId, String changedBy) {
			if (status == null) {
				throw new InvalidRequestException("현장 검증 상태를 선택해야 합니다.");
			}
			if (status != FieldVerificationStatus.VERIFIED
				&& status != FieldVerificationStatus.NEEDS_RECHECK) {
				throw new InvalidRequestException("현장 검증 상태는 VERIFIED 또는 NEEDS_RECHECK만 허용됩니다.");
			}
			return new UpdateFieldVerificationItemStatusCommand(stationId, itemId, status, note, changedBy);
		}
	}
}

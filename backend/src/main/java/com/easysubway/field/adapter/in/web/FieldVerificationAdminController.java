package com.easysubway.field.adapter.in.web;

import com.easysubway.common.web.ApiResponse;
import com.easysubway.field.application.port.in.FieldVerificationUseCase;
import com.easysubway.field.domain.FieldVerificationItem;
import com.easysubway.field.domain.FieldVerificationItemType;
import com.easysubway.field.domain.FieldVerificationSession;
import com.easysubway.field.domain.FieldVerificationStatus;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
class FieldVerificationAdminController {

	private final FieldVerificationUseCase fieldVerificationUseCase;

	FieldVerificationAdminController(FieldVerificationUseCase fieldVerificationUseCase) {
		this.fieldVerificationUseCase = fieldVerificationUseCase;
	}

	@GetMapping("/admin/field-verifications/stations/{stationId}")
	ApiResponse<FieldVerificationView> stationFieldVerification(@PathVariable String stationId) {
		return ApiResponse.ok(FieldVerificationView.from(fieldVerificationUseCase.getStationVerification(stationId)));
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
}

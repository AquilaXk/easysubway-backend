package com.easysubway.field.adapter.in.web;

import com.easysubway.admin.web.AdminFormErrorView;
import com.easysubway.common.web.pagination.AdminPageRequest;
import com.easysubway.common.web.pagination.EgovPaginationView;
import com.easysubway.field.application.port.in.FieldVerificationUseCase;
import com.easysubway.field.application.port.in.UpdateFieldVerificationItemStatusCommand;
import com.easysubway.field.domain.FieldVerificationChangeHistory;
import com.easysubway.field.domain.FieldVerificationItem;
import com.easysubway.field.domain.FieldVerificationSession;
import com.easysubway.field.domain.FieldVerificationStatus;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.security.Principal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
class FieldVerificationAdminPageController {

	private final FieldVerificationUseCase fieldVerificationUseCase;

	FieldVerificationAdminPageController(FieldVerificationUseCase fieldVerificationUseCase) {
		this.fieldVerificationUseCase = fieldVerificationUseCase;
	}

	@GetMapping("/admin/field-verifications/page")
	String fieldVerificationsPage(
		@RequestParam(required = false) Integer page,
		@RequestParam(required = false) Integer size,
		Model model
	) {
		AdminPageRequest pageRequest = AdminPageRequest.of(page, size);
		List<SessionRow> verifications = fieldVerificationUseCase.listStationVerifications().stream()
			.map(SessionRow::from)
			.toList();
		EgovPaginationView pageView = EgovPaginationView.from(pageRequest.page(), pageRequest.size(), verifications.size());
		model.addAttribute("verifications", pageView.pageItems(verifications));
		model.addAttribute("page", pageView);
		model.addAttribute("paginationLinks", pageView.links("/admin/field-verifications/page", Collections.emptyMap()));
		return "admin/field/list";
	}

	@GetMapping("/admin/field-verifications/{stationId}/page")
	String fieldVerificationDetailPage(@PathVariable String stationId, Model model) {
		populateDetailModel(stationId, model);
		return "admin/field/detail";
	}

	private void populateDetailModel(String stationId, Model model) {
		FieldVerificationSession session = fieldVerificationUseCase.getStationVerification(stationId);
		model.addAttribute("verification", SessionRow.from(session));
		model.addAttribute("items", session.items().stream().map(ItemRow::from).toList());
		model.addAttribute("history", fieldVerificationUseCase.listStationChangeHistory(stationId).stream()
			.map(HistoryRow::from)
			.toList());
		model.addAttribute("statusOptions", List.of(FieldVerificationStatus.VERIFIED, FieldVerificationStatus.NEEDS_RECHECK));
	}

	@PostMapping("/admin/field-verifications/{stationId}/items/{itemId}/page/status")
	String updateFieldVerificationItemStatusFromPage(
		@PathVariable String stationId,
		@PathVariable String itemId,
		@Valid @ModelAttribute("fieldVerificationForm") FieldVerificationStatusForm form,
		BindingResult bindingResult,
		Principal principal,
		Model model,
		HttpServletResponse response
	) {
		if (bindingResult.hasErrors()) {
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			populateDetailModel(stationId, model);
			model.addAttribute("fieldVerificationFailedItemId", itemId);
			AdminFormErrorView.expose(model, bindingResult);
			return "admin/field/detail";
		}
		fieldVerificationUseCase.updateItemStatus(
			new UpdateFieldVerificationItemStatusCommand(stationId, itemId, form.status(), form.note(), principal.getName())
		);
		return "redirect:/admin/field-verifications/%s/page".formatted(stationId);
	}

	private static String statusLabel(FieldVerificationStatus status) {
		return switch (status) {
			case PLANNED -> "예정";
			case IN_PROGRESS -> "진행 중";
			case VERIFIED -> "검증 완료";
			case NEEDS_RECHECK -> "재확인 필요";
		};
	}

	record SessionRow(
		String sessionId,
		String stationId,
		String stationName,
		LocalDate verifiedAt,
		String verifiedBy,
		String statusLabel,
		String note,
		int itemCount,
		long verifiedCount,
		long recheckCount
	) {

		static SessionRow from(FieldVerificationSession session) {
			long verifiedCount = session.items().stream()
				.filter(item -> item.status() == FieldVerificationStatus.VERIFIED)
				.count();
			long recheckCount = session.items().stream()
				.filter(item -> item.status() == FieldVerificationStatus.NEEDS_RECHECK)
				.count();
			return new SessionRow(
				session.id(),
				session.stationId(),
				session.stationName(),
				session.verifiedAt(),
				session.verifiedBy(),
				FieldVerificationAdminPageController.statusLabel(session.status()),
				session.note(),
				session.items().size(),
				verifiedCount,
				recheckCount
			);
		}
	}

	record ItemRow(
		String itemId,
		String typeLabel,
		String targetName,
		FieldVerificationStatus status,
		String statusLabel,
		String note
	) {

		static ItemRow from(FieldVerificationItem item) {
			return new ItemRow(
				item.id(),
				item.type().label(),
				item.targetName(),
				item.status(),
				FieldVerificationAdminPageController.statusLabel(item.status()),
				item.note()
			);
		}
	}

	record HistoryRow(
		String historyId,
		String itemId,
		String previousStatus,
		String newStatus,
		String previousNote,
		String newNote,
		String changedBy,
		LocalDateTime changedAt
	) {

		static HistoryRow from(FieldVerificationChangeHistory history) {
			return new HistoryRow(
				history.id(),
				history.itemId(),
				FieldVerificationAdminPageController.statusLabel(history.previousStatus()),
				FieldVerificationAdminPageController.statusLabel(history.newStatus()),
				history.previousNote(),
				history.newNote(),
				history.changedBy(),
				history.changedAt()
			);
		}
	}

	record FieldVerificationStatusForm(
		@NotNull(message = "{validation.field-verification.status.required}")
		FieldVerificationStatus status,
		String note
	) {

		@AssertTrue(message = "{validation.field-verification.status.allowed}")
		public boolean isStatusAllowed() {
			return status == null
				|| status == FieldVerificationStatus.VERIFIED
				|| status == FieldVerificationStatus.NEEDS_RECHECK;
		}
	}
}

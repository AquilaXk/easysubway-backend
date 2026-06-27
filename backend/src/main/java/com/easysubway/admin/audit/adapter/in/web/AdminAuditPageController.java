package com.easysubway.admin.audit.adapter.in.web;

import com.easysubway.admin.audit.application.port.out.AdminAuditEventRepository;
import com.easysubway.admin.audit.domain.AdminAuditEvent;
import com.easysubway.admin.audit.domain.AdminAuditEventType;
import com.easysubway.common.web.pagination.AdminPageRequest;
import com.easysubway.common.web.pagination.EgovPaginationView;
import java.util.Collections;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
class AdminAuditPageController {

	private final AdminAuditEventRepository auditEventRepository;

	AdminAuditPageController(AdminAuditEventRepository auditEventRepository) {
		this.auditEventRepository = auditEventRepository;
	}

	@GetMapping("/admin/audits/page")
	@PreAuthorize("hasAuthority('admin.audit.read')")
	String auditPage(
		@RequestParam(required = false) Integer page,
		@RequestParam(required = false) Integer size,
		Model model
	) {
		populateAuditModel(model, "관리자 감사", "a-audits", "/admin/audits/page", null, page, size);
		return "admin/audits/list";
	}

	@GetMapping("/admin/audits/privacy/page")
	@PreAuthorize("hasAuthority('admin.privacy-log.read')")
	String privacyAuditPage(
		@RequestParam(required = false) Integer page,
		@RequestParam(required = false) Integer size,
		Model model
	) {
		populateAuditModel(
			model,
			"개인정보 조회 로그",
			"a-privacy-audits",
			"/admin/audits/privacy/page",
			AdminAuditEventType.PRIVACY_READ,
			page,
			size
		);
		return "admin/audits/list";
	}

	private void populateAuditModel(
		Model model,
		String title,
		String activeProgram,
		String path,
		AdminAuditEventType eventType,
		Integer page,
		Integer size
	) {
		AdminPageRequest pageRequest = AdminPageRequest.of(page, size);
		List<AuditEventRow> events = rows(auditEventRepository.findRecent(
			eventType,
			pageRequest.limitForHasNext(),
			pageRequest.offset()
		));
		EgovPaginationView pageView = EgovPaginationView.fromSlice(pageRequest.page(), pageRequest.size(), events.size());
		model.addAttribute("title", title);
		model.addAttribute("paginationLabel", title + " 페이지");
		model.addAttribute("activeProgram", activeProgram);
		model.addAttribute("events", pageView.visibleItems(events));
		model.addAttribute("page", pageView);
		model.addAttribute("paginationLinks", pageView.links(path, Collections.emptyMap()));
	}

	private static List<AuditEventRow> rows(List<AdminAuditEvent> events) {
		return events.stream().map(AuditEventRow::from).toList();
	}

	record AuditEventRow(
		String eventType,
		String actor,
		String rolePermission,
		String requestId,
		String clientIp,
		String userAgent,
		String targetType,
		String targetId,
		String action,
		String outcome,
		String reason,
		String occurredAt
	) {

		static AuditEventRow from(AdminAuditEvent event) {
			return new AuditEventRow(
				event.eventType().name(),
				event.actor(),
				orDash(event.rolePermission()),
				orDash(event.requestId()),
				orDash(event.clientIp()),
				orDash(event.userAgent()),
				event.targetType(),
				orDash(event.targetId()),
				event.action(),
				event.outcome().name(),
				orDash(event.reason()),
				event.occurredAt().toString()
			);
		}

		private static String orDash(String value) {
			return value == null || value.isBlank() ? "-" : value;
		}
	}
}

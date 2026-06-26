package com.easysubway.admin.audit.adapter.in.web;

import com.easysubway.admin.audit.application.port.out.AdminAuditEventRepository;
import com.easysubway.admin.audit.domain.AdminAuditEvent;
import com.easysubway.admin.audit.domain.AdminAuditEventType;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class AdminAuditPageController {

	private final AdminAuditEventRepository auditEventRepository;

	AdminAuditPageController(AdminAuditEventRepository auditEventRepository) {
		this.auditEventRepository = auditEventRepository;
	}

	@GetMapping("/admin/audits/page")
	@PreAuthorize("hasAuthority('admin.audit.read')")
	String auditPage(Model model) {
		model.addAttribute("title", "관리자 감사");
		model.addAttribute("activeProgram", "a-audits");
		model.addAttribute("events", rows(auditEventRepository.findRecent(null, 100)));
		return "admin/audits/list";
	}

	@GetMapping("/admin/audits/privacy/page")
	@PreAuthorize("hasAuthority('admin.privacy-log.read')")
	String privacyAuditPage(Model model) {
		model.addAttribute("title", "개인정보 조회 로그");
		model.addAttribute("activeProgram", "a-privacy-audits");
		model.addAttribute("events", rows(auditEventRepository.findRecent(AdminAuditEventType.PRIVACY_READ, 100)));
		return "admin/audits/list";
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

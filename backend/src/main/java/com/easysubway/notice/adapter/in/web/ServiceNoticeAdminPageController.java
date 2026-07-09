package com.easysubway.notice.adapter.in.web;

import com.easysubway.admin.audit.application.service.AdminAuditWriter;
import com.easysubway.admin.audit.domain.AdminAuditOutcome;
import com.easysubway.common.error.InvalidRequestException;
import com.easysubway.common.error.ResourceNotFoundException;
import com.easysubway.notice.application.port.out.ServiceNoticeRepository;
import com.easysubway.notice.application.service.PublishNoticeCommand;
import com.easysubway.notice.application.service.ServiceNoticeService;
import com.easysubway.notice.domain.ServiceNotice;
import com.easysubway.notice.domain.ServiceNoticeScope;
import com.easysubway.notice.domain.ServiceNoticeSeverity;
import jakarta.servlet.http.HttpServletRequest;
import java.time.format.DateTimeParseException;
import java.time.LocalDateTime;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
class ServiceNoticeAdminPageController {

	private static final int NOTICE_LIMIT = 50;

	private final ServiceNoticeRepository repository;
	private final ServiceNoticeService service;
	private final AdminAuditWriter auditWriter;

	ServiceNoticeAdminPageController(
		ServiceNoticeRepository repository,
		ServiceNoticeService service,
		AdminAuditWriter auditWriter
	) {
		this.repository = repository;
		this.service = service;
		this.auditWriter = auditWriter;
	}

	@GetMapping("/admin/notices/page")
	@PreAuthorize("hasAuthority('admin.operations.manage')")
	String page(Model model) {
		model.addAttribute("notices", repository.findRecent(NOTICE_LIMIT).stream()
			.map(ServiceNoticeView::from)
			.toList());
		model.addAttribute("scopes", ServiceNoticeScope.values());
		model.addAttribute("severities", ServiceNoticeSeverity.values());
		return "admin/notices/list";
	}

	@PostMapping("/admin/notices/page")
	@PreAuthorize("hasAuthority('admin.operations.manage')")
	@Transactional
	String publish(
		@ModelAttribute NoticeForm form,
		Authentication authentication,
		HttpServletRequest request
	) {
		ServiceNotice published = service.publish(form.toCommand(), authentication.getName());
		auditWriter.noticeChange(
			authentication, request, published.id(), "PUBLISH_NOTICE",
			AdminAuditOutcome.SUCCESS, "service notice published");
		return "redirect:/admin/notices/page";
	}

	@PostMapping("/admin/notices/{id}/unpublish/page")
	@PreAuthorize("hasAuthority('admin.operations.manage')")
	@Transactional
	String unpublish(
		@PathVariable String id,
		Authentication authentication,
		HttpServletRequest request
	) {
		if (repository.findById(id).isEmpty()) {
			throw new ResourceNotFoundException("운행 공지를 찾을 수 없습니다: " + id);
		}
		service.unpublish(id);
		auditWriter.noticeChange(
			authentication, request, id, "UNPUBLISH_NOTICE",
			AdminAuditOutcome.SUCCESS, "service notice unpublished");
		return "redirect:/admin/notices/page";
	}

	record NoticeForm(
		String scope,
		String scopeValue,
		String title,
		String body,
		String severity,
		String expiresAt
	) {
		PublishNoticeCommand toCommand() {
			ServiceNoticeScope parsedScope = parseEnum(ServiceNoticeScope.class, scope, "scope");
			return new PublishNoticeCommand(
				parsedScope,
				parsedScope == ServiceNoticeScope.ALL ? null : scopeValue,
				title,
				body,
				parseEnum(ServiceNoticeSeverity.class, severity, "severity"),
				parseExpiresAt(expiresAt));
		}

		private static LocalDateTime parseExpiresAt(String value) {
			if (value == null || value.isBlank()) {
				return null;
			}
			try {
				return LocalDateTime.parse(value);
			} catch (DateTimeParseException exception) {
				throw new InvalidRequestException("expiresAt 형식이 올바르지 않습니다.", exception);
			}
		}

		private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String field) {
			if (value == null || value.isBlank()) {
				throw new InvalidRequestException(field + "은(는) 필수입니다.");
			}
			try {
				return Enum.valueOf(type, value);
			} catch (IllegalArgumentException exception) {
				throw new InvalidRequestException("알 수 없는 " + field + ": " + value, exception);
			}
		}
	}

	record ServiceNoticeView(
		String id,
		String scope,
		String scopeValue,
		String title,
		String body,
		String severity,
		LocalDateTime publishedAt,
		LocalDateTime expiresAt,
		String publishedBy
	) {
		static ServiceNoticeView from(ServiceNotice notice) {
			return new ServiceNoticeView(
				notice.id(),
				notice.scope().name(),
				notice.scopeValue() == null ? "-" : notice.scopeValue(),
				notice.title(),
				notice.body(),
				notice.severity().name(),
				notice.publishedAt(),
				notice.expiresAt(),
				notice.publishedBy());
		}
	}
}

package com.easysubway.ads.adapter.in.web;

import com.easysubway.admin.audit.application.service.AdminAuditWriter;
import com.easysubway.admin.audit.domain.AdminAuditOutcome;
import com.easysubway.ads.application.service.AdService;
import com.easysubway.ads.domain.AdCreative;
import com.easysubway.common.error.InvalidRequestException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@PreAuthorize("hasAuthority('admin.operations.manage')")
class AdminAdsPageController {

	private final AdService service;
	private final AdminAuditWriter auditWriter;

	AdminAdsPageController(AdService service, AdminAuditWriter auditWriter) {
		this.service = service;
		this.auditWriter = auditWriter;
	}

	@GetMapping("/admin/ads/page")
	String page(Model model) {
		model.addAttribute("creatives", service.creatives());
		return "admin/ads/list";
	}

	@PostMapping("/admin/ads")
	@Transactional
	String save(
		@ModelAttribute AdForm form,
		Authentication authentication,
		HttpServletRequest request
	) {
		String reason = cleanReason(form.reason());
		AdCreative creative = form.toCreative();
		AdService.SaveResult result = service.saveCreative(creative);
		auditWriter.adminAction(
			authentication, request, "AD_CREATIVE", creative.id(),
			result == AdService.SaveResult.CREATED ? "CREATE_AD_CREATIVE" : "UPDATE_AD_CREATIVE",
			AdminAuditOutcome.SUCCESS, reason);
		return "redirect:/admin/ads/page";
	}

	@PostMapping("/admin/ads/{id}/enable")
	@Transactional
	String enable(
		@PathVariable String id,
		@RequestParam String reason,
		Authentication authentication,
		HttpServletRequest request
	) {
		return setEnabled(id, true, reason, authentication, request);
	}

	@PostMapping("/admin/ads/{id}/disable")
	@Transactional
	String disable(
		@PathVariable String id,
		@RequestParam String reason,
		Authentication authentication,
		HttpServletRequest request
	) {
		return setEnabled(id, false, reason, authentication, request);
	}

	private String setEnabled(
		String id,
		boolean enabled,
		String reason,
		Authentication authentication,
		HttpServletRequest request
	) {
		reason = cleanReason(reason);
		service.setCreativeEnabled(id, enabled);
		auditWriter.adminAction(
			authentication, request, "AD_CREATIVE", id,
			enabled ? "ENABLE_AD_CREATIVE" : "DISABLE_AD_CREATIVE",
			AdminAuditOutcome.SUCCESS, reason);
		return "redirect:/admin/ads/page";
	}

	private static String cleanReason(String reason) {
		if (reason == null || reason.isBlank()) {
			throw new InvalidRequestException("변경 사유가 필요합니다.");
		}
		String cleaned = reason.trim();
		if (cleaned.length() > 500) {
			throw new InvalidRequestException("변경 사유는 500자 이하여야 합니다.");
		}
		return cleaned;
	}

	record AdForm(
		String id,
		String placementId,
		String advertiserName,
		String imageUrl,
		String landingUrl,
		String altText,
		String startsAt,
		String endsAt,
		String reason
	) {
		AdCreative toCreative() {
			return new AdCreative(
				id, placementId, imageUrl, landingUrl, advertiserName, altText,
				parseInstant(startsAt, "startsAt"), parseOptionalInstant(endsAt, "endsAt"), false);
		}

		private static LocalDateTime parseOptionalInstant(String value, String field) {
			return value == null || value.isBlank() ? null : parseInstant(value, field);
		}

		private static LocalDateTime parseInstant(String value, String field) {
			try {
				return LocalDateTime.ofInstant(Instant.parse(value), ZoneOffset.UTC);
			} catch (DateTimeParseException | NullPointerException exception) {
				throw new InvalidRequestException(field + " 형식이 올바르지 않습니다.", exception);
			}
		}
	}
}

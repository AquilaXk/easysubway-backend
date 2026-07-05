package com.easysubway.admin.savedview.adapter.in.web;

import com.easysubway.admin.audit.application.service.AdminAuditWriter;
import com.easysubway.admin.audit.domain.AdminAuditOutcome;
import com.easysubway.admin.savedview.application.port.in.AdminSavedViewUseCase;
import com.easysubway.admin.savedview.application.port.in.SaveAdminSavedViewCommand;
import com.easysubway.admin.savedview.domain.AdminSavedView;
import com.easysubway.admin.savedview.domain.AdminSavedViewNotFoundException;
import com.easysubway.admin.savedview.domain.InvalidAdminSavedViewException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 관리자 표준 테이블(#1737) 계정별 저장된 뷰 변경. 모든 변경은 command token(전역 인터셉터)과
 * CSRF로 보호되고 AdminAuditWriter로 감사된다. 처리 후에는 안전 검증된 returnTo로 되돌아간다.
 */
@Controller
class AdminSavedViewController {

	private static final String DEFAULT_RETURN_TO = "/admin/dashboard/page";

	private final AdminSavedViewUseCase savedViewUseCase;
	private final AdminAuditWriter auditWriter;

	AdminSavedViewController(AdminSavedViewUseCase savedViewUseCase, AdminAuditWriter auditWriter) {
		this.savedViewUseCase = savedViewUseCase;
		this.auditWriter = auditWriter;
	}

	@PostMapping("/admin/saved-views")
	String saveView(
		@RequestParam String programId,
		@RequestParam String name,
		@RequestParam(required = false) String queryParams,
		@RequestParam(defaultValue = "false") boolean makeDefault,
		@RequestParam(required = false) String returnTo,
		Authentication authentication,
		HttpServletRequest request,
		RedirectAttributes redirectAttributes
	) {
		String loginId = authentication.getName();
		try {
			AdminSavedView view = savedViewUseCase.saveView(new SaveAdminSavedViewCommand(
				loginId, programId, name, queryParams == null ? "" : queryParams, makeDefault));
			auditWriter.savedViewChange(authentication, request, view.viewId(),
				"SAVE_VIEW", AdminAuditOutcome.SUCCESS, "저장된 뷰 저장: " + view.name());
			flash(redirectAttributes, "저장된 뷰 '%s'를 저장했습니다.".formatted(view.name()), "good");
		} catch (InvalidAdminSavedViewException exception) {
			auditWriter.savedViewChange(authentication, request, "-",
				"SAVE_VIEW", AdminAuditOutcome.FAILURE, exception.getMessage());
			flash(redirectAttributes, exception.getMessage(), "failure");
		}
		return "redirect:" + safeReturnTo(returnTo);
	}

	@PostMapping("/admin/saved-views/{viewId}/default")
	String setDefault(
		@PathVariable String viewId,
		@RequestParam(required = false) String returnTo,
		Authentication authentication,
		HttpServletRequest request,
		RedirectAttributes redirectAttributes
	) {
		String loginId = authentication.getName();
		try {
			AdminSavedView view = savedViewUseCase.setDefaultView(loginId, viewId);
			auditWriter.savedViewChange(authentication, request, view.viewId(),
				"SET_DEFAULT_VIEW", AdminAuditOutcome.SUCCESS, "기본 뷰 지정: " + view.name());
			flash(redirectAttributes, "'%s'를 기본 뷰로 지정했습니다.".formatted(view.name()), "good");
		} catch (AdminSavedViewNotFoundException exception) {
			auditWriter.savedViewChange(authentication, request, viewId,
				"SET_DEFAULT_VIEW", AdminAuditOutcome.FAILURE, exception.getMessage());
			flash(redirectAttributes, "저장된 뷰를 찾을 수 없습니다.", "failure");
		}
		return "redirect:" + safeReturnTo(returnTo);
	}

	@PostMapping("/admin/saved-views/{viewId}/delete")
	String deleteView(
		@PathVariable String viewId,
		@RequestParam(required = false) String returnTo,
		Authentication authentication,
		HttpServletRequest request,
		RedirectAttributes redirectAttributes
	) {
		String loginId = authentication.getName();
		try {
			AdminSavedView view = savedViewUseCase.deleteView(loginId, viewId);
			auditWriter.savedViewChange(authentication, request, view.viewId(),
				"DELETE_VIEW", AdminAuditOutcome.SUCCESS, "저장된 뷰 삭제: " + view.name());
			flash(redirectAttributes, "저장된 뷰 '%s'를 삭제했습니다.".formatted(view.name()), "good");
		} catch (AdminSavedViewNotFoundException exception) {
			auditWriter.savedViewChange(authentication, request, viewId,
				"DELETE_VIEW", AdminAuditOutcome.FAILURE, exception.getMessage());
			flash(redirectAttributes, "저장된 뷰를 찾을 수 없습니다.", "failure");
		}
		return "redirect:" + safeReturnTo(returnTo);
	}

	private static void flash(RedirectAttributes redirectAttributes, String message, String tone) {
		redirectAttributes.addFlashAttribute("flashMessage", message);
		redirectAttributes.addFlashAttribute("flashTone", tone);
	}

	// open redirect 방지: 관리자 내부 경로만 허용한다.
	private static String safeReturnTo(String returnTo) {
		if (returnTo != null
			&& returnTo.startsWith("/admin/")
			&& !returnTo.startsWith("/admin//")
			&& !returnTo.contains("://")) {
			return returnTo;
		}
		return DEFAULT_RETURN_TO;
	}
}

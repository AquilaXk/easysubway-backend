package com.easysubway.admin.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice(annotations = Controller.class)
class AdminCommandTokenModelAdvice {

	private final AdminCommandTokenService commandTokenService;

	AdminCommandTokenModelAdvice(AdminCommandTokenService commandTokenService) {
		this.commandTokenService = commandTokenService;
	}

	@ModelAttribute
	void exposeAdminCommandToken(HttpServletRequest request, Model model) {
		if (isAdminHtmlPage(request)) {
			model.addAttribute("commandTokens", new AdminCommandTokens(commandTokenService, request));
		}
	}

	private static boolean isAdminHtmlPage(HttpServletRequest request) {
		String path = AdminHtmlRequest.pathWithinApplication(request);
		return AdminHtmlRequest.matches(request)
			&& !path.startsWith("/admin/error")
			&& !path.equals("/admin/login");
	}

	static final class AdminCommandTokens {

		private final AdminCommandTokenService commandTokenService;
		private final HttpServletRequest request;

		AdminCommandTokens(AdminCommandTokenService commandTokenService, HttpServletRequest request) {
			this.commandTokenService = commandTokenService;
			this.request = request;
		}

		public String next() {
			return commandTokenService.issue(request);
		}
	}
}

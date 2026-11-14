package com.easysubway.admin.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
class AdminCommandTokenInterceptor implements HandlerInterceptor {

	private final AdminCommandTokenService commandTokenService;

	AdminCommandTokenInterceptor(AdminCommandTokenService commandTokenService) {
		this.commandTokenService = commandTokenService;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (!requiresCommandToken(request)) {
			return true;
		}
		String token = request.getParameter(AdminCommandTokenService.PARAMETER_NAME);
		if (!StringUtils.hasText(token)) {
			token = request.getHeader(AdminCommandTokenService.HEADER_NAME);
		}
		commandTokenService.consume(request, token);
		return true;
	}

	private static boolean requiresCommandToken(HttpServletRequest request) {
		if (!AdminHtmlRequest.isStateChangingMethod(request)) {
			return false;
		}
		String path = AdminHtmlRequest.pathWithinApplication(request);
		if (!path.startsWith("/admin/")
			|| path.equals("/admin/login")
			|| path.equals("/admin/logout")
			|| path.startsWith("/admin/error")
			|| path.startsWith("/admin/api/datapack/")) {
			return false;
		}
		return AdminHtmlRequest.isSessionAuthenticated(request)
			|| AdminHtmlRequest.isBrowserFormContentType(request)
			|| AdminHtmlRequest.matches(request);
	}
}

package com.easysubway.admin.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
class AdminCommandTokenInterceptor implements HandlerInterceptor {

	private final AdminCommandTokenService commandTokenService;

	AdminCommandTokenInterceptor(AdminCommandTokenService commandTokenService) {
		this.commandTokenService = commandTokenService;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (!isAdminFormPost(request)) {
			return true;
		}
		String token = request.getParameter(AdminCommandTokenService.PARAMETER_NAME);
		commandTokenService.consume(request, token);
		return true;
	}

	private static boolean isAdminFormPost(HttpServletRequest request) {
		String path = AdminHtmlRequest.pathWithinApplication(request);
		return "POST".equals(request.getMethod())
			&& path.startsWith("/admin/")
			&& !path.equals("/admin/login")
			&& !path.startsWith("/admin/error")
			&& AdminHtmlRequest.isFormUrlEncoded(request);
	}
}

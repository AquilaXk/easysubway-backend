package com.easysubway.common.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

public final class LoginNoticeFlash implements AuthenticationFailureHandler {

	private static final String SESSION_ATTRIBUTE = LoginNoticeFlash.class.getName() + ".notice";

	@Override
	public void onAuthenticationFailure(
		HttpServletRequest request,
		HttpServletResponse response,
		AuthenticationException exception
	) throws IOException, ServletException {
		String loginPath = loginPath(request);
		request.getSession().setAttribute(sessionAttribute(loginPath), LoginNotice.RETRY_WARNING);
		response.sendRedirect(request.getContextPath() + loginPath);
	}

	public LoginNotice consume(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session == null) {
			return LoginNotice.NONE;
		}
		String sessionAttribute = sessionAttribute(loginPath(request));
		Object notice = session.getAttribute(sessionAttribute);
		session.removeAttribute(sessionAttribute);
		return notice == LoginNotice.RETRY_WARNING ? LoginNotice.RETRY_WARNING : LoginNotice.NONE;
	}

	private static String loginPath(HttpServletRequest request) {
		String requestPath = request.getRequestURI().substring(request.getContextPath().length());
		return "/operator/login".equals(requestPath) ? "/operator/login" : "/admin/login";
	}

	private static String sessionAttribute(String loginPath) {
		return SESSION_ATTRIBUTE + loginPath;
	}
}

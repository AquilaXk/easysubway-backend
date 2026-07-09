package com.easysubway.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

final class HtmxRefreshAuthenticationEntryPoint implements AuthenticationEntryPoint {

	private static final String HX_REQUEST = "HX-Request";
	private static final String HX_REFRESH = "HX-Refresh";

	@Override
	public void commence(
		HttpServletRequest request,
		HttpServletResponse response,
		AuthenticationException authException
	) throws IOException {
		response.setHeader(HX_REFRESH, "true");
		response.setStatus(HttpServletResponse.SC_NO_CONTENT);
	}

	static boolean matches(HttpServletRequest request) {
		return "true".equalsIgnoreCase(request.getHeader(HX_REQUEST));
	}
}

package com.easysubway.admin.web;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

public class AdminHtmlAccessDeniedHandler implements AccessDeniedHandler {

	@Override
	public void handle(
		HttpServletRequest request,
		HttpServletResponse response,
		AccessDeniedException accessDeniedException
	) throws IOException, ServletException {
		if (!AdminHtmlRequest.matches(request)) {
			response.sendError(HttpServletResponse.SC_FORBIDDEN, accessDeniedException.getMessage());
			return;
		}
		response.setStatus(HttpServletResponse.SC_FORBIDDEN);
		request.setAttribute("adminErrorStatus", HttpServletResponse.SC_FORBIDDEN);
		request.setAttribute("adminErrorTitle", "권한이 없습니다");
		request.setAttribute("adminErrorMessage", "이 관리자 기능을 사용할 권한이 없습니다.");
		request.setAttribute("adminErrorDetail", "필요한 역할과 권한을 확인해 주세요.");
		request.getRequestDispatcher("/admin/error/page").forward(request, response);
	}
}

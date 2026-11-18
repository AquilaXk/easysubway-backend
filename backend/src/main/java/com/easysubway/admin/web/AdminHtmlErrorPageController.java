package com.easysubway.admin.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
class AdminHtmlErrorPageController {

	@RequestMapping("/admin/error/page")
	String errorPage(HttpServletRequest request, HttpServletResponse response, Model model) {
		int status = intAttribute(request, "adminErrorStatus", HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
		response.setStatus(status);
		model.addAttribute("status", status);
		model.addAttribute("title", stringAttribute(request, "adminErrorTitle", "요청을 처리하지 못했습니다"));
		model.addAttribute("message", stringAttribute(request, "adminErrorMessage", "관리자 요청 처리 중 오류가 발생했습니다."));
		model.addAttribute("detail", stringAttribute(
			request,
			"adminErrorDetail",
			"잠시 후 다시 시도하고, 문제가 반복되면 운영 로그를 확인해 주세요."
		));
		return "admin/error";
	}

	private static int intAttribute(HttpServletRequest request, String name, int defaultValue) {
		Object value = request.getAttribute(name);
		return value instanceof Number number ? number.intValue() : defaultValue;
	}

	private static String stringAttribute(HttpServletRequest request, String name, String defaultValue) {
		Object value = request.getAttribute(name);
		return value instanceof String text && !text.isBlank() ? text : defaultValue;
	}
}

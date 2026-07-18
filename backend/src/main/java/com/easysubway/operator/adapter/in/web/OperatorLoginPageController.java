package com.easysubway.operator.adapter.in.web;

import com.easysubway.common.security.LoginNoticeFlash;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class OperatorLoginPageController {

	private final LoginNoticeFlash loginNoticeFlash;

	OperatorLoginPageController(LoginNoticeFlash loginNoticeFlash) {
		this.loginNoticeFlash = loginNoticeFlash;
	}

	@GetMapping("/operator/login")
	String loginPage(HttpServletRequest request, Model model) {
		model.addAttribute("loginNotice", loginNoticeFlash.consume(request));
		return "operator/login";
	}
}

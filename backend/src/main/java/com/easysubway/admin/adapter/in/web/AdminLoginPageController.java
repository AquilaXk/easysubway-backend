package com.easysubway.admin.adapter.in.web;

import com.easysubway.common.security.LoginNoticeFlash;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class AdminLoginPageController {

	private final LoginNoticeFlash loginNoticeFlash;

	AdminLoginPageController(LoginNoticeFlash loginNoticeFlash) {
		this.loginNoticeFlash = loginNoticeFlash;
	}

	@GetMapping("/admin/login")
	String loginPage(HttpServletRequest request, Model model) {
		model.addAttribute("loginNotice", loginNoticeFlash.consume(request));
		return "admin/login";
	}
}

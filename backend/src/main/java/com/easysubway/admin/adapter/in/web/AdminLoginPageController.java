package com.easysubway.admin.adapter.in.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class AdminLoginPageController {

	@GetMapping("/admin/login")
	String loginPage() {
		return "admin/login";
	}
}

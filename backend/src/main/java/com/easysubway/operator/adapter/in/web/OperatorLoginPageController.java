package com.easysubway.operator.adapter.in.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class OperatorLoginPageController {

	@GetMapping("/operator/login")
	String loginPage() {
		return "operator/login";
	}
}

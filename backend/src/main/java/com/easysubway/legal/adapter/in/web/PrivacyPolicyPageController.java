package com.easysubway.legal.adapter.in.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class PrivacyPolicyPageController {

	@GetMapping({ "/privacy", "/easysubway/privacy" })
	String privacyPolicy() {
		return "legal/privacy";
	}

	@GetMapping({ "/terms", "/easysubway/terms" })
	String terms() {
		return "legal/terms";
	}

	@GetMapping({ "/location-terms", "/easysubway/location-terms" })
	String locationTerms() {
		return "legal/location-terms";
	}
}

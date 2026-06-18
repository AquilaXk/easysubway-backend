package com.easysubway.operator.adapter.in.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class OperatorRepeatedBrokenFacilitiesPageController {

	private final OperatorRepeatedBrokenFacilitiesAssembler repeatedBrokenFacilitiesAssembler;

	OperatorRepeatedBrokenFacilitiesPageController(
		OperatorRepeatedBrokenFacilitiesAssembler repeatedBrokenFacilitiesAssembler
	) {
		this.repeatedBrokenFacilitiesAssembler = repeatedBrokenFacilitiesAssembler;
	}

	@GetMapping("/operator/repeated-broken-facilities/page")
	String repeatedBrokenFacilitiesPage(Model model) {
		model.addAttribute("report", repeatedBrokenFacilitiesAssembler.assemble());
		return "operator/repeated-broken-facilities";
	}
}

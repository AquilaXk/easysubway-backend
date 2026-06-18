package com.easysubway.operator.adapter.in.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class OperatorDataCollectionFailuresPageController {

	private final OperatorDataCollectionFailuresAssembler dataCollectionFailuresAssembler;

	OperatorDataCollectionFailuresPageController(
		OperatorDataCollectionFailuresAssembler dataCollectionFailuresAssembler
	) {
		this.dataCollectionFailuresAssembler = dataCollectionFailuresAssembler;
	}

	@GetMapping("/operator/data-collection-failures/page")
	String dataCollectionFailuresPage(Model model) {
		model.addAttribute("report", dataCollectionFailuresAssembler.assemble());
		return "operator/data-collection-failures";
	}
}

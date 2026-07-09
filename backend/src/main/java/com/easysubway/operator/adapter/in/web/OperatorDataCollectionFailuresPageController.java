package com.easysubway.operator.adapter.in.web;

import java.time.LocalDate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
class OperatorDataCollectionFailuresPageController {

	private final OperatorDataCollectionFailuresAssembler dataCollectionFailuresAssembler;

	OperatorDataCollectionFailuresPageController(
		OperatorDataCollectionFailuresAssembler dataCollectionFailuresAssembler
	) {
		this.dataCollectionFailuresAssembler = dataCollectionFailuresAssembler;
	}

	@GetMapping("/operator/data-collection-failures/page")
	String dataCollectionFailuresPage(
		@RequestParam(required = false) String q,
		@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
		@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
		@RequestParam(required = false) String sort,
		@RequestParam(required = false) String direction,
		Model model
	) {
		OperatorReportQuery query = OperatorReportQuery.of(q, from, to, sort, direction);
		model.addAttribute("query", query);
		model.addAttribute("report", dataCollectionFailuresAssembler.assemble(query));
		return "operator/data-collection-failures";
	}
}

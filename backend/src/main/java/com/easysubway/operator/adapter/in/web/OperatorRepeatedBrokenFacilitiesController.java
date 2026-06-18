package com.easysubway.operator.adapter.in.web;

import com.easysubway.common.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class OperatorRepeatedBrokenFacilitiesController {

	private final OperatorRepeatedBrokenFacilitiesAssembler repeatedBrokenFacilitiesAssembler;

	OperatorRepeatedBrokenFacilitiesController(
		OperatorRepeatedBrokenFacilitiesAssembler repeatedBrokenFacilitiesAssembler
	) {
		this.repeatedBrokenFacilitiesAssembler = repeatedBrokenFacilitiesAssembler;
	}

	@GetMapping("/operator/api/repeated-broken-facilities")
	ApiResponse<OperatorRepeatedBrokenFacilitiesView> repeatedBrokenFacilities() {
		return ApiResponse.ok(repeatedBrokenFacilitiesAssembler.assemble());
	}
}

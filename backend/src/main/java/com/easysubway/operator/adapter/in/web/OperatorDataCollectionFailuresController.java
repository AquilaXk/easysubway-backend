package com.easysubway.operator.adapter.in.web;

import com.easysubway.common.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class OperatorDataCollectionFailuresController {

	private final OperatorDataCollectionFailuresAssembler dataCollectionFailuresAssembler;

	OperatorDataCollectionFailuresController(
		OperatorDataCollectionFailuresAssembler dataCollectionFailuresAssembler
	) {
		this.dataCollectionFailuresAssembler = dataCollectionFailuresAssembler;
	}

	@GetMapping("/operator/api/data-collection-failures")
	ApiResponse<OperatorDataCollectionFailuresView> dataCollectionFailures() {
		return ApiResponse.ok(dataCollectionFailuresAssembler.assemble());
	}
}

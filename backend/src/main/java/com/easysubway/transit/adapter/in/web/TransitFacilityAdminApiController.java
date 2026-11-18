package com.easysubway.transit.adapter.in.web;

import com.easysubway.common.web.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class TransitFacilityAdminApiController {

	private final TransitFacilityStatusAssembler facilityStatusAssembler;

	TransitFacilityAdminApiController(TransitFacilityStatusAssembler facilityStatusAssembler) {
		this.facilityStatusAssembler = facilityStatusAssembler;
	}

	@GetMapping("/admin/facilities/summary")
	ApiResponse<List<FacilityStatusRow>> facilityStatusSummary() {
		return ApiResponse.ok(facilityStatusAssembler.assemble());
	}
}

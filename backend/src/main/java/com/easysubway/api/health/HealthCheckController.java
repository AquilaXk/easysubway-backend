package com.easysubway.api.health;

import com.easysubway.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class HealthCheckController {

	@GetMapping("/api/health")
	ApiResponse<HealthCheckResponse> health() {
		return ApiResponse.ok(new HealthCheckResponse("UP", "easysubway-backend"));
	}

	record HealthCheckResponse(String status, String service) {
	}
}

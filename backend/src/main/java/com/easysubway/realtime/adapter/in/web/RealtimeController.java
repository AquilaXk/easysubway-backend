package com.easysubway.realtime.adapter.in.web;

import com.easysubway.common.web.ApiResponse;
import com.easysubway.realtime.application.RealtimeArrivalResult;
import com.easysubway.realtime.application.RealtimeGatewayService;
import com.easysubway.realtime.application.RealtimeQuery;
import com.easysubway.realtime.application.RealtimeTrainPositionResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RealtimeController {

	private final RealtimeGatewayService realtimeGatewayService;

	RealtimeController(RealtimeGatewayService realtimeGatewayService) {
		this.realtimeGatewayService = realtimeGatewayService;
	}

	@GetMapping("/api/v1/realtime/arrivals")
	ApiResponse<RealtimeArrivalResult> arrivals(
		@RequestParam String stationId,
		@RequestParam(required = false) String lineId,
		@RequestParam(required = false) String providerLineId,
		@RequestParam String stationQueryName
	) {
		return ApiResponse.ok(realtimeGatewayService.arrivals(new RealtimeQuery(
			stationId,
			lineId,
			providerLineId,
			stationQueryName,
			null
		)));
	}

	@GetMapping("/api/v1/realtime/train-positions")
	ApiResponse<RealtimeTrainPositionResult> trainPositions(
		@RequestParam(required = false) String lineId,
		@RequestParam(required = false) String providerLineId,
		@RequestParam String lineName
	) {
		return ApiResponse.ok(realtimeGatewayService.trainPositions(new RealtimeQuery(
			null,
			lineId,
			providerLineId,
			null,
			lineName
		)));
	}
}

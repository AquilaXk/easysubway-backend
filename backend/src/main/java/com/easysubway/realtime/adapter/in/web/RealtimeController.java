package com.easysubway.realtime.adapter.in.web;

import com.easysubway.common.error.InvalidRequestException;
import com.easysubway.common.web.ApiResponse;
import com.easysubway.realtime.application.RealtimeArrivalResult;
import com.easysubway.realtime.application.RealtimeGatewayService;
import com.easysubway.realtime.application.RealtimeQuery;
import com.easysubway.realtime.application.RealtimeTrainPositionResult;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RealtimeController {

	private static final Set<String> SECRET_BEARING_PARAMETER_NAMES = Set.of(
		"apikey",
		"api_key",
		"key",
		"providerurl",
		"provider_url",
		"secret",
		"servicekey",
		"service_key",
		"token",
		"url"
	);

	private final RealtimeGatewayService realtimeGatewayService;

	RealtimeController(RealtimeGatewayService realtimeGatewayService) {
		this.realtimeGatewayService = realtimeGatewayService;
	}

	@GetMapping("/api/v1/realtime/arrivals")
	ApiResponse<RealtimeArrivalResult> arrivals(
		@RequestParam String stationId,
		@RequestParam(required = false) String lineId,
		@RequestParam(required = false) String providerLineId,
		@RequestParam String stationQueryName,
		HttpServletRequest request
	) {
		rejectSecretBearingProviderParameters(request);
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
		@RequestParam String lineName,
		HttpServletRequest request
	) {
		rejectSecretBearingProviderParameters(request);
		return ApiResponse.ok(realtimeGatewayService.trainPositions(new RealtimeQuery(
			null,
			lineId,
			providerLineId,
			null,
			lineName
		)));
	}

	private void rejectSecretBearingProviderParameters(HttpServletRequest request) {
		for (String parameterName : request.getParameterMap().keySet()) {
			String normalized = parameterName.toLowerCase(Locale.ROOT).replace("-", "_");
			if (SECRET_BEARING_PARAMETER_NAMES.contains(normalized)) {
				throw new InvalidRequestException("실시간 provider credential은 앱/API 요청에 포함할 수 없습니다.");
			}
		}
	}
}

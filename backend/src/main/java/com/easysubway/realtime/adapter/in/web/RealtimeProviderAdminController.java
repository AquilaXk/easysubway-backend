package com.easysubway.realtime.adapter.in.web;

import com.easysubway.common.web.ApiResponse;
import com.easysubway.realtime.application.RealtimeGatewayService;
import com.easysubway.realtime.application.RealtimeProviderControl;
import com.easysubway.realtime.application.RealtimeProviderHealthSnapshot;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class RealtimeProviderAdminController {

	private static final String PROVIDER_ID = "seoul-topis";

	private final RealtimeGatewayService realtimeGatewayService;
	private final RealtimeProviderControl providerControl;

	RealtimeProviderAdminController(
		RealtimeGatewayService realtimeGatewayService,
		RealtimeProviderControl providerControl
	) {
		this.realtimeGatewayService = realtimeGatewayService;
		this.providerControl = providerControl;
	}

	@GetMapping("/admin/realtime/providers/health")
	ApiResponse<RealtimeProviderHealthSnapshot> health() {
		return ApiResponse.ok(realtimeGatewayService.providerHealthSnapshot());
	}

	@PostMapping("/admin/realtime/providers/{providerId}/disable")
	@PreAuthorize("hasAuthority('admin.data.operate')")
	ApiResponse<RealtimeProviderHealthSnapshot> disableProvider(
		@PathVariable String providerId,
		@RequestParam(required = false) String reason
	) {
		validateProvider(providerId);
		providerControl.disableProvider(providerId, reason);
		return ApiResponse.ok(realtimeGatewayService.providerHealthSnapshot());
	}

	@PostMapping("/admin/realtime/providers/{providerId}/enable")
	@PreAuthorize("hasAuthority('admin.data.operate')")
	ApiResponse<RealtimeProviderHealthSnapshot> enableProvider(@PathVariable String providerId) {
		validateProvider(providerId);
		providerControl.enableProvider(providerId);
		return ApiResponse.ok(realtimeGatewayService.providerHealthSnapshot());
	}

	private void validateProvider(String providerId) {
		if (!PROVIDER_ID.equals(providerId)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 실시간 provider입니다.");
		}
	}
}

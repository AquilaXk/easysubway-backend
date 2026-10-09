package com.easysubway.datapack.adapter.in.web;

import com.easysubway.datapack.application.port.in.AutomationStatusUseCase;
import com.easysubway.datapack.domain.InvalidAutomationStatusException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * data 레포 workflow가 게시하는 자동화 상태 snapshot 수신(backend#500, data#1084).
 * 인증은 워크플로 서비스 토큰(Bearer) 필터 체인이 처리한다. 본문은 검증을 통과한 것만 마지막 1건으로 보관한다.
 */
@RestController
public class AutomationStatusApiController {

	/** 본문 상한(바이트). 계약 snapshot은 이보다 훨씬 작다. */
	static final int MAX_BODY_BYTES = 64 * 1024;

	private final AutomationStatusUseCase useCase;

	public AutomationStatusApiController(AutomationStatusUseCase useCase) {
		this.useCase = useCase;
	}

	@PostMapping(path = "/admin/api/datapack/automation-status", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, String>> receive(@RequestBody byte[] body) {
		if (body.length > MAX_BODY_BYTES) {
			return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("status", "TOO_LARGE"));
		}
		var result = useCase.receive(new String(body, java.nio.charset.StandardCharsets.UTF_8));
		return ResponseEntity.ok(Map.of("status", result.name()));
	}

	@ExceptionHandler(InvalidAutomationStatusException.class)
	ResponseEntity<Map<String, String>> handleInvalid(InvalidAutomationStatusException invalid) {
		return ResponseEntity.badRequest().body(Map.of("status", "INVALID", "reason", invalid.getMessage()));
	}
}

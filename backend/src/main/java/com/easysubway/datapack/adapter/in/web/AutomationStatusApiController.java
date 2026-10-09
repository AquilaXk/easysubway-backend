package com.easysubway.datapack.adapter.in.web;

import com.easysubway.datapack.application.port.in.AutomationStatusUseCase;
import com.easysubway.datapack.domain.InvalidAutomationStatusException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
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
	public ResponseEntity<Map<String, String>> receive(HttpServletRequest request) throws IOException {
		// 상한을 넘는 본문은 메모리에 올리지 않는다: 선언된 길이로 먼저 거르고, 선언이 없거나 거짓이어도 상한 + 1바이트까지만 읽는다.
		if (request.getContentLengthLong() > MAX_BODY_BYTES) {
			return tooLarge();
		}
		byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
		if (body.length > MAX_BODY_BYTES) {
			return tooLarge();
		}
		var result = useCase.receive(new String(body, java.nio.charset.StandardCharsets.UTF_8));
		return ResponseEntity.ok(Map.of("status", result.name()));
	}

	private static ResponseEntity<Map<String, String>> tooLarge() {
		return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("status", "TOO_LARGE"));
	}

	@ExceptionHandler(InvalidAutomationStatusException.class)
	ResponseEntity<Map<String, String>> handleInvalid(InvalidAutomationStatusException invalid) {
		return ResponseEntity.badRequest().body(Map.of("status", "INVALID", "reason", invalid.getMessage()));
	}
}

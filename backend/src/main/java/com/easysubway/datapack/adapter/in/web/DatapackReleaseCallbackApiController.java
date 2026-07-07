package com.easysubway.datapack.adapter.in.web;

import com.easysubway.datapack.application.service.DatapackReleaseCallbackService;
import com.easysubway.datapack.application.service.DatapackReleaseCallbackService.CallbackCommand;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 워크플로가 보내는 release-callback payload를 수신하는 REST 엔드포인트.
 * 인증은 Part A의 WorkflowServiceTokenFilter(Bearer 서비스 토큰)가 처리한다. 추가 보안 로직 없음.
 */
@RestController
public class DatapackReleaseCallbackApiController {

    private final DatapackReleaseCallbackService service;

    public DatapackReleaseCallbackApiController(DatapackReleaseCallbackService service) {
        this.service = service;
    }

    @PostMapping("/admin/api/datapack/release-callbacks")
    public ResponseEntity<Map<String, Object>> receiveCallback(
            @RequestBody CallbackRequestBody body) {
        var verifier = body.callbackVerifier();
        var cmd = new CallbackCommand(
            body.schemaVersion(),
            body.artifactKind(),
            body.releaseRequestId(),
            body.workflowRunUrl(),
            body.manifestSha256(),
            body.sqliteSha256(),
            body.gzipSha256(),
            body.evidenceBundleSha256(),
            body.validatorStatus(),
            body.routeRegressionStatus(),
            body.publishStatus(),
            verifier != null ? verifier.kind() : null,
            verifier != null ? verifier.value() : null
        );
        var result = service.receive(cmd);
        return ResponseEntity.ok(Map.of(
            "status", result.status(),
            "idempotentReplay", result.idempotentReplay()
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Void> handleIllegalArgument(IllegalArgumentException e) {
        String msg = e.getMessage() != null ? e.getMessage() : "";
        if (msg.equals("callback verifier mismatch")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (msg.startsWith("release request not found")) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.badRequest().build();
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Void> handleIllegalState(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    record CallbackVerifierBody(String kind, String value) {}

    record CallbackRequestBody(
        int schemaVersion,
        String artifactKind,
        String releaseRequestId,
        String workflowRunUrl,
        String manifestSha256,
        String sqliteSha256,
        String gzipSha256,
        String evidenceBundleSha256,
        String validatorStatus,
        String routeRegressionStatus,
        String publishStatus,
        CallbackVerifierBody callbackVerifier
    ) {}
}

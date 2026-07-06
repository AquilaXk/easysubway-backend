package com.easysubway.datapack.adapter.in.web;

import com.easysubway.datapack.application.service.DatapackReleaseRequestService;
import com.easysubway.datapack.domain.DatapackReleaseRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DatapackReleaseRequestApiController {

	private final DatapackReleaseRequestService service;

	public DatapackReleaseRequestApiController(DatapackReleaseRequestService service) {
		this.service = service;
	}

	@GetMapping("/admin/api/datapack/release-requests/{approvalId}")
	public ResponseEntity<Map<String, Object>> get(@PathVariable String approvalId) {
		return service.findApproved(approvalId)
			.map(DatapackReleaseRequestApiController::toSchemaBody)
			.map(ResponseEntity::ok)
			.orElseGet(() -> ResponseEntity.notFound().build());
	}

	// release-request.schema.json required 11필드와 1:1. additionalProperties:false이므로
	// status·workflowRunUrl 등 내부 필드는 응답에서 제외한다.
	private static Map<String, Object> toSchemaBody(DatapackReleaseRequest r) {
		var body = new LinkedHashMap<String, Object>();
		body.put("schemaVersion", 1);
		body.put("artifactKind", "datapack-release-request");
		body.put("candidateId", r.candidateId());
		body.put("scopeId", r.scopeId());
		body.put("buildSpecSha256", r.buildSpecSha256());
		body.put("sourceSnapshotSetHash", r.sourceSnapshotSetHash());
		body.put("approvedLedgerHash", r.approvedLedgerHash());
		body.put("requestedBy", r.requestedBy());
		body.put("approvedBy", r.approvedBy());
		body.put("approvalId", r.approvalId());
		body.put("targetChannel", r.targetChannel());
		return body;
	}
}

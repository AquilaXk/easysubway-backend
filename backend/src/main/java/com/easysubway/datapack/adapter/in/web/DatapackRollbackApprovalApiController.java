package com.easysubway.datapack.adapter.in.web;

import com.easysubway.datapack.adapter.out.persistence.JdbcDatapackReleaseChannelRepository;
import com.easysubway.datapack.adapter.out.persistence.JdbcDatapackReleaseChannelRepository.ReleaseChannelEventRow;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DatapackRollbackApprovalApiController {

	private static final String APPROVER_ROLE = "admin.datapack.rollback";
	private static final String REASON_CODE = "ADMIN_APPROVED_ROLLBACK";

	private final JdbcDatapackReleaseChannelRepository repository;

	public DatapackRollbackApprovalApiController(JdbcDatapackReleaseChannelRepository repository) {
		this.repository = repository;
	}

	@GetMapping("/admin/api/datapack/rollback-approvals/{eventId}")
	public ResponseEntity<Map<String, Object>> get(@PathVariable String eventId) {
		return repository.findEventById(eventId)
			.filter(event -> "ROLLBACK".equals(event.operationType()))
			.filter(event -> "PASS".equals(event.operationStatus()))
			.map(DatapackRollbackApprovalApiController::toSchemaBody)
			.map(ResponseEntity::ok)
			.orElseGet(() -> ResponseEntity.notFound().build());
	}

	private static Map<String, Object> toSchemaBody(ReleaseChannelEventRow event) {
		var body = new LinkedHashMap<String, Object>();
		body.put("schemaVersion", 1);
		body.put("artifactKind", "datapack-rollback-approval");
		body.put("rollbackApprovalEventId", event.id());
		body.put("targetChannel", event.channel());
		body.put("failedManifestSha256", event.previousManifestSha256());
		body.put("knownGoodManifestSha256", event.nextManifestSha256());
		body.put("approvedBy", event.approvedBy());
		body.put("approvedByRole", APPROVER_ROLE);
		body.put("approvedAt", event.createdAt().atZone(ZoneId.systemDefault())
			.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
		body.put("reasonCode", REASON_CODE);
		return body;
	}
}

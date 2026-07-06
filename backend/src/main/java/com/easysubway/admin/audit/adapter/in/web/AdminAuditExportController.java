package com.easysubway.admin.audit.adapter.in.web;

import com.easysubway.admin.audit.application.AdminAuditQuery;
import com.easysubway.admin.audit.application.port.out.AdminAuditEventRepository;
import com.easysubway.admin.audit.application.service.AdminAuditWriter;
import com.easysubway.admin.audit.domain.AdminAuditEvent;
import com.easysubway.admin.audit.domain.AdminAuditEventType;
import com.easysubway.admin.audit.domain.AdminAuditOutcome;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 감사·개인정보 조회 로그 내보내기(#1747). 현재 필터 상태 그대로 CSV(UTF-8 BOM)·JSON으로 내보낸다.
 * 목록과 같은 질의를 공유해 화면·내보내기 결과가 정합하며, 행 수 상한을 두고 누가 무엇을 내보냈는지
 * 감사에 남긴다(감사 이벤트는 민감정보 free-text를 담지 못하므로 내보내기에 PII가 없음).
 *
 * <p>개인정보 로그 내보내기는 개인정보 로그 권한을 요구하고 PRIVACY_READ로 유형을 강제한다(권한 분리).
 */
@RestController
class AdminAuditExportController {

	// CSV는 UTF-8 BOM으로 시작해 엑셀에서 한글이 깨지지 않게 한다.
	private static final String UTF8_BOM = "﻿";
	// 방어적 행 수 상한(대량 내보내기 방지). 필터를 좁혀 나눠 받도록 유도한다.
	private static final int MAX_EXPORT_ROWS = 5000;

	private final AdminAuditEventRepository auditEventRepository;
	private final AdminAuditWriter auditWriter;
	private final ObjectMapper objectMapper;

	AdminAuditExportController(
		AdminAuditEventRepository auditEventRepository,
		AdminAuditWriter auditWriter,
		ObjectMapper objectMapper
	) {
		this.auditEventRepository = auditEventRepository;
		this.auditWriter = auditWriter;
		this.objectMapper = objectMapper;
	}

	@GetMapping("/admin/audits/export")
	@PreAuthorize("hasAuthority('admin.audit.read')")
	ResponseEntity<byte[]> exportAudits(
		AuditFilterParams params,
		@RequestParam(name = "format", defaultValue = "csv") String format,
		Authentication authentication,
		HttpServletRequest request
	) {
		return export(params, format, null, true, "admin-audit", authentication, request);
	}

	@GetMapping("/admin/audits/privacy/export")
	@PreAuthorize("hasAuthority('admin.privacy-log.read')")
	ResponseEntity<byte[]> exportPrivacyAudits(
		AuditFilterParams params,
		@RequestParam(name = "format", defaultValue = "csv") String format,
		Authentication authentication,
		HttpServletRequest request
	) {
		return export(params, format, AdminAuditEventType.PRIVACY_READ, false, "privacy-audit", authentication, request);
	}

	private ResponseEntity<byte[]> export(
		AuditFilterParams params,
		String format,
		AdminAuditEventType forcedEventType,
		boolean excludePrivacyRead,
		String filenameBase,
		Authentication authentication,
		HttpServletRequest request
	) {
		AdminAuditQuery query = params.toQuery(forcedEventType, excludePrivacyRead, 0, null);
		List<AdminAuditEvent> events = auditEventRepository.findForExport(query, MAX_EXPORT_ROWS);
		boolean json = "json".equalsIgnoreCase(format);

		byte[] body = (json ? toJson(events) : toCsv(events)).getBytes(StandardCharsets.UTF_8);

		auditWriter.auditExport(
			authentication,
			request,
			filenameBase,
			"EXPORT_AUDIT_" + (json ? "JSON" : "CSV"),
			AdminAuditOutcome.SUCCESS,
			"업무 맥락: 감사 로그 내보내기 format=%s rows=%d".formatted(json ? "json" : "csv", events.size())
		);

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(json
			? MediaType.APPLICATION_JSON
			: new MediaType("text", "csv", StandardCharsets.UTF_8));
		headers.setContentDisposition(ContentDisposition.attachment()
			.filename("%s-%s.%s".formatted(filenameBase, events.size(), json ? "json" : "csv"))
			.build());
		return ResponseEntity.ok().headers(headers).body(body);
	}

	private static String toCsv(List<AdminAuditEvent> events) {
		StringBuilder csv = new StringBuilder(UTF8_BOM);
		csv.append("occurred_at,event_type,actor,role_permission,request_id,client_ip,user_agent,")
			.append("target_type,target_id,action,outcome,reason\r\n");
		for (AdminAuditEvent event : events) {
			csv.append(csvField(event.occurredAt().toString())).append(',')
				.append(csvField(event.eventType().name())).append(',')
				.append(csvField(event.actor())).append(',')
				.append(csvField(nullToEmpty(event.rolePermission()))).append(',')
				.append(csvField(nullToEmpty(event.requestId()))).append(',')
				.append(csvField(nullToEmpty(event.clientIp()))).append(',')
				.append(csvField(nullToEmpty(event.userAgent()))).append(',')
				.append(csvField(event.targetType())).append(',')
				.append(csvField(nullToEmpty(event.targetId()))).append(',')
				.append(csvField(event.action())).append(',')
				.append(csvField(event.outcome().name())).append(',')
				.append(csvField(nullToEmpty(event.reason()))).append("\r\n");
		}
		return csv.toString();
	}

	private String toJson(List<AdminAuditEvent> events) {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (AdminAuditEvent event : events) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("occurredAt", event.occurredAt().toString());
			row.put("eventType", event.eventType().name());
			row.put("actor", event.actor());
			row.put("rolePermission", event.rolePermission());
			row.put("requestId", event.requestId());
			row.put("clientIp", event.clientIp());
			row.put("userAgent", event.userAgent());
			row.put("targetType", event.targetType());
			row.put("targetId", event.targetId());
			row.put("action", event.action());
			row.put("outcome", event.outcome().name());
			row.put("reason", event.reason());
			rows.add(row);
		}
		try {
			return objectMapper.writeValueAsString(rows);
		} catch (JsonProcessingException exception) {
			return "[]";
		}
	}

	private static String nullToEmpty(String value) {
		return value == null ? "" : value;
	}

	private static String csvField(String rawValue) {
		// CSV/수식 인젝션(CWE-1236) 방어: user-agent·client-ip 등 헤더 유래 값이 =,+,-,@로 시작하면
		// 엑셀 등이 수식으로 해석·실행할 수 있으므로 작은따옴표를 앞에 붙여 무력화한다.
		String value = rawValue;
		if (!value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0) {
			value = "'" + value;
		}
		if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
			return '"' + value.replace("\"", "\"\"") + '"';
		}
		return value;
	}
}

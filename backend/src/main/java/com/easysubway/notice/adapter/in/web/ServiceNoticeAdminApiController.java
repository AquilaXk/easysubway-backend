package com.easysubway.notice.adapter.in.web;

import com.easysubway.admin.audit.application.service.AdminAuditWriter;
import com.easysubway.admin.audit.domain.AdminAuditOutcome;
import com.easysubway.common.web.ApiResponse;
import com.easysubway.notice.application.service.ServiceNoticeService;
import com.easysubway.notice.domain.ServiceNotice;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 공지 발행·즉시 내리기. 공지는 되돌리기 쉬워 4-eyes 불요이나, 발행 이력은
 * AdminAuditWriter로 남긴다. 권한·인증은 admin security chain(OPERATIONS_MANAGE)이 강제.
 */
@RestController
class ServiceNoticeAdminApiController {

	private final ServiceNoticeService service;
	private final AdminAuditWriter auditWriter;

	ServiceNoticeAdminApiController(ServiceNoticeService service, AdminAuditWriter auditWriter) {
		this.service = service;
		this.auditWriter = auditWriter;
	}

	@PostMapping("/admin/notices")
	ResponseEntity<ApiResponse<NoticeResponse>> publish(
		@RequestBody PublishNoticeRequest request,
		Authentication authentication,
		HttpServletRequest httpRequest
	) {
		ServiceNotice published = service.publish(request.toCommand(), authentication.getName());
		auditWriter.noticeChange(
			authentication, httpRequest, published.id(), "PUBLISH_NOTICE",
			AdminAuditOutcome.SUCCESS, "service notice published");
		return ResponseEntity.ok(ApiResponse.ok(NoticeResponse.from(published)));
	}

	@PostMapping("/admin/notices/{id}/unpublish")
	ResponseEntity<ApiResponse<Void>> unpublish(
		@PathVariable String id,
		Authentication authentication,
		HttpServletRequest httpRequest
	) {
		service.unpublish(id);
		auditWriter.noticeChange(
			authentication, httpRequest, id, "UNPUBLISH_NOTICE",
			AdminAuditOutcome.SUCCESS, "service notice unpublished");
		return ResponseEntity.ok(ApiResponse.ok(null));
	}
}

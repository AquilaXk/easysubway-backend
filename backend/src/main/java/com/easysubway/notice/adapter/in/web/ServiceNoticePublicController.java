package com.easysubway.notice.adapter.in.web;

import com.easysubway.common.web.ApiResponse;
import com.easysubway.notice.application.service.ServiceNoticeService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.DigestUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

/**
 * 활성 운행 공지 공개 조회. 인증 없음, 쿼리 파라미터 없음 — 어떤 식별자도 받지 않는다.
 * 공지는 실시간성 overlay라 max-age=60 공개 캐시 + ETag 조건부 요청으로 트래픽을 줄인다.
 */
@RestController
class ServiceNoticePublicController {

	private final ServiceNoticeService service;

	ServiceNoticePublicController(ServiceNoticeService service) {
		this.service = service;
	}

	@GetMapping("/api/notices/active")
	ResponseEntity<ApiResponse<List<NoticeResponse>>> activeNotices(WebRequest webRequest) {
		List<NoticeResponse> notices = service.activeNotices().stream()
			.map(NoticeResponse::from)
			.toList();
		String etag = etagFor(notices);
		CacheControl cacheControl = CacheControl.maxAge(60, TimeUnit.SECONDS).cachePublic();

		if (etag.equals(webRequest.getHeader("If-None-Match"))) {
			return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
				.eTag(etag)
				.cacheControl(cacheControl)
				.build();
		}
		return ResponseEntity.ok()
			.eTag(etag)
			.cacheControl(cacheControl)
			.body(ApiResponse.ok(notices));
	}

	private static String etagFor(List<NoticeResponse> notices) {
		StringBuilder fingerprint = new StringBuilder();
		for (NoticeResponse notice : notices) {
			fingerprint.append(notice.id())
				.append('@').append(notice.publishedAt())
				.append('~').append(notice.expiresAt())
				.append(';');
		}
		return "\"" + DigestUtils.md5DigestAsHex(
			fingerprint.toString().getBytes(StandardCharsets.UTF_8)) + "\"";
	}
}

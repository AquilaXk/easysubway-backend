package com.easysubway.notice.adapter.in.web;

import com.easysubway.common.web.UtcTimestamps;
import com.easysubway.notice.domain.ServiceNotice;
import java.time.Instant;

/**
 * 공개 공지 응답 DTO. 발행자(publishedBy)는 내부 정보라 노출하지 않는다.
 */
public record NoticeResponse(
	String id,
	String scope,
	String scopeValue,
	String title,
	String body,
	String severity,
	Instant publishedAt,
	Instant expiresAt
) {

	public static NoticeResponse from(ServiceNotice notice) {
		return new NoticeResponse(
			notice.id(),
			notice.scope().name(),
			notice.scopeValue(),
			notice.title(),
			notice.body(),
			notice.severity().name(),
			UtcTimestamps.toInstant(notice.publishedAt()),
			UtcTimestamps.toInstant(notice.expiresAt())
		);
	}
}

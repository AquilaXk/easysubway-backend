package com.easysubway.notice.application.service;

import com.easysubway.notice.domain.ServiceNoticeScope;
import com.easysubway.notice.domain.ServiceNoticeSeverity;
import java.time.LocalDateTime;

/**
 * 공지 발행 커맨드. id·게시 시각·발행자는 서비스가 채운다.
 */
public record PublishNoticeCommand(
	ServiceNoticeScope scope,
	String scopeValue,
	String title,
	String body,
	ServiceNoticeSeverity severity,
	LocalDateTime expiresAt
) {
}

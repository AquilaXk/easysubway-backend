package com.easysubway.notice.adapter.in.web;

import com.easysubway.common.error.InvalidRequestException;
import com.easysubway.notice.application.service.PublishNoticeCommand;
import com.easysubway.notice.domain.ServiceNoticeScope;
import com.easysubway.notice.domain.ServiceNoticeSeverity;
import java.time.LocalDateTime;

/**
 * 관리자 공지 발행 요청. scope/severity는 문자열로 받아 도메인 enum으로 변환한다.
 */
public record PublishNoticeRequest(
	String scope,
	String scopeValue,
	String title,
	String body,
	String severity,
	LocalDateTime expiresAt
) {

	public PublishNoticeCommand toCommand() {
		return new PublishNoticeCommand(
			parseEnum(ServiceNoticeScope.class, scope, "scope"),
			scopeValue,
			title,
			body,
			parseEnum(ServiceNoticeSeverity.class, severity, "severity"),
			expiresAt
		);
	}

	/**
	 * null·알 수 없는 값이면 400(InvalidRequestException)으로 매핑한다. 잘못된 요청
	 * 본문이 500으로 새어 나가지 않게 한다.
	 */
	private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String field) {
		if (raw == null) {
			throw new InvalidRequestException(field + "은(는) 필수입니다.");
		}
		try {
			return Enum.valueOf(type, raw);
		} catch (IllegalArgumentException exception) {
			throw new InvalidRequestException("알 수 없는 " + field + ": " + raw);
		}
	}
}

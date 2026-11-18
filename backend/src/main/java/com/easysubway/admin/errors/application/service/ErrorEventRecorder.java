package com.easysubway.admin.errors.application.service;

import com.easysubway.admin.errors.domain.ErrorEvent;
import com.easysubway.common.error.ErrorCategory;
import com.easysubway.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 5xx SYSTEM/DEPENDENCY 오류를 {@code error_events}에 비동기 집계한다.
 * 기록 실패는 WARN만 남기고 사용자 응답 경로에 영향을 주지 않는다.
 */
@Service
public class ErrorEventRecorder {

	private static final Logger log = LoggerFactory.getLogger(ErrorEventRecorder.class);
	private static final String UNKNOWN_PATH_PATTERN = "UNKNOWN";

	private final ErrorEventAsyncWriter asyncWriter;
	private final Clock clock;

	@Autowired
	ErrorEventRecorder(ErrorEventAsyncWriter asyncWriter) {
		this(asyncWriter, Clock.systemUTC());
	}

	public ErrorEventRecorder(ErrorEventAsyncWriter asyncWriter, Clock clock) {
		this.asyncWriter = asyncWriter;
		this.clock = clock;
	}

	/**
	 * 요청 스레드에서 민감하지 않은 필드만 추출한 뒤 비동기 저장을 예약한다.
	 */
	public void recordIfNeeded(
		HttpServletRequest request,
		ErrorCode errorCode,
		Throwable exception,
		String correlationId
	) {
		if (errorCode == null || exception == null) {
			return;
		}
		if (errorCode.category() == ErrorCategory.USER || errorCode.httpStatus().value() < 500) {
			return;
		}
		try {
			Instant now = clock.instant();
			ErrorEvent event = new ErrorEvent(
				null,
				now,
				now,
				errorCode.code(),
				errorCode.category().name(),
				errorCode.httpStatus().value(),
				method(request),
				pathPattern(request),
				exception.getClass().getName(),
				ErrorStackHash.of(exception),
				correlationId == null ? "" : correlationId,
				1L
			);
			asyncWriter.persist(event);
		}
		catch (RuntimeException failure) {
			log.warn(
				"error event schedule failed code={} exceptionType={}",
				errorCode.code(),
				exception.getClass().getName(),
				failure
			);
		}
	}

	private static String method(HttpServletRequest request) {
		if (request == null || request.getMethod() == null || request.getMethod().isBlank()) {
			return "UNKNOWN";
		}
		String method = request.getMethod().trim();
		return method.length() <= 8 ? method : method.substring(0, 8);
	}

	/**
	 * 핸들러 매핑 패턴만 저장한다. path variable 원본 URI는 폴백으로 쓰지 않는다.
	 */
	static String pathPattern(HttpServletRequest request) {
		if (request == null) {
			return UNKNOWN_PATH_PATTERN;
		}
		Object bestPattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
		if (bestPattern instanceof String pattern && !pattern.isBlank() && !"/**".equals(pattern)) {
			return pattern.length() <= 255 ? pattern : pattern.substring(0, 255);
		}
		return UNKNOWN_PATH_PATTERN;
	}
}

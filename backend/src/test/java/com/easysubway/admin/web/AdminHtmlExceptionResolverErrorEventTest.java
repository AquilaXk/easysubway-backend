package com.easysubway.admin.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.easysubway.admin.errors.application.service.ErrorEventRecorder;
import com.easysubway.common.error.ErrorCode;
import com.easysubway.common.error.InvalidRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName("관리자 HTML 예외 리졸버 오류 이벤트")
class AdminHtmlExceptionResolverErrorEventTest {

	@Test
	@DisplayName("관리자 HTML 500은 error_events 기록기를 호출한다")
	void recordsInternalErrorForAdminHtml500() {
		ErrorEventRecorder recorder = mock(ErrorEventRecorder.class);
		AdminHtmlExceptionResolver resolver = new AdminHtmlExceptionResolver(recorder);
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/errors/page");
		request.addHeader("Accept", "text/html");
		MockHttpServletResponse response = new MockHttpServletResponse();

		resolver.resolveException(request, response, new Object(), new IllegalStateException("boom"));

		verify(recorder).recordIfNeeded(eq(request), eq(ErrorCode.INTERNAL_ERROR), any(IllegalStateException.class), any());
	}

	@Test
	@DisplayName("관리자 HTML 4xx는 error_events에 기록하지 않는다")
	void doesNotRecordClientErrors() {
		ErrorEventRecorder recorder = mock(ErrorEventRecorder.class);
		AdminHtmlExceptionResolver resolver = new AdminHtmlExceptionResolver(recorder);
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/errors/page");
		request.addHeader("Accept", "text/html");
		MockHttpServletResponse response = new MockHttpServletResponse();

		resolver.resolveException(request, response, new Object(), new InvalidRequestException("bad"));

		verify(recorder, never()).recordIfNeeded(any(), any(), any(), any());
	}
}

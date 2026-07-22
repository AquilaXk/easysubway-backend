package com.easysubway.admin.errors.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.easysubway.admin.errors.domain.ErrorEvent;
import com.easysubway.common.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

@DisplayName("오류 이벤트 기록기")
class ErrorEventRecorderTest {

	private final ErrorEventAsyncWriter asyncWriter = mock(ErrorEventAsyncWriter.class);
	private final ErrorEventRecorder recorder = new ErrorEventRecorder(
		asyncWriter,
		Clock.fixed(Instant.parse("2026-07-10T00:00:00Z"), ZoneOffset.UTC)
	);

	@Test
	@DisplayName("USER 4xx는 저장하지 않는다")
	void doesNotRecordUserErrors() {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/demo");
		recorder.recordIfNeeded(
			request,
			ErrorCode.INVALID_REQUEST,
			new IllegalArgumentException("bad"),
			"corr-1"
		);
		verifyNoInteractions(asyncWriter);
	}

	@Test
	@DisplayName("path_pattern은 BEST_MATCHING_PATTERN만 쓰고 원본 URI를 저장하지 않는다")
	void storesHandlerMappingPatternOnly() {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/stations/station-1");
		request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/stations/{stationId}");

		recorder.recordIfNeeded(
			request,
			ErrorCode.INTERNAL_ERROR,
			new IllegalStateException("boom"),
			"corr-2"
		);

		ArgumentCaptor<ErrorEvent> captor = ArgumentCaptor.forClass(ErrorEvent.class);
		verify(asyncWriter).persist(captor.capture());
		assertThat(captor.getValue().pathPattern()).isEqualTo("/api/stations/{stationId}");
		assertThat(captor.getValue().pathPattern()).doesNotContain("station-1");
		assertThat(captor.getValue().exceptionClass()).isEqualTo("java.lang.IllegalStateException");
	}

	@Test
	@DisplayName("매핑 패턴이 없으면 UNKNOWN을 쓰고 request URI를 저장하지 않는다")
	void fallsBackToUnknownWithoutRawUri() {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/stations/station-raw");
		recorder.recordIfNeeded(
			request,
			ErrorCode.INTERNAL_ERROR,
			new IllegalStateException("boom"),
			"corr-3"
		);

		ArgumentCaptor<ErrorEvent> captor = ArgumentCaptor.forClass(ErrorEvent.class);
		verify(asyncWriter).persist(captor.capture());
		assertThat(captor.getValue().pathPattern()).isEqualTo("UNKNOWN");
		assertThat(captor.getValue().pathPattern()).doesNotContain("station-raw");
	}

	@Test
	@DisplayName("비동기 저장 예약 예외는 삼키고 호출부를 깨지 않는다")
	void scheduleFailureIsSwallowed() {
		ErrorEventAsyncWriter failing = mock(ErrorEventAsyncWriter.class);
		org.mockito.Mockito.doThrow(new RuntimeException("queue down")).when(failing).persist(any());
		ErrorEventRecorder failingRecorder = new ErrorEventRecorder(
			failing,
			Clock.fixed(Instant.parse("2026-07-10T00:00:00Z"), ZoneOffset.UTC)
		);

		failingRecorder.recordIfNeeded(
			new MockHttpServletRequest("GET", "/api/x"),
			ErrorCode.INTERNAL_ERROR,
			new IllegalStateException("boom"),
			"corr-4"
		);

		verify(failing).persist(any());
	}
}

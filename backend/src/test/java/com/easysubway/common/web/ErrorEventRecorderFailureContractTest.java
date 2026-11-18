package com.easysubway.common.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.admin.errors.application.service.ErrorEventAsyncWriter;
import com.easysubway.admin.errors.application.service.ErrorEventRecorder;
import com.easysubway.admin.errors.domain.ErrorEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@DisplayName("오류 이벤트 기록 실패가 응답에 영향을 주지 않음")
class ErrorEventRecorderFailureContractTest {

	@Test
	@DisplayName("기록기 런타임 예외가 있어도 catch-all 500 계약은 유지된다")
	void recorderFailureKeepsInternalErrorResponse() throws Exception {
		ErrorEventAsyncWriter failingWriter = mock(ErrorEventAsyncWriter.class);
		doThrow(new IllegalStateException("recorder down")).when(failingWriter).persist(any(ErrorEvent.class));
		ErrorEventRecorder recorder = new ErrorEventRecorder(
			failingWriter,
			Clock.fixed(Instant.parse("2026-07-10T00:00:00Z"), ZoneOffset.UTC)
		);

		var mockMvc = MockMvcBuilders.standaloneSetup(new BoomController())
			.setControllerAdvice(new CommonExceptionHandler(
				WebMessageResolver.defaultMessages(),
				recorder
			))
			.addFilters(new CorrelationIdFilter())
			.build();

		mockMvc.perform(get("/api/test/recorder-failure/boom"))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
			.andExpect(jsonPath("$.correlationId").isString());
	}

	@RestController
	static class BoomController {
		@GetMapping("/api/test/recorder-failure/boom")
		String boom() {
			throw new IllegalStateException("boom");
		}
	}
}

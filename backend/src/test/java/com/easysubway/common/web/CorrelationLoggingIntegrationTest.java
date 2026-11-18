package com.easysubway.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.common.error.CorrelationId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.layout.template.json.JsonTemplateLayout;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@DisplayName("correlationId 로깅 계약")
class CorrelationLoggingIntegrationTest {

	private MockMvc mockMvc;
	private CapturingAppender capturingAppender;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(new CorrelationLoggingProbeController())
			.setControllerAdvice(new CommonExceptionHandler(WebMessageResolver.defaultMessages()))
			.addFilters(new CorrelationIdFilter())
			.build();
		capturingAppender = CapturingAppender.attach(CommonExceptionHandler.class.getName());
	}

	@AfterEach
	void tearDown() {
		if (capturingAppender != null) {
			capturingAppender.detach();
		}
	}

	@Test
	@DisplayName("유효한 X-Correlation-Id는 응답 헤더·바디·ERROR 로그 MDC에 동일하게 실린다")
	void acceptedCorrelationIdRoundTripsToLogs() throws Exception {
		String correlationId = "test-abc";

		MvcResult result = mockMvc.perform(get("/api/test/correlation-logging/boom")
				.header(CorrelationId.HEADER, correlationId))
			.andExpect(status().isInternalServerError())
			.andExpect(header().string(CorrelationId.HEADER, correlationId))
			.andExpect(jsonPath("$.correlationId").value(correlationId))
			.andReturn();

		assertThat(result.getResponse().getHeader(CorrelationId.HEADER)).isEqualTo(correlationId);

		LogEvent errorEvent = capturingAppender.requireErrorEvent();
		Object mdcValue = errorEvent.getContextData().getValue(CorrelationId.MDC_KEY);
		assertThat(mdcValue).isEqualTo(correlationId);
		assertThat(errorEvent.getThrown()).isNotNull();
		assertThat(errorEvent.getMessage().getFormattedMessage()).contains(correlationId);

		String json = renderJson(errorEvent);
		JsonNode node = new ObjectMapper().readTree(json);
		assertThat(node.path("correlationId").asText()).isEqualTo(correlationId);
		assertThat(node.path("level").asText()).isEqualTo("ERROR");
		assertThat(node.path("stack_trace").asText()).contains("RuntimeException");
		assertThat(node.has("@timestamp") || node.has("timestamp")).isTrue();
		assertThat(node.path("logger").asText()).isNotBlank();
		assertThat(node.path("message").asText()).contains(correlationId);
	}

	@Test
	@DisplayName("헤더가 없으면 UUID를 생성해 응답 헤더·바디·로그 MDC에 동일하게 싣는다")
	void missingHeaderGeneratesUuidRoundTrip() throws Exception {
		MvcResult result = mockMvc.perform(get("/api/test/correlation-logging/boom"))
			.andExpect(status().isInternalServerError())
			.andExpect(header().exists(CorrelationId.HEADER))
			.andExpect(jsonPath("$.correlationId").isString())
			.andReturn();

		String correlationId = result.getResponse().getHeader(CorrelationId.HEADER);
		assertThat(correlationId).isNotBlank();
		assertThat(UUID.fromString(correlationId)).isNotNull();
		assertThat(jsonPathString(result, "$.correlationId")).isEqualTo(correlationId);

		LogEvent errorEvent = capturingAppender.requireErrorEvent();
		Object mdcValue = errorEvent.getContextData().getValue(CorrelationId.MDC_KEY);
		assertThat(mdcValue).isEqualTo(correlationId);
		assertThat(errorEvent.getThrown()).isNotNull();
	}

	@Test
	@DisplayName("무효한 X-Correlation-Id는 버리고 UUID를 새로 발급한다")
	void invalidHeaderIsRejected() throws Exception {
		MvcResult result = mockMvc.perform(get("/api/test/correlation-logging/boom")
				.header(CorrelationId.HEADER, "bad id with spaces!!!"))
			.andExpect(status().isInternalServerError())
			.andExpect(header().exists(CorrelationId.HEADER))
			.andReturn();

		String correlationId = result.getResponse().getHeader(CorrelationId.HEADER);
		assertThat(correlationId).isNotEqualTo("bad id with spaces!!!");
		assertThat(CorrelationId.isValid(correlationId)).isTrue();
		assertThat(jsonPathString(result, "$.correlationId")).isEqualTo(correlationId);
	}

	@Test
	@DisplayName("dev 패턴 레이아웃은 correlationId MDC를 텍스트로 출력한다")
	void patternLayoutIncludesCorrelationId() {
		PatternLayout layout = PatternLayout.newBuilder()
			.withPattern("%d %-5level [%X{correlationId}] %msg%n%throwable")
			.build();
		CorrelationId.putMdc("pattern-id");
		try {
			Log4jLogEvent event = Log4jLogEvent.newBuilder()
				.setLoggerName("test")
				.setLevel(Level.INFO)
				.setMessage(new SimpleMessage("hello"))
				.setIncludeLocation(false)
				.build();
			String rendered = layout.toSerializable(event);
			assertThat(rendered).contains("[pattern-id]");
			assertThat(ThreadContext.get(CorrelationId.MDC_KEY)).isEqualTo("pattern-id");
		} finally {
			CorrelationId.clearMdc();
		}
	}

	private static String renderJson(LogEvent event) {
		LoggerContext context = (LoggerContext) LogManager.getContext(false);
		JsonTemplateLayout layout = JsonTemplateLayout.newBuilder()
			.setConfiguration(context.getConfiguration())
			.setEventTemplateUri("classpath:log4j2-json-template.json")
			.build();
		return new String(layout.toByteArray(event), StandardCharsets.UTF_8);
	}

	private static String jsonPathString(MvcResult result, String path) throws Exception {
		return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), path);
	}

	@RestController
	static class CorrelationLoggingProbeController {

		@GetMapping("/api/test/correlation-logging/boom")
		ApiResponse<Void> boom() {
			throw new RuntimeException("forced-500-for-logging");
		}
	}

	private static final class CapturingAppender extends AbstractAppender {

		private final String loggerName;
		private final List<LogEvent> events = new CopyOnWriteArrayList<>();

		private CapturingAppender(String loggerName) {
			super("correlation-capturing", null, null, true, Property.EMPTY_ARRAY);
			this.loggerName = loggerName;
		}

		static CapturingAppender attach(String loggerName) {
			CapturingAppender appender = new CapturingAppender(loggerName);
			appender.start();
			LoggerContext context = (LoggerContext) LogManager.getContext(false);
			org.apache.logging.log4j.core.Logger logger = context.getLogger(loggerName);
			logger.addAppender(appender);
			logger.setLevel(Level.ERROR);
			logger.setAdditive(true);
			return appender;
		}

		void detach() {
			LoggerContext context = (LoggerContext) LogManager.getContext(false);
			org.apache.logging.log4j.core.Logger logger = context.getLogger(loggerName);
			logger.removeAppender(this);
			stop();
		}

		@Override
		public void append(LogEvent event) {
			events.add(event.toImmutable());
		}

		LogEvent requireErrorEvent() {
			return events.stream()
				.filter(event -> event.getLevel() == Level.ERROR)
				.findFirst()
				.orElseThrow(() -> new AssertionError("expected ERROR log event, got " + events.size()));
		}
	}
}

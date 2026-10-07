package com.easysubway.journey.adapter.in.web;

import com.easysubway.journey.application.TestJourneyCandidates;
import com.easysubway.journey.application.TestRides;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.journey.analytics.JourneySearchRecordStore;
import com.easysubway.journey.analytics.JourneySearchLatencyMetrics;
import com.easysubway.journey.analytics.JourneySearchRecorder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.easysubway.journey.application.JourneyApplicationDeadlineExecutor;
import com.easysubway.journey.application.JourneyApplicationDeadlineExecutor.Completed;
import com.easysubway.journey.application.JourneyApplicationDeadlineExecutor.TimedOut;
import com.easysubway.journey.application.JourneyCandidate;
import com.easysubway.journey.application.JourneyExecutionFailure;
import com.easysubway.journey.application.JourneyExecutionFailure.Reason;
import com.easysubway.journey.application.JourneyExecutionResult;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.journey.application.JourneyProfileResourcePolicy;
import com.easysubway.journey.application.JourneySessionException;
import com.easysubway.journey.application.JourneySessionService;
import com.easysubway.journey.application.JourneySessionService.AuthorizedSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@DisplayName("Journey V3 authenticated search HTTP boundary")
class JourneySearchControllerTest {

	private static final String REQUEST_ID = "01K1Y000000000000000000000";
	private static final Instant NOW = Instant.parse("2026-08-12T00:00:00Z");
	private static final String SEARCH_WEB_ENABLED = "easysubway.journey-v3.search-web.enabled=true";
	private static final ObjectMapper JSON = new ObjectMapper();

	private JourneySessionService sessionService;
	private JourneyApplicationDeadlineExecutor deadlineExecutor;
	private JourneyProfileResourcePolicy resourcePolicy;
	private MockMvc mockMvc;
	private JourneySearchRecordStore recordStore;
	private JourneySearchRecorder recorder;
	private SimpleMeterRegistry meters;
	private JourneySearchLatencyMetrics latency;

	@BeforeEach
	void setUp() {
		sessionService = mock(JourneySessionService.class);
		recordStore = mock(JourneySearchRecordStore.class);
		meters = new SimpleMeterRegistry();
		latency = new JourneySearchLatencyMetrics(meters);
		recorder = new JourneySearchRecorder(recordStore, Runnable::run, Clock.fixed(NOW, ZoneOffset.UTC), new SimpleMeterRegistry());
		deadlineExecutor = mock(JourneyApplicationDeadlineExecutor.class);
		resourcePolicy = policy();
		mockMvc = MockMvcBuilders.standaloneSetup(new JourneySearchController(sessionService, deadlineExecutor, resourcePolicy, recorder, latency))
			.setControllerAdvice(new JourneySearchExceptionHandler(
				Clock.fixed(NOW, ZoneOffset.UTC),
				new SecureRandom(new byte[] {1, 2, 3, 4})
			))
			.build();
	}

	@Test
	@DisplayName("bearer를 한 번 authorize하고 NOW command를 exact success JSON으로 투영한다")
	void authorizesAndExecutesNowRequestOnce() throws Exception {
		assertConditionalRegistration();
		allowSession();
		when(deadlineExecutor.execute(any())).thenReturn(new Completed(success()));

		MvcResult result = perform(validRequest("{\"mode\":\"NOW\"}"))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
			.andExpect(jsonPath("$.contractVersion").value("JOURNEY_SEARCH_V3"))
			.andExpect(jsonPath("$.requestId").value(REQUEST_ID))
			.andExpect(jsonPath("$.serviceTimezone").value("Asia/Seoul"))
			.andExpect(jsonPath("$.serviceDayCutoff").value("03:00"))
			.andExpect(jsonPath("$.sourceIdentity.realtimeSnapshotId").value(org.hamcrest.Matchers.nullValue()))
			.andExpect(jsonPath("$.journeys[0].journeyId").value("journey-1"))
			.andReturn();

		assertThat(fields(result)).containsExactlyInAnyOrder(
			"contractVersion", "requestId", "queryId", "calculatedAt", "validUntil",
			"effectiveDepartureTime", "serviceDate", "serviceTimezone", "serviceDayCutoff", "sourceIdentity",
			"requestPolicy", "journeys", "stairFreeAlternative"
		);
		verify(sessionService).authorize("session-token", 2);
		var request = ArgumentCaptor.forClass(JourneyRequest.class);
		verify(deadlineExecutor).execute(request.capture());
		assertThat(request.getValue()).satisfies(command -> {
			assertThat(command.requestId()).isEqualTo(REQUEST_ID);
			assertThat(command.originStationId()).isEqualTo("station-origin");
			assertThat(command.destinationStationId()).isEqualTo("station-destination");
			assertThat(command.departure()).isEqualTo(new JourneyRequest.Departure.Now());
			assertThat(command.timePolicy()).isEqualTo(JourneyRequest.TimePolicy.TIMETABLE_REQUIRED);
			assertThat(command.walkingPace()).isEqualTo(JourneyRequest.WalkingPace.STANDARD);
			assertThat(command.mobilityProfile()).isEqualTo(JourneyRequest.MobilityProfile.STEP_FREE);
			assertThat(command.constraintMode()).isEqualTo(JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE);
			assertThat(command.maxTransfers()).isEqualTo(2);
			assertThat(command.alternativeCount()).isEqualTo(1);
			assertThat(command.isCancelled()).isFalse();
		});
	}

	@Test
	@DisplayName("SCHEDULED offset date-time을 exact instant command로 변환한다")
	void decodesScheduledDeparture() throws Exception {
		allowSession();
		when(deadlineExecutor.execute(any())).thenReturn(new Completed(success()));

		perform(validRequest("{\"mode\":\"SCHEDULED\",\"requestedAt\":\"2026-08-12T09:01:00+09:00\"}"))
			.andExpect(status().isOk());

		var request = ArgumentCaptor.forClass(JourneyRequest.class);
		verify(deadlineExecutor).execute(request.capture());
		assertThat(request.getValue().departure()).isEqualTo(
			new JourneyRequest.Departure.Scheduled(Instant.parse("2026-08-12T00:01:00Z"))
		);
	}

	@Test
	@DisplayName("deadline timeout은 request identity를 보존한 exact 504로 닫힌다")
	void mapsDeadlineTimeout() throws Exception {
		allowSession();
		when(deadlineExecutor.execute(any())).thenReturn(new TimedOut());

		assertError(post("/api/v3/journeys/search")
			.header(HttpHeaders.AUTHORIZATION, "Bearer session-token")
			.contentType(MediaType.APPLICATION_JSON)
			.content(validRequest("{\"mode\":\"NOW\"}")), 504, "JOURNEY_SEARCH_TIMEOUT", true);

		verify(deadlineExecutor).execute(any());
	}

	@Test
	@DisplayName("deadline execution failure는 request identity를 보존한 existing 503으로 닫힌다")
	void mapsDeadlineExecutionFailure() throws Exception {
		allowSession();
		when(deadlineExecutor.execute(any())).thenThrow(new RuntimeException("failed"));

		assertError(post("/api/v3/journeys/search")
			.header(HttpHeaders.AUTHORIZATION, "Bearer session-token")
			.contentType(MediaType.APPLICATION_JSON)
			.content(validRequest("{\"mode\":\"NOW\"}")), 503, "ROUTE_SERVICE_UNAVAILABLE", true);

		verify(deadlineExecutor).execute(any());
	}

	@Test
	@DisplayName("missing·malformed·rejected bearer는 application 호출 없이 exact 401이다")
	void rejectsUnauthorizedRequestsBeforeExecution() throws Exception {
		assertError(post("/api/v3/journeys/search")
			.contentType(MediaType.APPLICATION_JSON), 401, "ROUTE_SESSION_REQUIRED", false);
		for (String authorization : List.of("", "Basic session-token", "Bearer", "Bearer token extra")) {
			var request = post("/api/v3/journeys/search")
				.contentType(MediaType.APPLICATION_JSON)
				.content(validRequest("{\"mode\":\"NOW\"}"));
			if (!authorization.isEmpty()) request.header(HttpHeaders.AUTHORIZATION, authorization);
			assertError(request, 401, "ROUTE_SESSION_REQUIRED", false);
		}
		when(sessionService.authorize("rejected-token", 2))
			.thenThrow(new JourneySessionException(JourneySessionException.Kind.SESSION_REQUIRED));
		assertError(post("/api/v3/journeys/search")
			.header(HttpHeaders.AUTHORIZATION, "Bearer rejected-token")
			.contentType(MediaType.APPLICATION_JSON)
			.content(validRequest("{\"mode\":\"NOW\"}")), 401, "ROUTE_SESSION_REQUIRED", false);

		verify(sessionService).authorize("rejected-token", 2);
		verifyNoInteractions(deadlineExecutor);
	}

	@Test
	@DisplayName("Bearer scheme은 대소문자와 무관하게 authorize한다")
	void acceptsCaseInsensitiveBearerScheme() throws Exception {
		allowSession();
		when(deadlineExecutor.execute(any())).thenReturn(new Completed(success()));

		mockMvc.perform(post("/api/v3/journeys/search")
			.header(HttpHeaders.AUTHORIZATION, "bEaReR session-token")
			.contentType(MediaType.APPLICATION_JSON)
			.content(validRequest("{\"mode\":\"NOW\"}")))
			.andExpect(status().isOk());

		verify(sessionService).authorize("session-token", 2);
		verify(deadlineExecutor).execute(any());
	}

	@Test
	@DisplayName("401 session failure는 exact Bearer challenge를 포함한다")
	void challengesUnauthorizedClientWithBearerScheme() throws Exception {
		assertError(post("/api/v3/journeys/search")
			.contentType(MediaType.APPLICATION_JSON)
			.content(validRequest("{\"mode\":\"NOW\"}")), 401, "ROUTE_SESSION_REQUIRED", false);

		verifyNoInteractions(deadlineExecutor);
	}

	@Test
	@DisplayName("session lifetime limit은 body/application 실행 전에 exact 429로 닫힌다")
	void rejectsRateLimitedSessionBeforeRequestExecution() throws Exception {
		when(sessionService.authorize("session-token", 2))
			.thenThrow(new JourneySessionException(JourneySessionException.Kind.RATE_LIMITED));

		assertError(post("/api/v3/journeys/search")
			.header(HttpHeaders.AUTHORIZATION, "Bearer session-token")
			.contentType(MediaType.APPLICATION_JSON), 429, "ROUTE_RATE_LIMITED", false);

		verify(sessionService).authorize("session-token", 2);
		verifyNoInteractions(deadlineExecutor);
	}

	@Test
	@DisplayName("authorized request body read failure는 execute 없이 exact 400으로 닫힌다")
	void rejectsUnreadableAuthorizedRequestBeforeExecution() throws Exception {
		allowSession();
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getInputStream()).thenThrow(new IOException("unreadable"));

		var exception = assertThrows(
			JourneySearchController.JourneySearchWebException.class,
			() -> new JourneySearchController(sessionService, deadlineExecutor, resourcePolicy, recorder, latency)
				.search("Bearer session-token", request)
		);

		assertThat(exception.httpStatus()).isEqualTo(400);
		assertThat(exception.machineCode()).isEqualTo("INVALID_JOURNEY_REQUEST");
		verify(sessionService).authorize("session-token", 2);
		verifyNoInteractions(deadlineExecutor);
	}

	@Test
	@DisplayName("request body 크기가 maxRequestBytes를 초과하면 execute 없이 exact 400으로 닫힌다")
	void rejectsOversizedAuthorizedRequestBeforeExecution() throws Exception {
		allowSession();
		int maxBytes = 100;
		var controller = new JourneySearchController(sessionService, deadlineExecutor, resourcePolicy, maxBytes, recorder, latency);
		var mvc = MockMvcBuilders.standaloneSetup(controller)
			.setControllerAdvice(new JourneySearchExceptionHandler(
				Clock.fixed(NOW, ZoneOffset.UTC),
				new SecureRandom(new byte[] {1, 2, 3, 4})
			))
			.build();

		String largeBody = validRequest("{\"mode\":\"NOW\"}");
		assertThat(largeBody.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(maxBytes);

		assertError(mvc, post("/api/v3/journeys/search")
				.header(HttpHeaders.AUTHORIZATION, "Bearer session-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(largeBody), 400, "INVALID_JOURNEY_REQUEST", false);

		verify(sessionService).authorize("session-token", 2);
		verifyNoInteractions(deadlineExecutor);
	}

	@Test
	@DisplayName("request body 크기가 정확히 maxRequestBytes 이하이면 정상 수용되고 초과 시 차단된다")
	void acceptsExactBoundaryAndRejectsOneByteOver() throws Exception {
		allowSession();
		String validBody = validRequest("{\"mode\":\"NOW\"}");
		int exactLength = validBody.getBytes(StandardCharsets.UTF_8).length;

		// 정확히 exactLength 바이트 허용: 200 성공
		var allowedExecutor = mock(JourneyApplicationDeadlineExecutor.class);
		when(allowedExecutor.execute(any())).thenReturn(new Completed(success()));
		var allowedController = new JourneySearchController(sessionService, allowedExecutor, resourcePolicy, exactLength, recorder, latency);
		var allowedMvc = MockMvcBuilders.standaloneSetup(allowedController).build();
		allowedMvc.perform(post("/api/v3/journeys/search")
				.header(HttpHeaders.AUTHORIZATION, "Bearer session-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(validBody))
			.andExpect(status().isOk());
		verify(allowedExecutor).execute(any());

		// exactLength - 1 바이트 허용 (1바이트 부족): 400 차단
		var rejectedExecutor = mock(JourneyApplicationDeadlineExecutor.class);
		var rejectedController = new JourneySearchController(sessionService, rejectedExecutor, resourcePolicy, exactLength - 1, recorder, latency);
		var rejectedMvc = MockMvcBuilders.standaloneSetup(rejectedController)
			.setControllerAdvice(new JourneySearchExceptionHandler(Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom(new byte[] {1, 2, 3, 4})))
			.build();
		assertError(rejectedMvc, post("/api/v3/journeys/search")
				.header(HttpHeaders.AUTHORIZATION, "Bearer session-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(validBody), 400, "INVALID_JOURNEY_REQUEST", false);
		verifyNoInteractions(rejectedExecutor);
	}

	@Test
	@DisplayName("chunked/stream 방식으로 Content-Length 없이 maxRequestBytes를 초과하는 바디도 exact 400으로 차단된다")
	void rejectsOversizedStreamWithoutContentLengthBeforeExecution() throws Exception {
		allowSession();
		when(deadlineExecutor.execute(any())).thenReturn(new Completed(success()));
		String validBody = validRequest("{\"mode\":\"NOW\"}");
		byte[] validBytes = validBody.getBytes(StandardCharsets.UTF_8);
		int maxBytes = validBytes.length;
		var controller = new JourneySearchController(sessionService, deadlineExecutor, resourcePolicy, maxBytes, recorder, latency);

		byte[] streamBytes = new byte[maxBytes + 10];
		System.arraycopy(validBytes, 0, streamBytes, 0, maxBytes);
		Arrays.fill(streamBytes, maxBytes, streamBytes.length, (byte) ' ');
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getContentLengthLong()).thenReturn(-1L);
		var stream = new ServletInputStream() {
			private final InputStream delegate = new ByteArrayInputStream(streamBytes);
			@Override public boolean isFinished() { try { return delegate.available() == 0; } catch (IOException e) { return true; } }
			@Override public boolean isReady() { return true; }
			@Override public void setReadListener(ReadListener readListener) {}
			@Override public int read() throws IOException { return delegate.read(); }
		};
		when(request.getInputStream()).thenReturn(stream);

		var exception = assertThrows(
			JourneySearchController.JourneySearchWebException.class,
			() -> controller.search("Bearer session-token", request)
		);

		assertThat(exception.httpStatus()).isEqualTo(400);
		assertThat(exception.machineCode()).isEqualTo("INVALID_JOURNEY_REQUEST");
		verify(sessionService).authorize("session-token", 2);
		verifyNoInteractions(deadlineExecutor);
	}

	@Test
	@DisplayName("maxRequestBytes가 0 이하이면 생성자에서 IllegalArgumentException이 발생한다")
	void constructorRejectsNonPositiveMaxRequestBytes() {
		assertThrows(IllegalArgumentException.class,
			() -> new JourneySearchController(sessionService, deadlineExecutor, resourcePolicy, 0, recorder, latency));
		assertThrows(IllegalArgumentException.class,
			() -> new JourneySearchController(sessionService, deadlineExecutor, resourcePolicy, -1, recorder, latency));
	}

	@Test
	@DisplayName("@Value max-request-bytes 설정이 JourneySearchController에 올바르게 바인딩된다")
	void bindsMaxRequestBytesConfigurationProperty() {
		var runner = new ApplicationContextRunner()
			.withBean(JourneySessionService.class, () -> mock(JourneySessionService.class))
			.withBean(JourneySearchRecorder.class, () -> recorder)
			.withBean(JourneySearchLatencyMetrics.class, () -> latency)
			.withBean(JourneyApplicationDeadlineExecutor.class, () -> mock(JourneyApplicationDeadlineExecutor.class))
			.withBean(JourneyProfileResourcePolicy.class, JourneySearchControllerTest::policy)
			.withUserConfiguration(SearchWebConfiguration.class)
			.withPropertyValues(SEARCH_WEB_ENABLED);

		// 기본값 검증 (설정 미제공 시 DEFAULT_MAX_REQUEST_BYTES: 65536)
		runner.run(context -> {
			assertThat(context).hasSingleBean(JourneySearchController.class);
			var controller = context.getBean(JourneySearchController.class);
			assertThat(ReflectionTestUtils.getField(controller, "maxRequestBytes"))
				.isEqualTo(JourneySearchController.DEFAULT_MAX_REQUEST_BYTES);
		});

		// 명시적 프로퍼티 바인딩 검증
		runner.withPropertyValues("easysubway.journey.search.max-request-bytes=32768").run(context -> {
			assertThat(context).hasSingleBean(JourneySearchController.class);
			var controller = context.getBean(JourneySearchController.class);
			assertThat(ReflectionTestUtils.getField(controller, "maxRequestBytes"))
				.isEqualTo(32_768);
		});

		// 0 이하 비정상 값 바인딩 시 context startup failure
		runner.withPropertyValues("easysubway.journey.search.max-request-bytes=0").run(context ->
			assertThat(context.getStartupFailure())
				.hasRootCauseInstanceOf(IllegalArgumentException.class)
				.hasRootCauseMessage("maxRequestBytes must be positive"));
	}

	@Test
	@DisplayName("valid viaStationId가 포함된 요청을 수용하여 JourneyRequest에 바인딩한다")
	void acceptsAndBindsValidViaStationId() throws Exception {
		allowSession();
		when(deadlineExecutor.execute(any())).thenReturn(new Completed(success()));

		perform(validRequest("{\"mode\":\"NOW\"}").replace(
			"\"destinationStationId\":\"station-destination\",",
			"\"destinationStationId\":\"station-destination\",\n\"viaStationId\":\"station-via\","
		)).andExpect(status().isOk());

		var request = ArgumentCaptor.forClass(JourneyRequest.class);
		verify(deadlineExecutor).execute(request.capture());
		assertThat(request.getValue().viaStationId()).isEqualTo("station-via");
	}

	@Test
	@DisplayName("malformed·duplicate·trailing·extra request는 authorize 뒤 execute 없이 exact 400이다")
	void rejectsNonContractRequestsBeforeExecution() throws Exception {
		allowSession();
		for (String body : List.of(
			"",
			"{not-json",
			validRequest("{\"mode\":\"NOW\"}") + " {}",
			validRequest("{\"mode\":\"NOW\",\"requestedAt\":\"2026-08-12T00:01:00Z\"}"),
			validRequest("{\"mode\":\"SCHEDULED\"}"),
			validRequest("{\"mode\":\"NOW\",\"extra\":true}"),
			validRequest("{\"mode\":\"NOW\"}").replace("\"alternativeCount\":1", "\"alternativeCount\":1,\"extra\":true"),
			validRequest("{\"mode\":\"NOW\"}").replace("\"requestId\":", "\"requestId\":\"duplicate\",\"requestId\":"),
			validRequest("{\"mode\":\"NOW\"}").replace("\"timePolicy\":\"TIMETABLE_REQUIRED\"", "\"timePolicy\":\"UNKNOWN\""),
			validRequest("{\"mode\":\"NOW\"}").replace("\"walkingPace\":\"STANDARD\",", ""),
			validRequest("{\"mode\":\"NOW\"}").replace("\"walkingPace\":\"STANDARD\"", "\"walkingPace\":null"),
			validRequest("{\"mode\":\"NOW\"}").replace("\"walkingPace\":\"STANDARD\"", "\"walkingPace\":\"UNKNOWN\""),
			validRequest("{\"mode\":\"NOW\"}").replace("\"maxTransfers\":2", "\"maxTransfers\":4"),
			validRequest("{\"mode\":\"NOW\"}").replace("\"mobilityProfile\":\"STEP_FREE\"", "\"mobilityProfile\":\"NO_STAIRS\"")
				.replace("\"constraintMode\":\"REQUIRE_STEP_FREE\"", "\"constraintMode\":\"NONE\""),
			validRequest("{\"mode\":\"NOW\"}").replace("\"destinationStationId\":\"station-destination\",",
				"\"destinationStationId\":\"station-destination\",\n\"viaStationId\":\"station-origin\","),
			validRequest("{\"mode\":\"NOW\"}").replace("\"destinationStationId\":\"station-destination\",",
				"\"destinationStationId\":\"station-destination\",\n\"viaStationId\":\"station-destination\","),
			validRequest("{\"mode\":\"NOW\"}").replace("\"destinationStationId\":\"station-destination\",",
				"\"destinationStationId\":\"station-destination\",\n\"viaStationId\":\"\","),
			validRequest("{\"mode\":\"NOW\"}").replace("\"destinationStationId\":\"station-destination\",",
				"\"destinationStationId\":\"station-destination\",\n\"viaStationId\":null,"),
			"[]",
			"\"just-string\"",
			validRequest("{\"mode\":\"NOW\"}").replace("\"destinationStationId\":\"station-destination\",",
				"\"destinationStationId\":\"station-destination\",\n\"viaStationId\":123,"),
			validRequest("{\"mode\":\"NOW\"}").replace("\"destinationStationId\":\"station-destination\",",
				"\"destinationStationId\":\"station-destination\",\n\"viaStationId\":\"station-via\",\n\"extraField\":true,")
		)) {
			assertError(post("/api/v3/journeys/search")
				.header(HttpHeaders.AUTHORIZATION, "Bearer session-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body), 400, "INVALID_JOURNEY_REQUEST", false);
		}
		verify(sessionService, times(22)).authorize("session-token", 2);
		verifyNoInteractions(deadlineExecutor);
	}

	@Test
	@DisplayName("current typed failures는 exact public status/code로 fail closed한다")
	void mapsCurrentTypedFailures() throws Exception {
		allowSession();
		for (FailureCase failure : List.of(
			new FailureCase(Reason.ACTIVE_SNAPSHOT_UNAVAILABLE, 503, "ROUTING_BUNDLE_UNAVAILABLE"),
			new FailureCase(Reason.ACTIVE_SNAPSHOT_STALE, 503, "ROUTING_BUNDLE_STALE"),
			new FailureCase(Reason.REALTIME_UNAVAILABLE, 503, "REALTIME_REQUIRED_UNAVAILABLE"),
			new FailureCase(Reason.REALTIME_STALE, 503, "REALTIME_REQUIRED_UNAVAILABLE"),
			new FailureCase(Reason.REALTIME_IDENTITY_MISMATCH, 503, "ROUTING_IDENTITY_MISMATCH"),
			new FailureCase(Reason.RAPTOR_FAILED, 503, "ROUTE_SERVICE_UNAVAILABLE"),
			new FailureCase(Reason.NO_ROUTE, 422, "ROUTE_NOT_FOUND"),
			new FailureCase(Reason.CANCELLED, 503, "ROUTE_SERVICE_UNAVAILABLE")
		)) {
			when(deadlineExecutor.execute(any())).thenReturn(
				new Completed(new JourneyExecutionFailure(failure.reason()))
			);
			assertError(post("/api/v3/journeys/search")
				.header(HttpHeaders.AUTHORIZATION, "Bearer session-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(validRequest("{\"mode\":\"NOW\"}")), failure.status(), failure.code(), true);
		}
		verify(sessionService, times(8)).authorize("session-token", 2);
		verify(deadlineExecutor, times(8)).execute(any());
	}

	private org.springframework.test.web.servlet.ResultActions perform(String body) throws Exception {
		return mockMvc.perform(post("/api/v3/journeys/search")
			.header(HttpHeaders.AUTHORIZATION, "Bearer session-token")
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private void assertError(
		org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
		int httpStatus,
		String code,
		boolean preservesRequestId
	) throws Exception {
		assertError(mockMvc, request, httpStatus, code, preservesRequestId);
	}

	private void assertError(
		MockMvc targetMvc,
		org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
		int httpStatus,
		String code,
		boolean preservesRequestId
	) throws Exception {
		var response = targetMvc.perform(request)
			.andExpect(status().is(httpStatus))
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
			.andExpect(jsonPath("$.contractVersion").value("JOURNEY_ERROR_V1"))
			.andExpect(jsonPath("$.requestId").value(preservesRequestId
				? org.hamcrest.Matchers.equalTo(REQUEST_ID)
				: org.hamcrest.Matchers.matchesPattern("^[0-7][0-9A-HJKMNP-TV-Z]{25}$")))
			.andExpect(jsonPath("$.code").value(code))
			.andExpect(jsonPath("$.retryable").value(false))
			.andExpect(jsonPath("$.occurredAt").value("2026-08-12T00:00:00Z"));
		if (httpStatus == 401) {
			response.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
		}
		MvcResult result = response
			.andReturn();
		assertThat(fields(result)).containsExactlyInAnyOrder(
			"contractVersion", "requestId", "code", "retryable", "occurredAt"
		);
	}

	private void allowSession() {
		when(sessionService.authorize("session-token", 2))
			.thenReturn(new AuthorizedSession("journey:v3", NOW.plusSeconds(600)));
	}

	private static JourneyProfileResourcePolicy policy() {
		return new JourneyProfileResourcePolicy(new JourneyProfileResourcePolicy.Identity(
			"point-policy", "1.0.0", "a".repeat(64)), Duration.ofHours(1), 2, 100, 8, 16, 16,
			Duration.ofHours(1), Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(8),
			2, 2, 3, 4, 10);
	}

	private static String validRequest(String departure) {
		return """
			{
			  "requestId":"%s",
			  "originStationId":"station-origin",
			  "destinationStationId":"station-destination",
			  "departure":%s,
			  "timePolicy":"TIMETABLE_REQUIRED",
			  "walkingPace":"STANDARD",
			  "mobilityProfile":"STEP_FREE",
			  "constraintMode":"REQUIRE_STEP_FREE",
			  "maxTransfers":2,
			  "alternativeCount":1
			}
			""".formatted(REQUEST_ID, departure);
	}

	private static JourneyExecutionResult.Success success() {
		Instant departure = Instant.parse("2026-08-12T00:01:00Z");
		Instant arrival = departure.plusSeconds(300);
		var candidate = TestJourneyCandidates.unavailableFare(
			"journey-1", departure, arrival, null, null, 300, 0, 0,
			JourneyCandidate.TimeSource.TIMETABLE,
			new JourneyCandidate.Accessibility(true, List.of("STEP_FREE_PATH")),
			List.of(TestRides.candidateRide(
				"line-1", "trip-1", "station-destination", "station-origin", "station-destination",
				departure, arrival, null, null
			))
		);
		return new JourneyExecutionResult.Success(
			REQUEST_ID, "query-1", NOW, NOW.plusSeconds(600), departure,
			LocalDate.parse("2026-08-12"),
			1, new com.easysubway.journey.application.JourneyRaptorPort.ScanMetrics(1, 2, 3),
			new JourneyExecutionResult.SourceIdentity(
				"bundle-1", "a".repeat(64), "timetable-1", "accessibility-1", null
			),
			new JourneyExecutionResult.RequestPolicy(
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
				JourneyRequest.WalkingPace.STANDARD,
				JourneyRequest.MobilityProfile.STEP_FREE,
				JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE,
				2,
				1
			),
			List.of(candidate),
			JourneyExecutionResult.SafetyBoundary.observed(), TestJourneyCandidates.stairFreeAlternative(List.of(candidate))
		);
	}

	private static List<String> fields(MvcResult result) throws Exception {
		JsonNode body = JSON.readTree(result.getResponse().getContentAsByteArray());
		var fields = new ArrayList<String>();
		body.fieldNames().forEachRemaining(fields::add);
		return fields;
	}

	private void assertConditionalRegistration() {
		var runner = new ApplicationContextRunner()
			.withBean(JourneySessionService.class, () -> mock(JourneySessionService.class))
			.withBean(JourneySearchRecorder.class, () -> recorder)
			.withBean(JourneySearchLatencyMetrics.class, () -> latency)
			.withUserConfiguration(SearchWebConfiguration.class);
		runner.run(context -> assertThat(context)
			.doesNotHaveBean(JourneySearchController.class)
			.doesNotHaveBean(JourneySearchExceptionHandler.class));
		runner.withPropertyValues(SEARCH_WEB_ENABLED).run(context ->
			assertThat(context.getStartupFailure()).isNotNull());
		runner.withBean(
			JourneyApplicationDeadlineExecutor.class,
			() -> mock(JourneyApplicationDeadlineExecutor.class)
		).withPropertyValues(SEARCH_WEB_ENABLED).run(context ->
			assertThat(context.getStartupFailure()).isNotNull());
		runner.withBean(
			JourneyApplicationDeadlineExecutor.class,
			() -> mock(JourneyApplicationDeadlineExecutor.class)
		).withBean(
			JourneyProfileResourcePolicy.class,
			JourneySearchControllerTest::policy
		).withPropertyValues(SEARCH_WEB_ENABLED).run(context -> assertThat(context)
			.hasSingleBean(JourneySearchController.class)
			.hasSingleBean(JourneySearchExceptionHandler.class));
	}

	@Test
	@DisplayName("성공한 검색은 결과 있음으로 탐색 종류·이동 프로필·대표 분류·접근성 판정을 기록한다")
	void recordsSuccessFacts() throws Exception {
		allowSession();
		var success = success();
		var withCategories = new JourneyExecutionResult.Success(
			success.requestId(), success.queryId(), success.calculatedAt(), success.validUntil(),
			success.effectiveDepartureTime(), success.serviceDate(), success.bundleGeneration(),
			success.scanMetrics(), success.sourceIdentity(), success.requestPolicy(),
			List.of(success.journeys().getFirst().withAlternativeCategories(
				List.of(com.easysubway.journey.application.JourneyAlternatives.Category.FASTEST,
					com.easysubway.journey.application.JourneyAlternatives.Category.STAIR_FREE))),
			success.safetyBoundary(), success.stairFreeAlternative());
		when(deadlineExecutor.execute(any())).thenReturn(new Completed(withCategories));

		perform(validRequest("{\"mode\":\"NOW\"}")).andExpect(status().isOk());

		var saved = ArgumentCaptor.forClass(com.easysubway.journey.analytics.JourneySearchRecord.class);
		verify(recordStore).save(saved.capture());
		assertThat(saved.getValue()).satisfies(record -> {
			assertThat(record.kind()).isEqualTo(com.easysubway.journey.analytics.JourneySearchKind.DEPART_AT);
			assertThat(record.outcome()).isEqualTo(com.easysubway.journey.analytics.JourneySearchOutcome.FOUND);
			assertThat(record.httpStatus()).isEqualTo(200);
			assertThat(record.machineCode()).isNull();
			assertThat(record.mobilityProfile()).isEqualTo("STEP_FREE");
			assertThat(record.alternativeCategories()).containsExactly("FASTEST", "STAIR_FREE");
			assertThat(record.stairFreeStatus()).isEqualTo("INCLUDED");
			assertThat(record.engineVersion()).isEqualTo("UNKNOWN");
		});
	}

	@Test
	@DisplayName("실패 응답은 같은 상태·코드로 결과 분류를 기록한다")
	void recordsFailureOutcomes() throws Exception {
		allowSession();
		var expectations = List.of(
			new FailureCase(Reason.NO_ROUTE, 422, "NO_ROUTE"),
			new FailureCase(Reason.ACTIVE_SNAPSHOT_STALE, 503, "UNAVAILABLE"));
		for (FailureCase expected : expectations) {
			org.mockito.Mockito.clearInvocations(recordStore);
			when(deadlineExecutor.execute(any())).thenReturn(new Completed(new JourneyExecutionFailure(expected.reason())));

			perform(validRequest("{\"mode\":\"NOW\"}")).andExpect(status().is(expected.status()));

			var saved = ArgumentCaptor.forClass(com.easysubway.journey.analytics.JourneySearchRecord.class);
			verify(recordStore).save(saved.capture());
			assertThat(saved.getValue().outcome().name()).isEqualTo(expected.code());
			assertThat(saved.getValue().httpStatus()).isEqualTo(expected.status());
			assertThat(saved.getValue().stairFreeStatus()).isEqualTo("NOT_APPLICABLE");
		}
	}

	@Test
	@DisplayName("시간 초과와 실행 실패는 시간 초과·일시 불가로 기록한다")
	void recordsTimeoutAndUnavailable() throws Exception {
		allowSession();
		when(deadlineExecutor.execute(any())).thenReturn(new TimedOut());
		perform(validRequest("{\"mode\":\"NOW\"}")).andExpect(status().isGatewayTimeout());
		when(deadlineExecutor.execute(any())).thenThrow(new RuntimeException("failed"));
		perform(validRequest("{\"mode\":\"NOW\"}")).andExpect(status().isServiceUnavailable());

		var saved = ArgumentCaptor.forClass(com.easysubway.journey.analytics.JourneySearchRecord.class);
		verify(recordStore, times(2)).save(saved.capture());
		assertThat(saved.getAllValues()).extracting(record -> record.outcome().name(), record -> record.httpStatus())
			.containsExactly(org.assertj.core.api.Assertions.tuple("TIMEOUT", 504),
				org.assertj.core.api.Assertions.tuple("UNAVAILABLE", 503));
	}

	@Test
	@DisplayName("요청 검증 실패와 인증 실패는 검색 기록으로 남기지 않는다")
	void doesNotRecordInvalidOrUnauthorizedRequests() throws Exception {
		allowSession();
		perform("{}").andExpect(status().isBadRequest());
		mockMvc.perform(post("/api/v3/journeys/search").contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isUnauthorized());

		verifyNoInteractions(recordStore);
	}

	@Test
	@DisplayName("기록 저장이 실패해도 검색 응답은 그대로이고 실패 건수가 늘어난다")
	void recordingFailureDoesNotChangeTheResponse() throws Exception {
		allowSession();
		when(deadlineExecutor.execute(any())).thenReturn(new Completed(success()));
		String expected = perform(validRequest("{\"mode\":\"NOW\"}")).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		org.mockito.Mockito.doThrow(new IllegalStateException("db down")).when(recordStore).save(any());

		String actual = perform(validRequest("{\"mode\":\"NOW\"}")).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();

		assertThat(actual).isEqualTo(expected);
		assertThat(recorder.failureCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("기록 저장이 실패해도 실패 응답은 그대로이다")
	void recordingFailureDoesNotChangeFailureResponses() throws Exception {
		allowSession();
		when(deadlineExecutor.execute(any())).thenReturn(new TimedOut());
		org.mockito.Mockito.doThrow(new IllegalStateException("db down")).when(recordStore).save(any());

		assertError(post("/api/v3/journeys/search")
			.header(HttpHeaders.AUTHORIZATION, "Bearer session-token")
			.contentType(MediaType.APPLICATION_JSON)
			.content(validRequest("{\"mode\":\"NOW\"}")), 504, "JOURNEY_SEARCH_TIMEOUT", true);

		assertThat(recorder.failureCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("검색 뒤 지연 미터가 mode·outcome 태그만으로 존재하고 결과별로 한 건씩 센다")
	void recordsSearchLatencyPerOutcome() throws Exception {
		allowSession();
		when(deadlineExecutor.execute(any())).thenReturn(new Completed(success()));
		perform(validRequest("{\"mode\":\"NOW\"}")).andExpect(status().isOk());
		when(deadlineExecutor.execute(any())).thenReturn(new Completed(new JourneyExecutionFailure(Reason.NO_ROUTE)));
		perform(validRequest("{\"mode\":\"NOW\"}")).andExpect(status().isUnprocessableEntity());
		when(deadlineExecutor.execute(any())).thenReturn(new Completed(new JourneyExecutionFailure(Reason.ACTIVE_SNAPSHOT_STALE)));
		perform(validRequest("{\"mode\":\"NOW\"}")).andExpect(status().isServiceUnavailable());
		when(deadlineExecutor.execute(any())).thenReturn(new TimedOut());
		perform(validRequest("{\"mode\":\"NOW\"}")).andExpect(status().isGatewayTimeout());

		assertThat(searchTimerCount("depart_at", "ok")).isEqualTo(1);
		assertThat(searchTimerCount("depart_at", "no_route")).isEqualTo(1);
		assertThat(searchTimerCount("depart_at", "fail_closed")).isEqualTo(1);
		assertThat(searchTimerCount("depart_at", "error")).isEqualTo(1);
		assertThat(meters.find(JourneySearchLatencyMetrics.METER).timers())
			.allSatisfy(timer -> assertThat(timer.getId().getTags()).extracting(io.micrometer.core.instrument.Tag::getKey)
				.containsExactlyInAnyOrder("mode", "outcome"));
	}

	@Test
	@DisplayName("요청 검증·인증 실패는 지연 지표에 섞이지 않는다")
	void doesNotRecordLatencyForInvalidOrUnauthorizedRequests() throws Exception {
		allowSession();
		perform("{}").andExpect(status().isBadRequest());
		mockMvc.perform(post("/api/v3/journeys/search").contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isUnauthorized());

		assertThat(meters.find(JourneySearchLatencyMetrics.METER).timers())
			.allSatisfy(timer -> assertThat(timer.count()).isZero());
	}

	private long searchTimerCount(String mode, String outcome) {
		return meters.get(JourneySearchLatencyMetrics.METER).tag("mode", mode).tag("outcome", outcome).timer().count();
	}

	private record FailureCase(Reason reason, int status, String code) {
	}

	@TestConfiguration
	@Import({JourneySearchController.class, JourneySearchExceptionHandler.class})
	static class SearchWebConfiguration {
	}
}

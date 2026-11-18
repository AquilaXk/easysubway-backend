package com.easysubway.journey.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.journey.application.JourneySessionException;
import com.easysubway.journey.application.JourneySessionException.Kind;
import com.easysubway.journey.application.JourneySessionService;
import com.easysubway.journey.application.JourneySessionService.IssuedSession;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@DisplayName("Journey V3 session HTTP boundary")
class JourneySessionControllerTest {

	private static final String NONCE = "AAAAAAAAAAAAAAAAAAAAAA";
	private static final Instant NOW = Instant.parse("2026-08-12T00:00:00Z");
	private static final ObjectMapper JSON = new ObjectMapper();
	private static final String SESSION_WEB_ENABLED = "easysubway.journey-v3.session-web.enabled=true";

	private JourneySessionService service;
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		service = mock(JourneySessionService.class);
		mockMvc = MockMvcBuilders.standaloneSetup(new JourneySessionController(service))
			.setControllerAdvice(new JourneySessionExceptionHandler(
				Clock.fixed(NOW, ZoneOffset.UTC),
				new SecureRandom(new byte[] {1, 2, 3, 4})
			))
			.build();
	}

	@Test
	@DisplayName("exact request를 한 번 발급하고 direct no-store response를 반환한다")
	void issuesDirectSessionResponseOnce() throws Exception {
		assertConditionalRegistration();
		when(service.issue("integrity-token", NONCE)).thenReturn(new IssuedSession(
			"A".repeat(43),
			"journey:v3",
			NOW,
			NOW.plusSeconds(600)
		));

		MvcResult result = mockMvc.perform(post("/api/v3/journeys/session")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"integrityToken":"integrity-token","clientNonce":"AAAAAAAAAAAAAAAAAAAAAA"}
					"""))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
			.andExpect(jsonPath("$.token").value("A".repeat(43)))
			.andExpect(jsonPath("$.scope").value("journey:v3"))
			.andExpect(jsonPath("$.issuedAt").value("2026-08-12T00:00:00Z"))
			.andExpect(jsonPath("$.expiresAt").value("2026-08-12T00:10:00Z"))
			.andReturn();

		assertThat(fields(result)).containsExactlyInAnyOrder("token", "scope", "issuedAt", "expiresAt");
		verify(service).issue("integrity-token", NONCE);
	}

	@Test
	@DisplayName("malformed·missing·extra·wrong-type request는 service 호출 없이 exact 400이다")
	void rejectsNonContractRequestsBeforeService() throws Exception {
		for (String body : List.of(
			"",
			"   ",
			"{not-json",
			"[]",
			"{}",
			"{\"integrityToken\":\"token\"}",
			"{\"integrityToken\":7,\"clientNonce\":\"" + NONCE + "\"}",
			"{\"integrityToken\":\"token\",\"clientNonce\":\"" + NONCE + "\",\"extra\":true}",
			"{\"integrityToken\":\"first\",\"integrityToken\":\"second\",\"clientNonce\":\"" + NONCE + "\"}",
			"{\"integrityToken\":\"token\",\"clientNonce\":\"" + NONCE + "\"} {}"
		)) {
			MvcResult result = mockMvc.perform(post("/api/v3/journeys/session")
					.contentType(MediaType.APPLICATION_JSON)
					.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
				.andExpect(jsonPath("$.contractVersion").value("JOURNEY_ERROR_V1"))
				.andExpect(jsonPath("$.requestId").value(org.hamcrest.Matchers.matchesPattern(
					"^[0-7][0-9A-HJKMNP-TV-Z]{25}$"
				)))
				.andExpect(jsonPath("$.code").value("INVALID_JOURNEY_SESSION_REQUEST"))
				.andExpect(jsonPath("$.retryable").value(false))
				.andExpect(jsonPath("$.occurredAt").value("2026-08-12T00:00:00Z"))
				.andReturn();

			assertThat(fields(result)).containsExactlyInAnyOrder(
				"contractVersion", "requestId", "code", "retryable", "occurredAt"
			);
		}
		verifyNoInteractions(service);
	}

	private static void assertConditionalRegistration() {
		var runner = new ApplicationContextRunner()
			.withBean(JourneySessionService.class, () -> mock(JourneySessionService.class))
			.withUserConfiguration(SessionWebConfiguration.class);

		runner.run(context -> assertThat(context)
			.doesNotHaveBean(JourneySessionController.class)
			.doesNotHaveBean(JourneySessionExceptionHandler.class));
		runner.withPropertyValues(SESSION_WEB_ENABLED).run(context -> assertThat(context)
			.hasSingleBean(JourneySessionController.class)
			.hasSingleBean(JourneySessionExceptionHandler.class));
	}

	@Test
	@DisplayName("typed session failures는 exact status/code만 direct error body로 공개한다")
	void mapsTypedFailuresWithoutSensitiveDetails() throws Exception {
		for (FailureCase failure : List.of(
			new FailureCase(Kind.INVALID_REQUEST, 400, "INVALID_JOURNEY_SESSION_REQUEST"),
			new FailureCase(Kind.ATTESTATION_REJECTED, 403, "ROUTE_SESSION_ATTESTATION_REJECTED"),
			new FailureCase(Kind.ATTESTATION_UNAVAILABLE, 503, "ROUTE_SESSION_ATTESTATION_UNAVAILABLE")
		)) {
			String token = "sensitive-" + failure.kind().name();
			when(service.issue(token, NONCE)).thenThrow(new JourneySessionException(failure.kind()));

			MvcResult result = mockMvc.perform(post("/api/v3/journeys/session")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{"integrityToken":"%s","clientNonce":"%s"}
						""".formatted(token, NONCE)))
				.andExpect(status().is(failure.status()))
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
				.andExpect(jsonPath("$.contractVersion").value("JOURNEY_ERROR_V1"))
				.andExpect(jsonPath("$.requestId").value(org.hamcrest.Matchers.matchesPattern(
					"^[0-7][0-9A-HJKMNP-TV-Z]{25}$"
				)))
				.andExpect(jsonPath("$.code").value(failure.code()))
				.andExpect(jsonPath("$.retryable").value(false))
				.andExpect(jsonPath("$.occurredAt").value("2026-08-12T00:00:00Z"))
				.andReturn();

			assertThat(fields(result)).containsExactlyInAnyOrder(
				"contractVersion", "requestId", "code", "retryable", "occurredAt"
			);
			assertThat(result.getResponse().getContentAsString()).doesNotContain(token);
			verify(service).issue(token, NONCE);
		}
	}

	@Test
	@DisplayName("request body 크기가 maxRequestBytes를 초과하면 service 호출 없이 exact 400으로 닫힌다")
	void rejectsOversizedSessionRequestBeforeService() throws Exception {
		int maxBytes = 50;
		var controller = new JourneySessionController(service, maxBytes);
		var mvc = MockMvcBuilders.standaloneSetup(controller)
			.setControllerAdvice(new JourneySessionExceptionHandler(
				Clock.fixed(NOW, ZoneOffset.UTC),
				new SecureRandom(new byte[] {1, 2, 3, 4})
			))
			.build();

		String body = "{\"integrityToken\":\"integrity-token\",\"clientNonce\":\"" + NONCE + "\"}";
		assertThat(body.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(maxBytes);

		mvc.perform(post("/api/v3/journeys/session")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isBadRequest())
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
			.andExpect(jsonPath("$.code").value("INVALID_JOURNEY_SESSION_REQUEST"));

		verifyNoInteractions(service);
	}

	@Test
	@DisplayName("request body 크기가 정확히 maxRequestBytes 이하이면 정상 수용되고 초과 시 차단된다")
	void acceptsExactBoundaryAndRejectsOneByteOver() throws Exception {
		when(service.issue("integrity-token", NONCE)).thenReturn(new IssuedSession(
			"A".repeat(43), "journey:v3", NOW, NOW.plusSeconds(600)
		));
		String validBody = "{\"integrityToken\":\"integrity-token\",\"clientNonce\":\"" + NONCE + "\"}";
		int exactLength = validBody.getBytes(StandardCharsets.UTF_8).length;

		// 정확히 exactLength 바이트 허용: 200 성공
		var allowedController = new JourneySessionController(service, exactLength);
		var allowedMvc = MockMvcBuilders.standaloneSetup(allowedController).build();
		allowedMvc.perform(post("/api/v3/journeys/session")
				.contentType(MediaType.APPLICATION_JSON)
				.content(validBody))
			.andExpect(status().isOk());
		verify(service).issue("integrity-token", NONCE);

		// exactLength - 1 바이트 허용 (1바이트 부족): 400 차단
		var rejectedService = mock(JourneySessionService.class);
		var rejectedController = new JourneySessionController(rejectedService, exactLength - 1);
		var rejectedMvc = MockMvcBuilders.standaloneSetup(rejectedController)
			.setControllerAdvice(new JourneySessionExceptionHandler(Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom(new byte[] {1, 2, 3, 4})))
			.build();
		rejectedMvc.perform(post("/api/v3/journeys/session")
				.contentType(MediaType.APPLICATION_JSON)
				.content(validBody))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_JOURNEY_SESSION_REQUEST"));
		verifyNoInteractions(rejectedService);
	}

	@Test
	@DisplayName("chunked/stream 방식으로 Content-Length 없이 maxRequestBytes를 초과하는 바디도 exact 400으로 차단된다")
	void rejectsOversizedStreamWithoutContentLengthBeforeService() throws Exception {
		String validBody = "{\"integrityToken\":\"integrity-token\",\"clientNonce\":\"" + NONCE + "\"}";
		byte[] validBytes = validBody.getBytes(StandardCharsets.UTF_8);
		int maxBytes = validBytes.length;
		var controller = new JourneySessionController(service, maxBytes);

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
			JourneySessionException.class,
			() -> controller.issue(request)
		);

		assertThat(exception.kind()).isEqualTo(Kind.INVALID_REQUEST);
		verifyNoInteractions(service);
	}

	@Test
	@DisplayName("request inputStream 읽기 중 IOException이 발생하면 exact 400으로 차단된다")
	void rejectsRequestWhenInputStreamThrowsIOException() throws Exception {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getContentLengthLong()).thenReturn(-1L);
		when(request.getInputStream()).thenThrow(new IOException("Simulated I/O failure"));

		var controller = new JourneySessionController(service, 1024);
		var exception = assertThrows(
			JourneySessionException.class,
			() -> controller.issue(request)
		);

		assertThat(exception.kind()).isEqualTo(Kind.INVALID_REQUEST);
		verifyNoInteractions(service);
	}

	@Test
	@DisplayName("maxRequestBytes가 0 이하이면 생성자에서 IllegalArgumentException이 발생한다")
	void constructorRejectsNonPositiveMaxRequestBytes() {
		assertThrows(IllegalArgumentException.class,
			() -> new JourneySessionController(service, 0));
		assertThrows(IllegalArgumentException.class,
			() -> new JourneySessionController(service, -1));
	}

	@Test
	@DisplayName("@Value max-request-bytes 설정이 JourneySessionController에 올바르게 바인딩된다")
	void bindsMaxRequestBytesConfigurationProperty() {
		var runner = new ApplicationContextRunner()
			.withBean(JourneySessionService.class, () -> mock(JourneySessionService.class))
			.withUserConfiguration(SessionWebConfiguration.class)
			.withPropertyValues(SESSION_WEB_ENABLED);

		// 기본값 검증 (설정 미제공 시 DEFAULT_MAX_REQUEST_BYTES: 65536)
		runner.run(context -> {
			assertThat(context).hasSingleBean(JourneySessionController.class);
			var controller = context.getBean(JourneySessionController.class);
			assertThat(ReflectionTestUtils.getField(controller, "maxRequestBytes"))
				.isEqualTo(65536);
		});

		// 명시적 프로퍼티 바인딩 검증
		runner.withPropertyValues("easysubway.journey.session.max-request-bytes=32768").run(context -> {
			assertThat(context).hasSingleBean(JourneySessionController.class);
			var controller = context.getBean(JourneySessionController.class);
			assertThat(ReflectionTestUtils.getField(controller, "maxRequestBytes"))
				.isEqualTo(32_768);
		});

		// 0 이하 비정상 값 바인딩 시 context startup failure
		runner.withPropertyValues("easysubway.journey.session.max-request-bytes=0").run(context ->
			assertThat(context.getStartupFailure())
				.hasRootCauseInstanceOf(IllegalArgumentException.class)
				.hasRootCauseMessage("maxRequestBytes must be positive"));
	}

	private static List<String> fields(MvcResult result) throws Exception {
		JsonNode body = JSON.readTree(result.getResponse().getContentAsByteArray());
		var fields = new ArrayList<String>();
		body.fieldNames().forEachRemaining(fields::add);
		return fields;
	}

	private record FailureCase(Kind kind, int status, String code) {
	}

	@TestConfiguration
	@Import({JourneySessionController.class, JourneySessionExceptionHandler.class})
	static class SessionWebConfiguration {
	}
}

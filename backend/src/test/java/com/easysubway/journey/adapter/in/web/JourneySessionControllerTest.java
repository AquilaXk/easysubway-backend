package com.easysubway.journey.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@DisplayName("Journey V3 session HTTP boundary")
class JourneySessionControllerTest {

	private static final String NONCE = "AAAAAAAAAAAAAAAAAAAAAA";
	private static final Instant NOW = Instant.parse("2026-08-12T00:00:00Z");
	private static final ObjectMapper JSON = new ObjectMapper();

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
			"{not-json",
			"[]",
			"{}",
			"{\"integrityToken\":\"token\"}",
			"{\"integrityToken\":7,\"clientNonce\":\"" + NONCE + "\"}",
			"{\"integrityToken\":\"token\",\"clientNonce\":\"" + NONCE + "\",\"extra\":true}"
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

	private static List<String> fields(MvcResult result) throws Exception {
		JsonNode body = JSON.readTree(result.getResponse().getContentAsByteArray());
		var fields = new ArrayList<String>();
		body.fieldNames().forEachRemaining(fields::add);
		return fields;
	}

	private record FailureCase(Kind kind, int status, String code) {
	}
}

package com.easysubway.route.adapter.in.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.route.application.service.RouteSessionAttestationRejectedException;
import com.easysubway.route.application.service.RouteSessionAttestationUnavailableException;
import com.easysubway.route.application.service.RouteV2SessionService;
import com.easysubway.route.application.service.RouteV2SessionService.IssuedRouteV2Session;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@DisplayName("Route V2 session API")
class RouteV2SessionControllerTest {

	private RouteV2SessionService service;
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		service = mock(RouteV2SessionService.class);
		mockMvc = MockMvcBuilders.standaloneSetup(new RouteV2SessionController(service))
			.setControllerAdvice(new RouteV2ExceptionHandler())
			.build();
	}

	@Test
	@DisplayName("성공 응답은 token·scope·발급·만료 시각과 private no-store만 반환한다")
	void issuesNoStoreSession() throws Exception {
		when(service.issue("integrity-token", "AAAAAAAAAAAAAAAAAAAAAA")).thenReturn(new IssuedRouteV2Session(
			"A".repeat(43),
			"route:v2:itx",
			Instant.parse("2026-07-16T09:00:00Z"),
			Instant.parse("2026-07-16T09:10:00Z")
		));

		mockMvc.perform(post("/api/v2/routes/session")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"integrityToken":"integrity-token","clientNonce":"AAAAAAAAAAAAAAAAAAAAAA"}
					"""))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
			.andExpect(jsonPath("$.token").value("A".repeat(43)))
			.andExpect(jsonPath("$.scope").value("route:v2:itx"))
			.andExpect(jsonPath("$.issuedAt").value("2026-07-16T09:00:00Z"))
			.andExpect(jsonPath("$.expiresAt").value("2026-07-16T09:10:00Z"));
	}

	@Test
	@DisplayName("attestation 거부는 보안 상세 없이 exact 403 machine code를 반환한다")
	void rejectsAttestationWithoutDetails() throws Exception {
		when(service.issue("rejected-token", "AAAAAAAAAAAAAAAAAAAAAA"))
			.thenThrow(new RouteSessionAttestationRejectedException(new IllegalStateException("secret-verdict")));

		mockMvc.perform(post("/api/v2/routes/session")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"integrityToken":"rejected-token","clientNonce":"AAAAAAAAAAAAAAAAAAAAAA"}
					"""))
			.andExpect(status().isForbidden())
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value("ROUTE_SESSION_ATTESTATION_REJECTED"))
			.andExpect(jsonPath("$.message").value("ITX 시간표를 불러올 수 없어요"))
			.andExpect(jsonPath("$..secret-verdict").doesNotExist());
	}

	@Test
	@DisplayName("provider 장애는 exact 503으로 반환해 ingress smoke를 실패시킨다")
	void reportsProviderUnavailability() throws Exception {
		when(service.issue("provider-failure", "AAAAAAAAAAAAAAAAAAAAAA"))
			.thenThrow(new RouteSessionAttestationUnavailableException(new IllegalStateException("provider-secret")));

		mockMvc.perform(post("/api/v2/routes/session")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"integrityToken":"provider-failure","clientNonce":"AAAAAAAAAAAAAAAAAAAAAA"}
					"""))
			.andExpect(status().isServiceUnavailable())
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value("ROUTE_SESSION_ATTESTATION_UNAVAILABLE"))
			.andExpect(jsonPath("$..provider-secret").doesNotExist());
	}

	@Test
	@DisplayName("oversized attestation token은 decoder 호출 전에 exact 403으로 거부한다")
	void rejectsOversizedAttestationBeforeDecode() throws Exception {
		mockMvc.perform(post("/api/v2/routes/session")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"integrityToken":"%s","clientNonce":"AAAAAAAAAAAAAAAAAAAAAA"}
					""".formatted("A".repeat(16_385))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ROUTE_SESSION_ATTESTATION_REJECTED"));

		verifyNoInteractions(service);
	}
}

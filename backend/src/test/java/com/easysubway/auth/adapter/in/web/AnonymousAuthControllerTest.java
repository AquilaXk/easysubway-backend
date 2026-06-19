package com.easysubway.auth.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.auth.application.port.in.AnonymousAuthRateLimitUseCase;
import com.easysubway.auth.domain.AnonymousAuthRateLimitExceededException;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties = {
	"easysubway.user.username=configured-user",
	"easysubway.user.password=configured-password",
	"easysubway.auth.client-ip.trusted-proxies=10.0.0.0/8"
})
@AutoConfigureMockMvc
@DisplayName("익명 사용자 인증 API")
class AnonymousAuthControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private RecordingAnonymousAuthRateLimitUseCase rateLimitUseCase;

	@BeforeEach
	void resetRateLimitUseCase() {
		rateLimitUseCase.reset();
	}

	@Test
	@DisplayName("익명 사용자를 발급하고 Bearer token으로 현재 사용자를 조회한다")
	void issueAnonymousUserAndReadCurrentUser() throws Exception {
		var result = mockMvc.perform(post("/api/v1/auth/anonymous"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.userId").isNotEmpty())
			.andExpect(jsonPath("$.data.password").doesNotExist())
			.andExpect(jsonPath("$.data.accessToken").isNotEmpty())
			.andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
			.andExpect(jsonPath("$.data.authType").value("BEARER"))
			.andExpect(jsonPath("$.data.anonymous").value(true))
			.andExpect(jsonPath("$.data.createdAt").isNotEmpty())
			.andReturn();

		String body = result.getResponse().getContentAsString();
		String userId = JsonPath.read(body, "$.data.userId");
		String accessToken = JsonPath.read(body, "$.data.accessToken");

		assertThat(userId).startsWith("anonymous-");

		mockMvc.perform(get("/api/v1/me")
				.header("Authorization", "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.userId").value(userId))
			.andExpect(jsonPath("$.data.authType").value("BEARER"))
			.andExpect(jsonPath("$.data.anonymous").value(true));
	}

	@Test
	@DisplayName("익명 사용자 발급은 같은 클라이언트 반복 호출을 제한한다")
	void issueAnonymousUserRateLimitsSameClient() throws Exception {
		mockMvc.perform(post("/api/v1/auth/anonymous")
				.with(remoteAddr("198.51.100.10")))
			.andExpect(status().isOk());

		mockMvc.perform(post("/api/v1/auth/anonymous")
				.with(remoteAddr("198.51.100.10")))
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("잠시 후 다시 시도해 주세요."));
	}

	@Test
	@DisplayName("refresh token 갱신은 새 Bearer token을 발급하고 같은 refresh token 재사용을 거부한다")
	void refreshAnonymousUserRotatesTokensAndRejectsReuse() throws Exception {
		var issueResult = mockMvc.perform(post("/api/v1/auth/anonymous")
				.with(remoteAddr("198.51.100.11")))
			.andExpect(status().isOk())
			.andReturn();
		String issueBody = issueResult.getResponse().getContentAsString();
		String userId = JsonPath.read(issueBody, "$.data.userId");
		String accessToken = JsonPath.read(issueBody, "$.data.accessToken");
		String refreshToken = JsonPath.read(issueBody, "$.data.refreshToken");

		var refreshResult = mockMvc.perform(post("/api/v1/auth/anonymous/refresh")
				.contentType("application/json")
				.content("""
					{"refreshToken":"%s"}
					""".formatted(refreshToken)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.userId").value(userId))
			.andExpect(jsonPath("$.data.accessToken").isNotEmpty())
			.andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
			.andExpect(jsonPath("$.data.authType").value("BEARER"))
			.andReturn();
		String refreshBody = refreshResult.getResponse().getContentAsString();
		String rotatedAccessToken = JsonPath.read(refreshBody, "$.data.accessToken");
		String rotatedRefreshToken = JsonPath.read(refreshBody, "$.data.refreshToken");

		assertThat(rotatedAccessToken).isNotEqualTo(accessToken);
		assertThat(rotatedRefreshToken).isNotEqualTo(refreshToken);

		mockMvc.perform(get("/api/v1/me")
				.header("Authorization", "Bearer " + rotatedAccessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.userId").value(userId))
			.andExpect(jsonPath("$.data.authType").value("BEARER"));

		mockMvc.perform(post("/api/v1/auth/anonymous/refresh")
				.contentType("application/json")
				.content("""
					{"refreshToken":"%s"}
					""".formatted(refreshToken)))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.message").value("익명 인증 세션을 갱신할 수 없습니다."));
	}

	@Test
	@DisplayName("refresh token 갱신은 빈 토큰 요청을 거부한다")
	void refreshAnonymousUserRejectsBlankToken() throws Exception {
		mockMvc.perform(post("/api/v1/auth/anonymous/refresh")
				.contentType("application/json")
				.content("""
					{"refreshToken":" "}
					"""))
			.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("익명 사용자 발급은 원격 주소를 발급 제한 키로 사용한다")
	void issueAnonymousUserUsesRemoteAddressAsRateLimitKey() throws Exception {
		mockMvc.perform(post("/api/v1/auth/anonymous")
				.with(remoteAddr("198.51.100.55")))
			.andExpect(status().isOk());

		assertThat(rateLimitUseCase.clientKeys).containsExactly("198.51.100.55");
	}

	@Test
	@DisplayName("신뢰 프록시 요청은 전달된 첫 번째 클라이언트 IP를 발급 제한 키로 사용한다")
	void issueAnonymousUserUsesForwardedClientIpFromTrustedProxy() throws Exception {
		mockMvc.perform(post("/api/v1/auth/anonymous")
				.with(remoteAddr("10.12.0.8"))
				.header("X-Forwarded-For", "203.0.113.77, 10.12.0.8"))
			.andExpect(status().isOk());

		assertThat(rateLimitUseCase.clientKeys).containsExactly("203.0.113.77");
	}

	@Test
	@DisplayName("신뢰 프록시 요청은 주입된 전달 헤더보다 마지막 비신뢰 IP를 우선한다")
	void issueAnonymousUserUsesLastUntrustedForwardedClientIpFromTrustedProxy() throws Exception {
		mockMvc.perform(post("/api/v1/auth/anonymous")
				.with(remoteAddr("10.12.0.8"))
				.header("X-Forwarded-For", "198.51.100.200, 203.0.113.77, 10.12.0.8"))
			.andExpect(status().isOk());

		assertThat(rateLimitUseCase.clientKeys).containsExactly("203.0.113.77");
	}

	@Test
	@DisplayName("신뢰하지 않는 원격 주소의 전달 헤더는 발급 제한 키로 사용하지 않는다")
	void issueAnonymousUserIgnoresForwardedClientIpFromUntrustedRemoteAddress() throws Exception {
		mockMvc.perform(post("/api/v1/auth/anonymous")
				.with(remoteAddr("198.51.100.20"))
				.header("X-Forwarded-For", "203.0.113.88"))
			.andExpect(status().isOk());

		assertThat(rateLimitUseCase.clientKeys).containsExactly("198.51.100.20");
	}

	@Test
	@DisplayName("잘못된 전달 헤더는 원격 주소로 되돌아가 발급 제한 키를 만든다")
	void issueAnonymousUserFallsBackToRemoteAddressWhenForwardedHeaderIsInvalid() throws Exception {
		mockMvc.perform(post("/api/v1/auth/anonymous")
				.with(remoteAddr("10.12.0.8"))
				.header("X-Forwarded-For", "localhost"))
			.andExpect(status().isOk());

		assertThat(rateLimitUseCase.clientKeys).containsExactly("10.12.0.8");
	}

	@Test
	@DisplayName("현재 사용자 조회는 고정 Basic 계정을 익명 사용자가 아닌 계정으로 반환한다")
	void currentUserReturnsNonAnonymousForConfiguredBasicUser() throws Exception {
		mockMvc.perform(get("/api/v1/me")
				.with(httpBasic("configured-user", "configured-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.userId").value("configured-user"))
			.andExpect(jsonPath("$.data.authType").value("BASIC"))
			.andExpect(jsonPath("$.data.anonymous").value(false));
	}

	@Test
	@DisplayName("익명 사용자는 데이터 삭제 후 같은 인증 정보를 다시 사용할 수 없다")
	void deleteCurrentAnonymousUserDataInvalidatesIssuedCredentials() throws Exception {
		var result = mockMvc.perform(post("/api/v1/auth/anonymous"))
			.andExpect(status().isOk())
			.andReturn();
		String body = result.getResponse().getContentAsString();
		String userId = JsonPath.read(body, "$.data.userId");
		String accessToken = JsonPath.read(body, "$.data.accessToken");

		mockMvc.perform(delete("/api/v1/me")
				.header("Authorization", "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.userId").value(userId))
			.andExpect(jsonPath("$.data.anonymousCredentialsDeleted").value(true));

		mockMvc.perform(get("/api/v1/me")
				.header("Authorization", "Bearer " + accessToken))
			.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("현재 사용자 조회는 인증을 요구한다")
	void currentUserRequiresAuthentication() throws Exception {
		mockMvc.perform(get("/api/v1/me"))
			.andExpect(status().isUnauthorized());
	}

	private static RequestPostProcessor remoteAddr(String remoteAddr) {
		return request -> {
			request.setRemoteAddr(remoteAddr);
			return request;
		};
	}

	@TestConfiguration
	static class RateLimitTestConfiguration {

		@Bean
		@Primary
		RecordingAnonymousAuthRateLimitUseCase anonymousAuthRateLimitUseCase() {
			return new RecordingAnonymousAuthRateLimitUseCase();
		}
	}

	private static final class RecordingAnonymousAuthRateLimitUseCase implements AnonymousAuthRateLimitUseCase {

		private final List<String> clientKeys = new ArrayList<>();
		private final Map<String, Integer> countsByClientKey = new HashMap<>();

		@Override
		public void check(String clientKey) {
			clientKeys.add(clientKey);
			int count = countsByClientKey.merge(clientKey, 1, Integer::sum);
			if (count > 1) {
				throw new AnonymousAuthRateLimitExceededException("잠시 후 다시 시도해 주세요.");
			}
		}

		private void reset() {
			clientKeys.clear();
			countsByClientKey.clear();
		}
	}
}

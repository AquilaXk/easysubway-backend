package com.easysubway.auth.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
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
	"easysubway.user.password=configured-password"
})
@AutoConfigureMockMvc
@DisplayName("익명 사용자 인증 API")
class AnonymousAuthControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("익명 사용자를 발급하고 같은 Basic 인증 정보로 현재 사용자를 조회한다")
	void issueAnonymousUserAndReadCurrentUser() throws Exception {
		var result = mockMvc.perform(post("/api/v1/auth/anonymous"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.userId").isNotEmpty())
			.andExpect(jsonPath("$.data.password").isNotEmpty())
			.andExpect(jsonPath("$.data.authType").value("BASIC"))
			.andExpect(jsonPath("$.data.anonymous").value(true))
			.andExpect(jsonPath("$.data.createdAt").isNotEmpty())
			.andReturn();

		String body = result.getResponse().getContentAsString();
		String userId = JsonPath.read(body, "$.data.userId");
		String password = JsonPath.read(body, "$.data.password");

		assertThat(userId).startsWith("anonymous-");

		mockMvc.perform(get("/api/v1/me")
				.with(httpBasic(userId, password)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.userId").value(userId))
			.andExpect(jsonPath("$.data.authType").value("BASIC"))
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
		AnonymousAuthRateLimiter anonymousAuthRateLimiter() {
			return new AnonymousAuthRateLimiter(
				Clock.fixed(Instant.parse("2026-06-13T00:00:00Z"), ZoneId.of("Asia/Seoul")),
				1,
				Duration.ofMinutes(10),
				100
			);
		}
	}
}

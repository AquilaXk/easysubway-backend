package com.easysubway.auth.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.auth.application.port.out.AnonymousAuthTokenPort;
import com.easysubway.auth.application.service.AnonymousAuthTokenHasher;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

@DisplayName("익명 Bearer 인증 필터")
class AnonymousBearerAuthenticationFilterTest {

	private final RecordingAnonymousAuthTokenPort tokenPort = new RecordingAnonymousAuthTokenPort();
	private final AnonymousBearerAuthenticationFilter filter = new AnonymousBearerAuthenticationFilter(tokenPort);

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	@DisplayName("유효한 access token은 사용자 인증으로 변환한다")
	void validAccessTokenAuthenticatesUser() throws Exception {
		tokenPort.userIdByAccessTokenHash = "anonymous-user-1";
		var request = bearerRequest("valid-access-token");

		filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

		var authentication = SecurityContextHolder.getContext().getAuthentication();
		assertThat(authentication.getName()).isEqualTo("anonymous-user-1");
		assertThat(authentication.getPrincipal())
			.isInstanceOfSatisfying(AnonymousBearerPrincipal.class, principal ->
				assertThat(principal.getName()).isEqualTo("anonymous-user-1"));
		assertThat(authentication.getCredentials()).isNull();
		assertThat(tokenPort.requestedAccessTokenHashes)
			.containsExactly(AnonymousAuthTokenHasher.sha256("valid-access-token"));
	}

	@Test
	@DisplayName("유효하지 않은 access token은 인증 실패 감사 이벤트를 남긴다")
	void invalidAccessTokenWritesAuditEvent() throws Exception {
		var request = bearerRequest("invalid-access-token");

		filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
		assertThat(tokenPort.auditEvents).containsExactly("ACCESS_TOKEN_INVALID");
	}

	private MockHttpServletRequest bearerRequest(String token) {
		var request = new MockHttpServletRequest();
		request.addHeader("Authorization", "Bearer " + token);
		return request;
	}

	private static final class RecordingAnonymousAuthTokenPort implements AnonymousAuthTokenPort {

		private String userIdByAccessTokenHash;
		private final List<String> requestedAccessTokenHashes = new ArrayList<>();
		private final List<String> auditEvents = new ArrayList<>();

		@Override
		public void saveIssuedTokenHashes(
			String userId,
			String accessTokenHash,
			String refreshTokenHash,
			LocalDateTime issuedAt
		) {
		}

		@Override
		public Optional<String> findUserIdByAccessTokenHash(String accessTokenHash) {
			requestedAccessTokenHashes.add(accessTokenHash);
			return Optional.ofNullable(userIdByAccessTokenHash);
		}

		@Override
		public Optional<String> consumeRefreshTokenHash(String refreshTokenHash, LocalDateTime consumedAt) {
			return Optional.empty();
		}

		@Override
		public void saveAuditEvent(String eventType, String userId, LocalDateTime occurredAt) {
			auditEvents.add(eventType);
		}

		@Override
		public void deleteTokenHashesByUserId(String userId) {
		}
	}
}

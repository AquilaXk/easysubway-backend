package com.easysubway.auth.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.auth.application.port.out.AnonymousAuthTokenPort;
import com.easysubway.auth.application.port.out.RegisterAnonymousUserPort;
import com.easysubway.auth.domain.AnonymousAuthTokenSession;
import com.easysubway.auth.domain.AnonymousUserCredentials;
import com.easysubway.auth.domain.InvalidAnonymousAuthException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("익명 사용자 인증 서비스")
class AnonymousAuthServiceTest {

	private final FakeAnonymousUserRegistry registry = new FakeAnonymousUserRegistry();
	private final FakeAnonymousAuthTokenStore tokenStore = new FakeAnonymousAuthTokenStore();
	private final Clock clock = Clock.fixed(Instant.parse("2026-06-13T00:00:00Z"), ZoneId.of("Asia/Seoul"));

	@Test
	@DisplayName("익명 사용자 식별자와 1회 표시용 비밀번호를 발급하고 사용자 저장소에 등록한다")
	void issueAnonymousUserRegistersGeneratedCredentials() {
		var service = new AnonymousAuthService(
			registry,
			tokenStore,
			clock,
			() -> "anonymous-user-1",
			() -> "test-password-1",
			() -> "access-token-1",
			() -> "refresh-token-1"
		);

		AnonymousAuthTokenSession session = service.issueAnonymousUser();

		assertThat(session.userId()).isEqualTo("anonymous-user-1");
		assertThat(session.accessToken()).isEqualTo("access-token-1");
		assertThat(session.refreshToken()).isEqualTo("refresh-token-1");
		assertThat(session.createdAt()).isEqualTo(LocalDateTime.of(2026, 6, 13, 9, 0));
		assertThat(registry.registeredPasswordsByUserId).containsEntry("anonymous-user-1", "test-password-1");
		assertThat(tokenStore.userIdsByAccessTokenHash)
			.containsEntry(AnonymousAuthTokenHasher.sha256("access-token-1"), "anonymous-user-1");
		assertThat(tokenStore.userIdsByAccessTokenHash).doesNotContainKey("access-token-1");
		assertThat(tokenStore.refreshTokenHashes).contains(AnonymousAuthTokenHasher.sha256("refresh-token-1"));
		assertThat(tokenStore.refreshTokenHashes).doesNotContain("refresh-token-1");
	}

	@Test
	@DisplayName("이미 등록된 익명 사용자 식별자가 생성되면 새 식별자를 다시 만든다")
	void issueAnonymousUserRetriesWhenGeneratedUserIdAlreadyExists() {
		registry.registeredPasswordsByUserId.put("anonymous-user-1", "old-password");
		var generatedUserIds = new SequentialStringSupplier("anonymous-user-1", "anonymous-user-2");
		var service = new AnonymousAuthService(
			registry,
			tokenStore,
			clock,
			generatedUserIds,
			() -> "test-password-2",
			() -> "access-token-2",
			() -> "refresh-token-2"
		);

		AnonymousAuthTokenSession credentials = service.issueAnonymousUser();

		assertThat(credentials.userId()).isEqualTo("anonymous-user-2");
		assertThat(registry.registeredPasswordsByUserId).containsEntry("anonymous-user-2", "test-password-2");
	}

	@Test
	@DisplayName("현재 사용자 조회는 인증된 사용자 식별자를 익명 사용자로 반환한다")
	void currentUserReturnsAuthenticatedAnonymousUser() {
		registry.issuedAnonymousUserIds.add("anonymous-user-1");
		var service = new AnonymousAuthService(
			registry,
			tokenStore,
			clock,
			() -> "anonymous-user-1",
			() -> "test-password-1",
			() -> "access-token-1",
			() -> "refresh-token-1"
		);

		var user = service.currentUser("anonymous-user-1", "BEARER");

		assertThat(user.userId()).isEqualTo("anonymous-user-1");
		assertThat(user.authType()).isEqualTo("BEARER");
		assertThat(user.anonymous()).isTrue();
	}

	@Test
	@DisplayName("현재 사용자 조회는 고정 Basic 계정을 익명 사용자가 아닌 계정으로 반환한다")
	void currentUserReturnsNonAnonymousForConfiguredBasicUser() {
		registry.registeredPasswordsByUserId.put("configured-user", "configured-password");
		var service = new AnonymousAuthService(
			registry,
			tokenStore,
			clock,
			() -> "anonymous-user-1",
			() -> "test-password-1",
			() -> "access-token-1",
			() -> "refresh-token-1"
		);

		var user = service.currentUser("configured-user", "BASIC");

		assertThat(user.userId()).isEqualTo("configured-user");
		assertThat(user.authType()).isEqualTo("BASIC");
		assertThat(user.anonymous()).isFalse();
	}

	@Test
	@DisplayName("현재 사용자 조회는 비어 있는 사용자 식별자를 거부한다")
	void currentUserRejectsBlankUserId() {
		var service = new AnonymousAuthService(
			registry,
			tokenStore,
			clock,
			() -> "anonymous-user-1",
			() -> "test-password-1",
			() -> "access-token-1",
			() -> "refresh-token-1"
		);

		assertThatThrownBy(() -> service.currentUser("", "BEARER"))
			.isInstanceOf(InvalidAnonymousAuthException.class)
			.hasMessage("사용자 식별자가 필요합니다.");
	}

	@Test
	@DisplayName("refresh token은 회전되고 같은 token 재사용은 감사 로그를 남기고 거부된다")
	void refreshAnonymousUserRotatesRefreshTokenAndAuditsReuse() {
		var service = new AnonymousAuthService(
			registry,
			tokenStore,
			clock,
			() -> "anonymous-user-1",
			() -> "test-password-1",
			new SequentialStringSupplier("access-token-1", "access-token-2"),
			new SequentialStringSupplier("refresh-token-1", "refresh-token-2")
		);
		var issuedSession = service.issueAnonymousUser();

		var refreshedSession = service.refreshAnonymousUser(issuedSession.refreshToken());

		assertThat(refreshedSession.userId()).isEqualTo("anonymous-user-1");
		assertThat(refreshedSession.accessToken()).isEqualTo("access-token-2");
		assertThat(refreshedSession.refreshToken()).isEqualTo("refresh-token-2");
		assertThatThrownBy(() -> service.refreshAnonymousUser(issuedSession.refreshToken()))
			.isInstanceOf(InvalidAnonymousAuthException.class)
			.hasMessage("익명 인증 세션을 갱신할 수 없습니다.");
		assertThat(tokenStore.auditEvents).containsExactly("REFRESH_TOKEN_REUSED_OR_INVALID");
	}

	private static final class FakeAnonymousAuthTokenStore implements AnonymousAuthTokenPort {

		private final Map<String, String> userIdsByAccessTokenHash = new HashMap<>();
		private final Map<String, String> userIdsByRefreshTokenHash = new HashMap<>();
		private final Set<String> refreshTokenHashes = new HashSet<>();
		private final List<String> auditEvents = new ArrayList<>();

		@Override
		public void saveIssuedTokenHashes(
			String userId,
			String accessTokenHash,
			String refreshTokenHash,
			LocalDateTime issuedAt
		) {
			userIdsByAccessTokenHash.put(accessTokenHash, userId);
			userIdsByRefreshTokenHash.put(refreshTokenHash, userId);
			refreshTokenHashes.add(refreshTokenHash);
		}

		@Override
		public Optional<String> findUserIdByAccessTokenHash(String accessTokenHash) {
			return Optional.ofNullable(userIdsByAccessTokenHash.get(accessTokenHash));
		}

		@Override
		public Optional<String> consumeRefreshTokenHash(String refreshTokenHash, LocalDateTime consumedAt) {
			return Optional.ofNullable(userIdsByRefreshTokenHash.remove(refreshTokenHash));
		}

		@Override
		public void saveAuditEvent(String eventType, String userId, LocalDateTime occurredAt) {
			auditEvents.add(eventType);
		}

		@Override
		public void deleteTokenHashesByUserId(String userId) {
			userIdsByAccessTokenHash.entrySet().removeIf(entry -> entry.getValue().equals(userId));
			userIdsByRefreshTokenHash.entrySet().removeIf(entry -> entry.getValue().equals(userId));
		}
	}

	private static final class FakeAnonymousUserRegistry implements RegisterAnonymousUserPort {

		private final Map<String, String> registeredPasswordsByUserId = new HashMap<>();
		private final Set<String> issuedAnonymousUserIds = new HashSet<>();

		@Override
		public boolean existsByUserId(String userId) {
			return registeredPasswordsByUserId.containsKey(userId);
		}

		@Override
		public boolean isAnonymousUser(String userId) {
			return issuedAnonymousUserIds.contains(userId);
		}

		@Override
		public void registerAnonymousUser(AnonymousUserCredentials credentials) {
			registeredPasswordsByUserId.put(credentials.userId(), credentials.password());
			issuedAnonymousUserIds.add(credentials.userId());
		}

		@Override
		public boolean deleteAnonymousUser(String userId) {
			boolean removed = issuedAnonymousUserIds.remove(userId);
			registeredPasswordsByUserId.remove(userId);
			return removed;
		}
	}

	private static final class SequentialStringSupplier implements Supplier<String> {

		private final String[] values;
		private int index;

		private SequentialStringSupplier(String... values) {
			this.values = values;
		}

		@Override
		public String get() {
			return values[index++];
		}
	}
}

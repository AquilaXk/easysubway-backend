package com.easysubway.auth.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.auth.application.port.out.RegisterAnonymousUserPort;
import com.easysubway.auth.domain.AnonymousUserCredentials;
import com.easysubway.auth.domain.InvalidAnonymousAuthException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("익명 사용자 인증 서비스")
class AnonymousAuthServiceTest {

	private final FakeAnonymousUserRegistry registry = new FakeAnonymousUserRegistry();
	private final Clock clock = Clock.fixed(Instant.parse("2026-06-13T00:00:00Z"), ZoneId.of("Asia/Seoul"));

	@Test
	@DisplayName("익명 사용자 식별자와 1회 표시용 비밀번호를 발급하고 사용자 저장소에 등록한다")
	void issueAnonymousUserRegistersGeneratedCredentials() {
		var service = new AnonymousAuthService(
			registry,
			clock,
			() -> "anonymous-user-1",
			() -> "test-password-1"
		);

		AnonymousUserCredentials credentials = service.issueAnonymousUser();

		assertThat(credentials.userId()).isEqualTo("anonymous-user-1");
		assertThat(credentials.password()).isEqualTo("test-password-1");
		assertThat(credentials.createdAt()).isEqualTo(LocalDateTime.of(2026, 6, 13, 9, 0));
		assertThat(registry.registeredPasswordsByUserId).containsEntry("anonymous-user-1", "test-password-1");
	}

	@Test
	@DisplayName("이미 등록된 익명 사용자 식별자가 생성되면 새 식별자를 다시 만든다")
	void issueAnonymousUserRetriesWhenGeneratedUserIdAlreadyExists() {
		registry.registeredPasswordsByUserId.put("anonymous-user-1", "old-password");
		var generatedUserIds = new SequentialStringSupplier("anonymous-user-1", "anonymous-user-2");
		var service = new AnonymousAuthService(
			registry,
			clock,
			generatedUserIds,
			() -> "test-password-2"
		);

		AnonymousUserCredentials credentials = service.issueAnonymousUser();

		assertThat(credentials.userId()).isEqualTo("anonymous-user-2");
		assertThat(registry.registeredPasswordsByUserId).containsEntry("anonymous-user-2", "test-password-2");
	}

	@Test
	@DisplayName("현재 사용자 조회는 인증된 사용자 식별자를 익명 사용자로 반환한다")
	void currentUserReturnsAuthenticatedAnonymousUser() {
		registry.issuedAnonymousUserIds.add("anonymous-user-1");
		var service = new AnonymousAuthService(
			registry,
			clock,
			() -> "anonymous-user-1",
			() -> "test-password-1"
		);

		var user = service.currentUser("anonymous-user-1");

		assertThat(user.userId()).isEqualTo("anonymous-user-1");
		assertThat(user.authType()).isEqualTo("BASIC");
		assertThat(user.anonymous()).isTrue();
	}

	@Test
	@DisplayName("현재 사용자 조회는 고정 Basic 계정을 익명 사용자가 아닌 계정으로 반환한다")
	void currentUserReturnsNonAnonymousForConfiguredBasicUser() {
		registry.registeredPasswordsByUserId.put("configured-user", "configured-password");
		var service = new AnonymousAuthService(
			registry,
			clock,
			() -> "anonymous-user-1",
			() -> "test-password-1"
		);

		var user = service.currentUser("configured-user");

		assertThat(user.userId()).isEqualTo("configured-user");
		assertThat(user.authType()).isEqualTo("BASIC");
		assertThat(user.anonymous()).isFalse();
	}

	@Test
	@DisplayName("현재 사용자 조회는 비어 있는 사용자 식별자를 거부한다")
	void currentUserRejectsBlankUserId() {
		var service = new AnonymousAuthService(
			registry,
			clock,
			() -> "anonymous-user-1",
			() -> "test-password-1"
		);

		assertThatThrownBy(() -> service.currentUser(""))
			.isInstanceOf(InvalidAnonymousAuthException.class)
			.hasMessage("사용자 식별자가 필요합니다.");
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

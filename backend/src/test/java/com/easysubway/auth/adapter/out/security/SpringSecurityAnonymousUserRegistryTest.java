package com.easysubway.auth.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.auth.domain.AnonymousUserCredentials;
import com.easysubway.common.security.ConcurrentUserDetailsManager;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

@DisplayName("Spring Security 익명 사용자 저장소")
class SpringSecurityAnonymousUserRegistryTest {

	private final ConcurrentUserDetailsManager userDetailsManager = new ConcurrentUserDetailsManager();
	private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder =
		PasswordEncoderFactories.createDelegatingPasswordEncoder();
	private final SpringSecurityAnonymousUserRegistry registry = new SpringSecurityAnonymousUserRegistry(
		userDetailsManager,
		passwordEncoder,
		10_000
	);

	@Test
	@DisplayName("익명 사용자 비밀번호는 인코딩된 값으로 등록한다")
	void registerAnonymousUserEncodesPassword() {
		registry.registerAnonymousUser(new AnonymousUserCredentials(
			"anonymous-user-1",
			"raw-password-1",
			LocalDateTime.of(2026, 6, 13, 9, 0)
		));

		var user = userDetailsManager.loadUserByUsername("anonymous-user-1");

		assertThat(user.getPassword()).isNotEqualTo("raw-password-1");
		assertThat(passwordEncoder.matches("raw-password-1", user.getPassword())).isTrue();
		assertThat(user.getAuthorities())
			.extracting(GrantedAuthority::getAuthority)
			.containsExactly("ROLE_USER");
	}

	@Test
	@DisplayName("익명 발급 사용자와 고정 Basic 사용자를 구분한다")
	void isAnonymousUserDistinguishesIssuedAnonymousUserFromConfiguredUser() {
		userDetailsManager.createUser(User.withUsername("configured-user")
			.password(passwordEncoder.encode("configured-password"))
			.roles("USER")
			.build());
		registry.registerAnonymousUser(new AnonymousUserCredentials(
			"anonymous-user-1",
			"raw-password-1",
			LocalDateTime.of(2026, 6, 13, 9, 0)
		));

		assertThat(registry.isAnonymousUser("configured-user")).isFalse();
		assertThat(registry.isAnonymousUser("anonymous-user-1")).isTrue();
	}

	@Test
	@DisplayName("익명 사용자 보관 한도를 넘으면 가장 오래된 발급 사용자를 제거한다")
	void registerAnonymousUserEvictsOldestIssuedUserWhenLimitIsExceeded() {
		var limitedRegistry = new SpringSecurityAnonymousUserRegistry(
			userDetailsManager,
			passwordEncoder,
			1
		);

		limitedRegistry.registerAnonymousUser(new AnonymousUserCredentials(
			"anonymous-user-1",
			"raw-password-1",
			LocalDateTime.of(2026, 6, 13, 9, 0)
		));
		limitedRegistry.registerAnonymousUser(new AnonymousUserCredentials(
			"anonymous-user-2",
			"raw-password-2",
			LocalDateTime.of(2026, 6, 13, 9, 1)
		));

		assertThat(limitedRegistry.existsByUserId("anonymous-user-1")).isFalse();
		assertThat(limitedRegistry.isAnonymousUser("anonymous-user-1")).isFalse();
		assertThat(limitedRegistry.existsByUserId("anonymous-user-2")).isTrue();
		assertThat(limitedRegistry.isAnonymousUser("anonymous-user-2")).isTrue();
	}
}

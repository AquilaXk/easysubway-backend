package com.easysubway.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.userdetails.User;

@DisplayName("동시성 안전 사용자 저장소")
class ConcurrentUserDetailsManagerTest {

	@Test
	@DisplayName("런타임 사용자 등록 중에도 기존 사용자를 안정적으로 조회한다")
	void loadUserWhileCreatingRuntimeUsers() throws Exception {
		var userDetailsManager = new ConcurrentUserDetailsManager();
		userDetailsManager.createUser(User.withUsername("configured-user")
			.password("{noop}configured-password")
			.roles("USER")
			.build());
		List<Callable<Void>> tasks = new ArrayList<>();
		for (int index = 0; index < 100; index++) {
			int userIndex = index;
			tasks.add(() -> {
				userDetailsManager.createUser(User.withUsername("anonymous-" + userIndex)
					.password("{noop}anonymous-password-" + userIndex)
					.roles("USER")
					.build());
				return null;
			});
			tasks.add(() -> {
				assertThat(userDetailsManager.loadUserByUsername("configured-user").getUsername())
					.isEqualTo("configured-user");
				return null;
			});
		}

		try (var executor = Executors.newFixedThreadPool(8)) {
			for (var result : executor.invokeAll(tasks)) {
				result.get();
			}
		}

		assertThat(userDetailsManager.userExists("anonymous-99")).isTrue();
	}

	@Test
	@DisplayName("조회한 사용자 객체에서 인증 정보를 지워도 저장된 비밀번호는 유지한다")
	void loadUserReturnsCopyThatDoesNotMutateStoredPassword() {
		var userDetailsManager = new ConcurrentUserDetailsManager();
		userDetailsManager.createUser(User.withUsername("anonymous-user")
			.password("{noop}raw-password")
			.roles("USER")
			.build());
		var loadedUser = userDetailsManager.loadUserByUsername("anonymous-user");

		((CredentialsContainer) loadedUser).eraseCredentials();

		assertThat(userDetailsManager.loadUserByUsername("anonymous-user").getPassword())
			.isEqualTo("{noop}raw-password");
	}
}

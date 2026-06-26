package com.easysubway.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;

@DisplayName("보안 설정")
class SecurityConfigTest {

	private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(
			SecurityAutoConfiguration.class,
			WebMvcAutoConfiguration.class
		))
		.withUserConfiguration(SecurityConfig.class);

	@Test
	@DisplayName("운영 프로필은 관리자 계정 설정이 없으면 시작하지 않는다")
	void prodProfileFailsWhenAdminCredentialsAreMissing() {
		contextRunner
			.withPropertyValues("spring.profiles.active=prod")
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure())
					.hasMessageContaining("운영 관리자 계정 설정이 필요합니다.");
			});
	}

	@Test
	@DisplayName("개발 프로필은 관리자 계정 없이도 로컬 실행을 허용한다")
	void devProfileAllowsMissingAdminCredentials() {
		contextRunner
			.withPropertyValues("spring.profiles.active=dev")
			.run(context -> assertThat(context).hasNotFailed());
	}

	@Test
	@DisplayName("운영 프로필은 관리자 계정 설정이 있으면 시작한다")
	void prodProfileStartsWhenAdminCredentialsAreConfigured() {
		contextRunner
			.withPropertyValues(
				"spring.profiles.active=prod",
				"easysubway.admin.username=admin-user",
				"easysubway.admin.password=admin-password",
				"easysubway.admin.basic-auth.enabled=false"
			)
			.run(context -> assertThat(context).hasNotFailed());
	}

	@Test
	@DisplayName("운영 프로필은 Basic auth 예외 owner와 만료일 없이 Basic auth를 켤 수 없다")
	void prodProfileRejectsBasicAuthWithoutReleaseException() {
		contextRunner
			.withPropertyValues(
				"spring.profiles.active=prod",
				"easysubway.admin.username=admin-user",
				"easysubway.admin.password=admin-password",
				"easysubway.admin.basic-auth.enabled=true"
			)
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure())
					.hasMessageContaining("운영 Basic auth 예외는 owner와 만료일이 필요합니다.");
			});
	}

	@Test
	@DisplayName("운영 프로필은 만료일 있는 Basic auth 예외를 명시하면 시작한다")
	void prodProfileAllowsBasicAuthWithReleaseException() {
		contextRunner
			.withPropertyValues(
				"spring.profiles.active=prod",
				"easysubway.admin.username=admin-user",
				"easysubway.admin.password=admin-password",
				"easysubway.admin.basic-auth.enabled=true",
				"easysubway.admin.basic-auth.exception-owner=security-owner",
				"easysubway.admin.basic-auth.exception-expires-at=2099-12-31"
			)
			.run(context -> assertThat(context).hasNotFailed());
	}

	@Test
	@DisplayName("운영기관 관리자 계정 설정이 있으면 전용 역할 사용자를 등록한다")
	void operatorAdminCredentialsRegisterOperatorAdminUser() {
		contextRunner
			.withPropertyValues(
				"easysubway.operator.username=operator-user",
				"easysubway.operator.password=operator-password"
			)
			.run(context -> {
				assertThat(context).hasNotFailed();
				UserDetailsService userDetailsService = context.getBean(UserDetailsService.class);

				assertThat(userDetailsService.loadUserByUsername("operator-user").getAuthorities())
					.extracting(GrantedAuthority::getAuthority)
					.containsExactly("ROLE_OPERATOR_ADMIN");
			});
	}

	@Test
	@DisplayName("운영기관 관리자 계정은 아이디와 비밀번호를 함께 설정해야 한다")
	void operatorAdminCredentialsFailWhenPartiallyConfigured() {
		contextRunner
			.withPropertyValues("easysubway.operator.username=operator-user")
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure())
					.hasMessageContaining("운영기관 관리자 계정 설정은 아이디와 비밀번호를 함께 입력해야 합니다.");
			});

		contextRunner
			.withPropertyValues("easysubway.operator.password=operator-password")
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure())
					.hasMessageContaining("운영기관 관리자 계정 설정은 아이디와 비밀번호를 함께 입력해야 합니다.");
			});
	}

	@Test
	@DisplayName("관리자 Basic auth는 연속 실패 후 잠금 기간 동안 올바른 비밀번호도 거절한다")
	void adminBasicAuthLocksAfterConsecutiveFailures() {
		contextRunner
			.withPropertyValues(
				"easysubway.admin.username=admin-user",
				"easysubway.admin.password=admin-password",
				"easysubway.admin.lockout.max-failures=2",
				"easysubway.admin.lockout.duration=PT10M"
			)
			.run(context -> {
				assertThat(context).hasNotFailed();
				AuthenticationManager authenticationManager = context.getBean(AuthenticationConfiguration.class)
					.getAuthenticationManager();

				assertThatThrownBy(() -> authenticate(authenticationManager, "admin-user", "bad-password"))
					.isInstanceOf(BadCredentialsException.class);
				assertThatThrownBy(() -> authenticate(authenticationManager, "admin-user", "bad-password"))
					.isInstanceOf(BadCredentialsException.class);
				assertThatThrownBy(() -> authenticate(authenticationManager, "admin-user", "admin-password"))
					.isInstanceOf(LockedException.class)
					.hasMessageContaining("관리자 인증 실패 횟수가 초과되었습니다.");
			});
	}

	@Test
	@DisplayName("운영기관 Basic auth 성공은 실패 카운터를 초기화한다")
	void operatorBasicAuthSuccessResetsFailureCounter() {
		contextRunner
			.withPropertyValues(
				"easysubway.operator.username=operator-user",
				"easysubway.operator.password=operator-password",
				"easysubway.admin.lockout.max-failures=2",
				"easysubway.admin.lockout.duration=PT10M"
			)
			.run(context -> {
				assertThat(context).hasNotFailed();
				AuthenticationManager authenticationManager = context.getBean(AuthenticationConfiguration.class)
					.getAuthenticationManager();

				assertThatThrownBy(() -> authenticate(authenticationManager, "operator-user", "bad-password"))
					.isInstanceOf(BadCredentialsException.class);
				assertThat(authenticate(authenticationManager, "operator-user", "operator-password").isAuthenticated())
					.isTrue();
				assertThatThrownBy(() -> authenticate(authenticationManager, "operator-user", "bad-password"))
					.isInstanceOf(BadCredentialsException.class);
				assertThat(authenticate(authenticationManager, "operator-user", "operator-password").isAuthenticated())
					.isTrue();
			});
	}

	private org.springframework.security.core.Authentication authenticate(
		AuthenticationManager authenticationManager,
		String username,
		String password
	) {
		return authenticationManager.authenticate(
			UsernamePasswordAuthenticationToken.unauthenticated(username, password)
		);
	}

}

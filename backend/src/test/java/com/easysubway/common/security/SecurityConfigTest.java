package com.easysubway.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
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
				"easysubway.admin.password=admin-password"
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
}

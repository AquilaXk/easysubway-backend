package com.easysubway.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.admin.authorization.adapter.out.persistence.InMemoryAdminRbacAuthorityRepository;
import com.easysubway.admin.identity.adapter.out.persistence.InMemoryAdminIdentityRepository;
import com.easysubway.admin.identity.application.port.out.AdminIdentityRepository;
import com.easysubway.admin.identity.domain.AdminIdentity;
import com.easysubway.admin.identity.domain.AdminIdentityAuthMethod;
import com.easysubway.admin.identity.domain.AdminIdentityRole;
import com.easysubway.admin.identity.domain.AdminIdentityStatus;
import java.time.LocalDateTime;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

@DisplayName("보안 설정")
class SecurityConfigTest {

	private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(
			SecurityAutoConfiguration.class,
			WebMvcAutoConfiguration.class
		))
		.withUserConfiguration(SecurityConfig.class, TestAdminIdentityRepositoryConfig.class);

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
				AdminIdentityRepository repository = context.getBean(AdminIdentityRepository.class);
				assertThat(repository.findByLoginId("operator-user").orElseThrow().role())
					.isEqualTo(AdminIdentityRole.OPERATOR_ADMIN);
			});
	}

	@Test
	@DisplayName("관리자 계정은 RBAC permission authority를 함께 가진다")
	void adminCredentialsRegisterPermissionAuthorities() {
		contextRunner
			.withPropertyValues(
				"easysubway.admin.username=admin-user",
				"easysubway.admin.password=admin-password"
			)
			.run(context -> {
				assertThat(context).hasNotFailed();
				UserDetailsService userDetailsService = context.getBean(UserDetailsService.class);

				assertThat(userDetailsService.loadUserByUsername("admin-user").getAuthorities())
					.extracting(GrantedAuthority::getAuthority)
					.contains(
						"ROLE_ADMIN",
						"admin.view",
						"admin.report.review",
						"admin.master.edit",
						"admin.field.operate",
						"admin.data.operate",
						"admin.security.audit",
						"admin.security.admin"
					);
			});
	}

	@Test
	@DisplayName("관리자 RBAC role 할당이 있으면 할당 permission만 authority로 가진다")
	void adminCredentialsUseAssignedRbacAuthorities() {
		contextRunner
			.withPropertyValues(
				"easysubway.admin.username=admin-user",
				"easysubway.admin.password=admin-password"
			)
			.run(context -> {
				assertThat(context).hasNotFailed();
				InMemoryAdminRbacAuthorityRepository rbacRepository =
					context.getBean(InMemoryAdminRbacAuthorityRepository.class);
				rbacRepository.replacePermissionAuthorities("admin-user", Set.of("admin.view", "admin.report.review"));
				UserDetailsService userDetailsService = context.getBean(UserDetailsService.class);

				assertThat(userDetailsService.loadUserByUsername("admin-user").getAuthorities())
					.extracting(GrantedAuthority::getAuthority)
					.contains("ROLE_ADMIN", "admin.view", "admin.report.review")
					.doesNotContain("admin.data.operate", "admin.master.edit", "admin.security.admin");
			});
	}

	@Test
	@DisplayName("인메모리 RBAC 저장소는 선언되지 않은 permission authority를 거절한다")
	void inMemoryAdminRbacRejectsUnknownAuthority() {
		var rbacRepository = new InMemoryAdminRbacAuthorityRepository();

		assertThatThrownBy(() -> rbacRepository.replacePermissionAuthorities(
			"admin-user",
			Set.of("admin.view", "admin.unknown")
		))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("선언되지 않은 관리자 permission authority");
	}

	@Test
	@DisplayName("영속 관리자 계정은 RBAC role 미할당만으로 full permission을 얻지 않는다")
	void persistentAdminWithoutRbacAssignmentDoesNotReceiveFullPermissions() {
		contextRunner
			.run(context -> {
				assertThat(context).hasNotFailed();
				var passwordEncoder = context.getBean(org.springframework.security.crypto.password.PasswordEncoder.class);
				AdminIdentityRepository repository = context.getBean(AdminIdentityRepository.class);
				LocalDateTime now = LocalDateTime.of(2026, 6, 27, 0, 0);
				repository.save(new AdminIdentity(
					"persistent-admin",
					"영속 관리자",
					null,
					passwordEncoder.encode("admin-password"),
					AdminIdentityAuthMethod.LOCAL,
					AdminIdentityRole.ADMIN,
					AdminIdentityStatus.ACTIVE,
					0,
					null,
					now,
					null,
					false,
					null,
					false,
					now,
					now
				));
				UserDetailsService userDetailsService = context.getBean(UserDetailsService.class);

				assertThat(userDetailsService.loadUserByUsername("persistent-admin").getAuthorities())
					.extracting(GrantedAuthority::getAuthority)
					.containsExactly("ROLE_ADMIN");
			});
	}

	@Test
	@DisplayName("관리자 계정 설정이 있으면 영속 identity 저장소에 bootstrap한다")
	void adminCredentialsBootstrapPersistentIdentity() {
		contextRunner
			.withPropertyValues(
				"easysubway.admin.username=admin-user",
				"easysubway.admin.password=admin-password"
			)
			.run(context -> {
				assertThat(context).hasNotFailed();
				AdminIdentityRepository repository = context.getBean(AdminIdentityRepository.class);

				var identity = repository.findByLoginId("admin-user").orElseThrow();
				assertThat(identity.authMethod()).isEqualTo(AdminIdentityAuthMethod.LOCAL);
				assertThat(identity.role()).isEqualTo(AdminIdentityRole.ADMIN);
				assertThat(identity.status()).isEqualTo(AdminIdentityStatus.ACTIVE);
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
	@DisplayName("break-glass 계정은 아이디, 비밀번호, 사유를 함께 설정해야 한다")
	void breakGlassCredentialsFailWhenPartiallyConfigured() {
		contextRunner
			.withPropertyValues(
				"easysubway.admin.break-glass.username=break-glass",
				"easysubway.admin.break-glass.password=break-password"
			)
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure())
					.hasMessageContaining("break-glass 계정 설정은 아이디, 비밀번호, 사유를 함께 입력해야 합니다.");
			});
	}

	@Test
	@DisplayName("일반 사용자 계정 ID는 관리자 계정 ID와 달라야 한다")
	void userCredentialsFailWhenLoginIdCollidesWithAdminIdentity() {
		contextRunner
			.withPropertyValues(
				"easysubway.admin.username=shared-user",
				"easysubway.admin.password=admin-password",
				"easysubway.user.username=shared-user",
				"easysubway.user.password=user-password"
			)
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure())
					.hasMessageContaining("관리자, 운영기관, break-glass, 일반 사용자 계정 ID는 서로 달라야 합니다.");
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

	@Test
	@DisplayName("break-glass Basic auth 성공은 감사 사유를 남기고 credential rotation을 요구한다")
	void breakGlassAuthRecordsReasonAndRequiresCredentialRotation() {
		contextRunner
			.withPropertyValues(
				"easysubway.admin.username=admin-user",
				"easysubway.admin.password=admin-password",
				"easysubway.admin.break-glass.username=break-glass",
				"easysubway.admin.break-glass.password=break-password",
				"easysubway.admin.break-glass.reason=정기 관리자 계정 접근 장애 대응"
			)
			.run(context -> {
				assertThat(context).hasNotFailed();
				AuthenticationManager authenticationManager = context.getBean(AuthenticationConfiguration.class)
					.getAuthenticationManager();
				InMemoryAdminIdentityRepository repository = context.getBean(InMemoryAdminIdentityRepository.class);

				assertThat(authenticate(authenticationManager, "break-glass", "break-password").isAuthenticated())
					.isTrue();
				assertThat(repository.findByLoginId("break-glass").orElseThrow().status())
					.isEqualTo(AdminIdentityStatus.CREDENTIAL_ROTATION_REQUIRED);
				assertThat(repository.audits())
					.anySatisfy(audit -> {
						assertThat(audit.loginId()).isEqualTo("break-glass");
						assertThat(audit.authMethod()).isEqualTo(AdminIdentityAuthMethod.BREAK_GLASS);
						assertThat(audit.outcome()).isEqualTo("SUCCESS");
						assertThat(audit.reason()).isEqualTo("정기 관리자 계정 접근 장애 대응");
					});

				assertThatThrownBy(() -> authenticate(authenticationManager, "break-glass", "break-password"))
					.isInstanceOf(DisabledException.class);
			});
	}

	@Test
	@DisplayName("관리자 bootstrap은 배포 secret이 바뀌면 기존 identity 비밀번호 해시를 갱신한다")
	void adminBootstrapUpdatesStoredPasswordWhenDeploymentSecretRotates() {
		var securityConfig = new SecurityConfig();
		var repository = new InMemoryAdminIdentityRepository();
		var passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
		var environment = new MockEnvironment();

		securityConfig.userDetailsService(
			"admin-user",
			"old-admin-password",
			"",
			"",
			"",
			"",
			"",
			"",
			"",
			false,
			"",
			"",
			repository,
			passwordEncoder,
			environment
		);

		securityConfig.userDetailsService(
			"admin-user",
			"new-admin-password",
			"",
			"",
			"",
			"",
			"",
			"",
			"",
			false,
			"",
			"",
			repository,
			passwordEncoder,
			environment
		);

		var identity = repository.findByLoginId("admin-user").orElseThrow();
		assertThat(passwordEncoder.matches("new-admin-password", identity.passwordHash())).isTrue();
		assertThat(passwordEncoder.matches("old-admin-password", identity.passwordHash())).isFalse();
		assertThat(identity.failedLoginCount()).isZero();
	}

	@Test
	@DisplayName("bootstrap 설정에서 제거된 영속 관리자 계정은 시작 시 비활성화한다")
	void removedBootstrapIdentitiesAreDisabledOnStartup() {
		var securityConfig = new SecurityConfig();
		var repository = new InMemoryAdminIdentityRepository();
		var passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
		var environment = new MockEnvironment();

		securityConfig.userDetailsService(
			"old-admin",
			"old-admin-password",
			"old-break-glass",
			"old-break-password",
			"운영 장애 대응",
			"old-operator",
			"old-operator-password",
			"",
			"",
			false,
			"",
			"",
			repository,
			passwordEncoder,
			environment
		);

		securityConfig.userDetailsService(
			"new-admin",
			"new-admin-password",
			"",
			"",
			"",
			"",
			"",
			"",
			"",
			false,
			"",
			"",
			repository,
			passwordEncoder,
			environment
		);

		assertThat(repository.findByLoginId("old-admin").orElseThrow().status())
			.isEqualTo(AdminIdentityStatus.DISABLED);
		assertThat(repository.findByLoginId("old-operator").orElseThrow().status())
			.isEqualTo(AdminIdentityStatus.DISABLED);
		assertThat(repository.findByLoginId("old-break-glass").orElseThrow().status())
			.isEqualTo(AdminIdentityStatus.DISABLED);
		assertThat(repository.findByLoginId("new-admin").orElseThrow().status())
			.isEqualTo(AdminIdentityStatus.ACTIVE);
	}

	@Test
	@DisplayName("제거 후 같은 설정으로 복구된 bootstrap 계정은 다시 활성화한다")
	void restoredBootstrapIdentityBecomesActiveAgain() {
		var securityConfig = new SecurityConfig();
		var repository = new InMemoryAdminIdentityRepository();
		var passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
		var environment = new MockEnvironment();

		securityConfig.userDetailsService(
			"admin-user",
			"admin-password",
			"",
			"",
			"",
			"",
			"",
			"",
			"",
			false,
			"",
			"",
			repository,
			passwordEncoder,
			environment
		);
		securityConfig.userDetailsService(
			"replacement-admin",
			"replacement-password",
			"",
			"",
			"",
			"",
			"",
			"",
			"",
			false,
			"",
			"",
			repository,
			passwordEncoder,
			environment
		);
		assertThat(repository.findByLoginId("admin-user").orElseThrow().status())
			.isEqualTo(AdminIdentityStatus.DISABLED);

		securityConfig.userDetailsService(
			"admin-user",
			"admin-password",
			"",
			"",
			"",
			"",
			"",
			"",
			"",
			false,
			"",
			"",
			repository,
			passwordEncoder,
			environment
		);

		assertThat(repository.findByLoginId("admin-user").orElseThrow().status())
			.isEqualTo(AdminIdentityStatus.ACTIVE);
		assertThat(repository.findByLoginId("replacement-admin").orElseThrow().status())
			.isEqualTo(AdminIdentityStatus.DISABLED);
	}

	@Test
	@DisplayName("break-glass bootstrap은 사용 전에는 비밀번호 만료 시각을 저장하지 않는다")
	void breakGlassBootstrapDoesNotExpireBeforeFirstUse() {
		var securityConfig = new SecurityConfig();
		var repository = new InMemoryAdminIdentityRepository();
		var passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
		var environment = new MockEnvironment();

		securityConfig.userDetailsService(
			"admin-user",
			"admin-password",
			"break-glass",
			"break-password",
			"운영 장애 대응",
			"",
			"",
			"",
			"",
			false,
			"",
			"",
			repository,
			passwordEncoder,
			environment
		);
		securityConfig.userDetailsService(
			"admin-user",
			"admin-password",
			"break-glass",
			"break-password",
			"운영 장애 대응",
			"",
			"",
			"",
			"",
			false,
			"",
			"",
			repository,
			passwordEncoder,
			environment
		);

		var breakGlass = repository.findByLoginId("break-glass").orElseThrow();
		assertThat(breakGlass.status()).isEqualTo(AdminIdentityStatus.ACTIVE);
		assertThat(breakGlass.passwordExpiresAt()).isNull();
	}

	@Test
	@DisplayName("break-glass bootstrap은 같은 비밀번호면 reason 변경만으로 rotation 요구를 해제하지 않는다")
	void breakGlassBootstrapKeepsRotationRequirementWhenSecretDidNotChange() {
		var securityConfig = new SecurityConfig();
		var repository = new InMemoryAdminIdentityRepository();
		var passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
		var environment = new MockEnvironment();

		securityConfig.userDetailsService(
			"admin-user",
			"admin-password",
			"break-glass",
			"break-password",
			"운영 장애 후속 기록 변경",
			"",
			"",
			"",
			"",
			false,
			"",
			"",
			repository,
			passwordEncoder,
			environment
		);
		var usedBreakGlass = repository.findByLoginId("break-glass")
			.orElseThrow()
			.recordBreakGlassSuccess(LocalDateTime.of(2026, 6, 27, 0, 0));
		repository.save(usedBreakGlass);

		securityConfig.userDetailsService(
			"admin-user",
			"admin-password",
			"break-glass",
			"break-password",
			"운영 장애 대응",
			"",
			"",
			"",
			"",
			false,
			"",
			"",
			repository,
			passwordEncoder,
			environment
		);

		assertThat(repository.findByLoginId("break-glass").orElseThrow().status())
			.isEqualTo(AdminIdentityStatus.CREDENTIAL_ROTATION_REQUIRED);
	}

	@Test
	@DisplayName("break-glass bootstrap은 비밀번호가 바뀌면 rotation 요구를 해제하고 새 비밀번호를 저장한다")
	void breakGlassBootstrapRestoresAccessWhenSecretChanges() {
		var securityConfig = new SecurityConfig();
		var repository = new InMemoryAdminIdentityRepository();
		var passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
		var environment = new MockEnvironment();

		securityConfig.userDetailsService(
			"admin-user",
			"admin-password",
			"break-glass",
			"old-break-password",
			"운영 장애 대응",
			"",
			"",
			"",
			"",
			false,
			"",
			"",
			repository,
			passwordEncoder,
			environment
		);
		repository.save(repository.findByLoginId("break-glass")
			.orElseThrow()
			.recordBreakGlassSuccess(LocalDateTime.of(2026, 6, 27, 0, 0)));

		securityConfig.userDetailsService(
			"admin-user",
			"admin-password",
			"break-glass",
			"new-break-password",
			"운영 장애 대응",
			"",
			"",
			"",
			"",
			false,
			"",
			"",
			repository,
			passwordEncoder,
			environment
		);

		var breakGlass = repository.findByLoginId("break-glass").orElseThrow();
		assertThat(breakGlass.status()).isEqualTo(AdminIdentityStatus.ACTIVE);
		assertThat(passwordEncoder.matches("old-break-password", breakGlass.passwordHash())).isFalse();
		assertThat(passwordEncoder.matches("new-break-password", breakGlass.passwordHash())).isTrue();
	}

	@Test
	@DisplayName("사용 완료된 break-glass 계정은 제거 후 같은 비밀번호로 복구해도 재활성화하지 않는다")
	void breakGlassBootstrapKeepsRotationRequirementAfterDisableWhenSecretDidNotChange() {
		var securityConfig = new SecurityConfig();
		var repository = new InMemoryAdminIdentityRepository();
		var passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
		var environment = new MockEnvironment();

		securityConfig.userDetailsService(
			"admin-user",
			"admin-password",
			"break-glass",
			"break-password",
			"운영 장애 대응",
			"",
			"",
			"",
			"",
			false,
			"",
			"",
			repository,
			passwordEncoder,
			environment
		);
		repository.save(repository.findByLoginId("break-glass")
			.orElseThrow()
			.recordBreakGlassSuccess(LocalDateTime.of(2026, 6, 27, 0, 0)));
		securityConfig.userDetailsService(
			"admin-user",
			"admin-password",
			"",
			"",
			"",
			"",
			"",
			"",
			"",
			false,
			"",
			"",
			repository,
			passwordEncoder,
			environment
		);

		securityConfig.userDetailsService(
			"admin-user",
			"admin-password",
			"break-glass",
			"break-password",
			"운영 장애 대응",
			"",
			"",
			"",
			"",
			false,
			"",
			"",
			repository,
			passwordEncoder,
			environment
		);

		var breakGlass = repository.findByLoginId("break-glass").orElseThrow();
		assertThat(breakGlass.status()).isEqualTo(AdminIdentityStatus.DISABLED);
		assertThat(breakGlass.credentialRotationRequired()).isTrue();
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

	@TestConfiguration
	static class TestAdminIdentityRepositoryConfig {

		@Bean
		InMemoryAdminIdentityRepository adminIdentityRepository() {
			return new InMemoryAdminIdentityRepository();
		}

		@Bean
		InMemoryAdminRbacAuthorityRepository adminRbacAuthorityRepository() {
			return new InMemoryAdminRbacAuthorityRepository();
		}
	}

}

package com.easysubway.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.admin.audit.adapter.out.persistence.InMemoryAdminAuditEventRepository;
import com.easysubway.admin.authorization.adapter.out.persistence.InMemoryAdminRbacAuthorityRepository;
import com.easysubway.admin.identity.adapter.out.persistence.InMemoryAdminIdentityRepository;
import com.easysubway.admin.identity.application.port.out.AdminIdentityRepository;
import com.easysubway.admin.identity.application.service.AdminIdentityUserDetailsService;
import com.easysubway.admin.identity.domain.AdminIdentity;
import com.easysubway.admin.identity.domain.AdminIdentityAuthMethod;
import com.easysubway.admin.identity.domain.AdminIdentityRole;
import com.easysubway.admin.identity.domain.AdminIdentityStatus;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
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
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
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
	@DisplayName("비운영 프로필의 partial 관리자 credential은 identity mutation 전에 거부한다")
	void partialAdminCredentialsFailBeforeIdentityMutationOutsideProd() {
		var securityConfig = new SecurityConfig();
		var repository = new InMemoryAdminIdentityRepository();
		var rbacRepository = new InMemoryAdminRbacAuthorityRepository();
		var passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();

		assertThatThrownBy(() -> securityConfig.userDetailsService(
			"admin-user", "", "", "", "", "", false, "", "",
			repository, rbacRepository, passwordEncoder, new MockEnvironment()
		))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("관리자 계정 설정은 아이디와 비밀번호를 함께 입력해야 합니다.");
		assertThat(repository.findByLoginId("admin-user")).isEmpty();
		assertThat(rbacRepository.findPermissionAuthorities("admin-user")).isEmpty();
	}

	@Test
	@DisplayName("운영 프로필은 관리자 계정 설정이 있으면 시작한다")
	void prodProfileStartsWhenAdminCredentialsAreConfigured() {
		contextRunner
			.withPropertyValues(
				"spring.profiles.active=prod",
				"easysubway.admin.username=admin-user",
				"easysubway.admin.password=admin-password",
				"easysubway.admin.remember-me.key=0123456789abcdef0123456789abcdef",
				"easysubway.admin.basic-auth.enabled=false"
			)
			.run(context -> assertThat(context).hasNotFailed());
	}

	@Test
	@DisplayName("운영 프로필은 관리자 로그인 유지 서명 키가 없으면 시작하지 않는다")
	void prodProfileFailsWhenAdminRememberMeKeyIsMissing() {
		contextRunner
			.withPropertyValues(
				"spring.profiles.active=prod",
				"easysubway.admin.username=admin-user",
				"easysubway.admin.password=admin-password",
				"easysubway.admin.basic-auth.enabled=false"
			)
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure())
					.hasMessageContaining("운영 관리자 로그인 유지 서명 키는 32자 이상이어야 합니다.");
			});
	}

	@Test
	@DisplayName("운영 프로필은 Basic auth 예외 owner와 만료일 없이 Basic auth를 켤 수 없다")
	void prodProfileRejectsBasicAuthWithoutReleaseException() {
		contextRunner
			.withPropertyValues(
				"spring.profiles.active=prod",
				"easysubway.admin.username=admin-user",
				"easysubway.admin.password=admin-password",
				"easysubway.admin.remember-me.key=0123456789abcdef0123456789abcdef",
				"easysubway.admin.basic-auth.enabled=true"
			)
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure())
					.hasMessageContaining("운영 Basic auth 예외는 owner와 만료일이 필요합니다.");
			});
	}

	@Test
	@DisplayName("운영 프로필은 고정된 과거 만료일의 Basic auth 예외를 거부한다")
	void prodProfileRejectsExpiredBasicAuthReleaseException() {
		contextRunner
			.withPropertyValues(
				"spring.profiles.active=prod",
				"easysubway.admin.username=admin-user",
				"easysubway.admin.password=admin-password",
				"easysubway.admin.remember-me.key=0123456789abcdef0123456789abcdef",
				"easysubway.admin.basic-auth.enabled=true",
				"easysubway.admin.basic-auth.exception-owner=security-owner",
				"easysubway.admin.basic-auth.exception-expires-at=2000-01-01"
			)
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure())
					.hasMessageContaining("운영 Basic auth 예외 만료일이 지났습니다.");
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
				"easysubway.admin.remember-me.key=0123456789abcdef0123456789abcdef",
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
							"admin.security.admin",
							"admin.datapack.override.approve",
							"admin.datapack.production.approve",
							"admin.datapack.rollback"
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
	@DisplayName("인메모리 RBAC 저장소는 데이터팩 운영 세분 권한을 선언된 permission으로 허용한다")
	void inMemoryAdminRbacAcceptsDatapackAuthorities() {
		var rbacRepository = new InMemoryAdminRbacAuthorityRepository();
		var datapackAuthorities = Set.of(
			"admin.datapack.read",
			"admin.datapack.source.run",
			"admin.datapack.alias.review",
			"admin.datapack.quarantine.review",
			"admin.datapack.evidence.review",
			"admin.datapack.override.request",
			"admin.datapack.override.approve",
			"admin.datapack.candidate.build",
			"admin.datapack.staging.promote",
			"admin.datapack.production.approve",
			"admin.datapack.rollback",
			"admin.datapack.audit.read"
		);

		rbacRepository.replacePermissionAuthorities(
			"datapack-admin",
			datapackAuthorities
		);

		assertThat(rbacRepository.findPermissionAuthorities("datapack-admin"))
			.containsExactlyInAnyOrderElementsOf(datapackAuthorities);
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
	@DisplayName("env 지정 관리자 계정은 선존재 영속 계정이어도 부팅 시 RBAC SUPER_ADMIN role seed로 관리자 권한을 얻는다")
	void envAdminSeedsSuperAdminRoleForPreexistingUnmanagedIdentity() {
		var securityConfig = new SecurityConfig();
		var adminRepository = new InMemoryAdminIdentityRepository();
		var rbacRepository = new InMemoryAdminRbacAuthorityRepository();
		var passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
		var environment = new MockEnvironment();
		LocalDateTime now = LocalDateTime.of(2026, 6, 27, 0, 0);
		adminRepository.save(new AdminIdentity(
			"env-admin",
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

		var beforeSeed = new AdminIdentityUserDetailsService(
			adminRepository,
			rbacRepository,
			username -> {
				throw new UsernameNotFoundException(username);
			},
			Clock.systemUTC()
		);
		assertThat(beforeSeed.loadUserByUsername("env-admin").getAuthorities())
			.extracting(GrantedAuthority::getAuthority)
			.containsExactly("ROLE_ADMIN");

		UserDetailsService userDetailsService = securityConfig.userDetailsService(
			"env-admin",
			"admin-password",
			"",
			"",
			"",
			"",
			true,
			"",
			"",
			adminRepository,
			rbacRepository,
			passwordEncoder,
			environment
		);

		assertThat(rbacRepository.findPermissionAuthorities("env-admin"))
			.contains(
				"admin.view",
				"admin.report.review",
				"admin.master.edit",
				"admin.field.operate",
				"admin.data.operate",
				"admin.security.audit",
				"admin.security.admin"
			);
		assertThat(userDetailsService.loadUserByUsername("env-admin").getAuthorities())
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
	}

	@Test
	@DisplayName("env 관리자 RBAC role seed는 여러 번 부팅해도 중복 없이 멱등하다")
	void envAdminSuperAdminRoleSeedIsIdempotentAcrossReboots() {
		var securityConfig = new SecurityConfig();
		var adminRepository = new InMemoryAdminIdentityRepository();
		var rbacRepository = new InMemoryAdminRbacAuthorityRepository();
		var passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
		var environment = new MockEnvironment();

		securityConfig.userDetailsService(
			"env-admin", "admin-password", "", "", "", "", true, "", "",
			adminRepository, rbacRepository, passwordEncoder, environment
		);
		Set<String> afterFirstBoot = rbacRepository.findPermissionAuthorities("env-admin");

		securityConfig.userDetailsService(
			"env-admin", "admin-password", "", "", "", "", true, "", "",
			adminRepository, rbacRepository, passwordEncoder, environment
		);
		Set<String> afterSecondBoot = rbacRepository.findPermissionAuthorities("env-admin");

		assertThat(afterSecondBoot).isEqualTo(afterFirstBoot);
		assertThat(afterSecondBoot).contains("admin.view", "admin.security.admin");
	}

	@Test
	@DisplayName("env에서 관리자 계정이 제거되면 부팅 시 bootstrap-seeded SUPER_ADMIN role을 회수한다")
	void envAdminBootstrapRoleIsRevokedWhenCredentialRemoved() {
		var securityConfig = new SecurityConfig();
		var adminRepository = new InMemoryAdminIdentityRepository();
		var rbacRepository = new InMemoryAdminRbacAuthorityRepository();
		var passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
		var environment = new MockEnvironment();

		securityConfig.userDetailsService(
			"env-admin", "admin-password", "", "", "", "", true, "", "",
			adminRepository, rbacRepository, passwordEncoder, environment
		);
		assertThat(rbacRepository.findPermissionAuthorities("env-admin")).contains("admin.security.admin");

		// env에서 관리자 계정 설정이 사라진 채 부팅한다.
		securityConfig.userDetailsService(
			"", "", "", "", "", "", true, "", "",
			adminRepository, rbacRepository, passwordEncoder, environment
		);

		assertThat(rbacRepository.findPermissionAuthorities("env-admin")).isEmpty();
	}

	@Test
	@DisplayName("bootstrap 회수는 운영자가 수동 부여한 RBAC 권한을 보존한다")
	void bootstrapRevokePreservesManuallyGrantedAuthorities() {
		var securityConfig = new SecurityConfig();
		var adminRepository = new InMemoryAdminIdentityRepository();
		var rbacRepository = new InMemoryAdminRbacAuthorityRepository();
		var passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
		var environment = new MockEnvironment();
		// 운영자가 별도 계정에 수동 부여한 권한.
		rbacRepository.replacePermissionAuthorities("manual-admin", Set.of("admin.view", "admin.report.review"));

		securityConfig.userDetailsService(
			"env-admin", "admin-password", "", "", "", "", true, "", "",
			adminRepository, rbacRepository, passwordEncoder, environment
		);
		// env 계정 제거 후 재부팅해도 수동 부여 권한은 회수되지 않는다.
		securityConfig.userDetailsService(
			"", "", "", "", "", "", true, "", "",
			adminRepository, rbacRepository, passwordEncoder, environment
		);

		assertThat(rbacRepository.findPermissionAuthorities("manual-admin"))
			.containsExactlyInAnyOrder("admin.view", "admin.report.review");
		assertThat(rbacRepository.findPermissionAuthorities("env-admin")).isEmpty();
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
					.hasMessageContaining("관리자, 운영기관, 일반 사용자 계정 ID는 서로 달라야 합니다.");
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
	@DisplayName("stale bootstrap-managed break-glass identity는 시작 시 비활성화되고 bootstrap RBAC role을 잃는다")
	void staleBootstrapManagedBreakGlassIdentityIsDisabledAndRolesRevoked() {
		var securityConfig = new SecurityConfig();
		var repository = new InMemoryAdminIdentityRepository();
		var rbacRepository = new InMemoryAdminRbacAuthorityRepository();
		var passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
		var now = LocalDateTime.of(2026, 6, 27, 0, 0);
		repository.save(breakGlassIdentity(passwordEncoder, true, now));
		rbacRepository.seedRole("break-glass", com.easysubway.admin.authorization.AdminRbacRole.SUPER_ADMIN);

		securityConfig.userDetailsService(
			"admin-user", "admin-password", "", "", "", "", false, "", "",
			repository, rbacRepository, passwordEncoder, new MockEnvironment()
		);

		assertThat(repository.findByLoginId("break-glass").orElseThrow().status())
			.isEqualTo(AdminIdentityStatus.DISABLED);
		assertThat(rbacRepository.findPermissionAuthorities("break-glass")).isEmpty();
	}

	@Test
	@DisplayName("외부 관리 break-glass identity는 명시 RBAC와 기존 감사·rotation 동작을 보존한다")
	void externallyManagedBreakGlassIdentityPreservesExplicitRbacAuditAndRotation() {
		var securityConfig = new SecurityConfig();
		var repository = new InMemoryAdminIdentityRepository();
		var rbacRepository = new InMemoryAdminRbacAuthorityRepository();
		var passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
		var now = LocalDateTime.of(2026, 6, 27, 0, 0);
		repository.save(breakGlassIdentity(passwordEncoder, false, now));
		rbacRepository.replacePermissionAuthorities("break-glass", Set.of("admin.view", "admin.audit.read"));

		UserDetailsService users = securityConfig.userDetailsService(
			"admin-user", "admin-password", "", "", "", "", false, "", "",
			repository, rbacRepository, passwordEncoder, new MockEnvironment()
		);
		var provider = new AdminOperatorLockoutAuthenticationProvider(
			users, passwordEncoder, repository, 5, java.time.Duration.ofMinutes(15), Clock.systemUTC());

		var authentication = provider.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(
			"break-glass", "break-password"));
		assertThat(authentication.isAuthenticated()).isTrue();
		assertThat(authentication.getAuthorities())
			.extracting(GrantedAuthority::getAuthority)
			.containsExactlyInAnyOrder("ROLE_ADMIN", "admin.view", "admin.audit.read")
			.doesNotContain("admin.security.admin");
		assertThat(repository.findByLoginId("break-glass").orElseThrow().status())
			.isEqualTo(AdminIdentityStatus.CREDENTIAL_ROTATION_REQUIRED);
		assertThat(rbacRepository.findPermissionAuthorities("break-glass"))
			.containsExactlyInAnyOrder("admin.view", "admin.audit.read")
			.doesNotContain("admin.security.admin");
		assertThat(repository.audits()).anySatisfy(audit -> {
			assertThat(audit.authMethod()).isEqualTo(AdminIdentityAuthMethod.BREAK_GLASS);
			assertThat(audit.outcome()).isEqualTo("SUCCESS");
			assertThat(audit.reason()).isEqualTo("운영 장애 대응");
		});
	}

	@Test
	@DisplayName("모든 persisted break-glass identity와 bootstrap ID 충돌은 mutation 전에 실패한다")
	void persistedBreakGlassIdentityCollisionFailsBeforeMutation() {
		for (var scenario : List.of(
			new BootstrapIdentityCollision(" BREAK-GLASS ", "different-admin-password", "", "", false),
			new BootstrapIdentityCollision(" break-glass ", "break-password", "", "", true),
			new BootstrapIdentityCollision("admin-user", "admin-password", " BREAK-GLASS ", "different-operator-password", false),
			new BootstrapIdentityCollision("admin-user", "admin-password", " break-glass ", "break-password", true)
		)) {
			var securityConfig = new SecurityConfig();
			var repository = new InMemoryAdminIdentityRepository();
			var rbacRepository = new InMemoryAdminRbacAuthorityRepository();
			var passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
			var now = LocalDateTime.of(2026, 6, 27, 0, 0);
			AdminIdentity existingIdentity = breakGlassIdentity(
				passwordEncoder, scenario.bootstrapManaged(), now);
			repository.save(existingIdentity);
			rbacRepository.replacePermissionAuthorities("break-glass", Set.of("admin.view", "admin.audit.read"));

			AdminIdentity expectedIdentity = existingIdentity;
			assertThatThrownBy(() -> securityConfig.userDetailsService(
				scenario.adminUsername(),
				scenario.adminPassword(),
				scenario.operatorUsername(),
				scenario.operatorPassword(),
				"",
				"",
				false,
				"",
				"",
				repository,
				rbacRepository,
				passwordEncoder,
				new MockEnvironment()
			))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("break-glass identity");
			assertThat(repository.findByLoginId("break-glass")).contains(expectedIdentity);
			assertThat(repository.findByLoginId("admin-user")).isEmpty();
			assertThat(repository.findByLoginId("operator-user")).isEmpty();
			assertThat(rbacRepository.findPermissionAuthorities("break-glass"))
				.containsExactlyInAnyOrder("admin.view", "admin.audit.read")
				.doesNotContain("admin.security.admin");
			assertThat(rbacRepository.findPermissionAuthorities("admin-user")).isEmpty();
			assertThat(rbacRepository.findPermissionAuthorities("operator-user")).isEmpty();
		}
	}

	private AdminIdentity breakGlassIdentity(
		org.springframework.security.crypto.password.PasswordEncoder passwordEncoder,
		boolean bootstrapManaged,
		LocalDateTime now
	) {
		return new AdminIdentity(
			"break-glass", "break-glass 관리자", null, passwordEncoder.encode("break-password"),
			AdminIdentityAuthMethod.BREAK_GLASS, AdminIdentityRole.ADMIN, AdminIdentityStatus.ACTIVE,
			0, null, now, null, false, "운영 장애 대응", bootstrapManaged, now, now
		);
	}

	private record BootstrapIdentityCollision(
		String adminUsername,
		String adminPassword,
		String operatorUsername,
		String operatorPassword,
		boolean bootstrapManaged
	) {
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

		@Bean
		InMemoryAdminAuditEventRepository adminAuditEventRepository() {
			return new InMemoryAdminAuditEventRepository();
		}
	}

}

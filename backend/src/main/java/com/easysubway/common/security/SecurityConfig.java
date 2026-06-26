package com.easysubway.common.security;

import com.easysubway.admin.authorization.AdminPermission;
import com.easysubway.admin.identity.application.port.out.AdminIdentityRepository;
import com.easysubway.admin.identity.application.service.AdminIdentityUserDetailsService;
import com.easysubway.admin.identity.domain.AdminIdentity;
import com.easysubway.admin.identity.domain.AdminIdentityAuthMethod;
import com.easysubway.admin.identity.domain.AdminIdentityRole;
import com.easysubway.admin.identity.domain.AdminIdentityStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

	@Bean
	@Order(1)
	SecurityFilterChain adminSecurityFilterChain(
		HttpSecurity http,
		AdminOperatorAuditFilter auditFilter,
		@Value("${easysubway.admin.basic-auth.enabled:true}") boolean basicAuthEnabled
	) throws Exception {
		// 관리자 검수 화면에는 상태 변경 form이 있으므로 CSRF 보호를 유지한다.
		HttpSecurity configured = http
			.securityMatcher("/admin/**")
			.authorizeHttpRequests(authorize -> authorize
				.requestMatchers("/admin/login").permitAll()
				.requestMatchers(HttpMethod.POST, "/admin/reports/**")
				.hasAnyAuthority(AdminPermission.REPORT_REVIEW.authority(), "ROLE_ADMIN")
				.requestMatchers(HttpMethod.POST, "/admin/facilities/**", "/admin/stations/**")
				.hasAnyAuthority(AdminPermission.MASTER_EDIT.authority(), "ROLE_ADMIN")
				.requestMatchers(HttpMethod.PUT, "/admin/facilities/**", "/admin/stations/**")
				.hasAnyAuthority(AdminPermission.MASTER_EDIT.authority(), "ROLE_ADMIN")
				.requestMatchers(HttpMethod.PATCH, "/admin/facilities/**", "/admin/stations/**")
				.hasAnyAuthority(AdminPermission.MASTER_EDIT.authority(), "ROLE_ADMIN")
				.requestMatchers(HttpMethod.POST, "/admin/field-verifications/**")
				.hasAnyAuthority(AdminPermission.FIELD_OPERATE.authority(), "ROLE_ADMIN")
				.requestMatchers(HttpMethod.PATCH, "/admin/field-verifications/**")
				.hasAnyAuthority(AdminPermission.FIELD_OPERATE.authority(), "ROLE_ADMIN")
				.requestMatchers(
					HttpMethod.POST,
					"/admin/data-collections/**",
					"/admin/data-sources/**",
					"/admin/notifications/**"
				)
				.hasAnyAuthority(AdminPermission.DATA_OPERATE.authority(), "ROLE_ADMIN")
				.requestMatchers("/admin/system/**", "/admin/usage/**")
				.hasAnyAuthority(AdminPermission.SECURITY_AUDIT.authority(), "ROLE_ADMIN")
				.anyRequest().hasAnyAuthority(AdminPermission.ADMIN_VIEW.authority(), "ROLE_ADMIN")
			)
			.exceptionHandling(exception -> exception
				.defaultAuthenticationEntryPointFor(
					new LoginUrlAuthenticationEntryPoint("/admin/login"),
					request -> {
						String accept = request.getHeader("Accept");
						return accept != null && accept.contains("text/html");
					}
				)
				.defaultAuthenticationEntryPointFor(
					new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
					new AntPathRequestMatcher("/admin/**")
				)
			)
			.formLogin(form -> form
				.loginPage("/admin/login")
				.defaultSuccessUrl("/admin/dashboard/page", true)
				.permitAll()
			)
			.addFilterAfter(auditFilter, BasicAuthenticationFilter.class);
		configureBasicAuth(configured, basicAuthEnabled);
		return configured.build();
	}

	@Bean
	@Order(2)
	SecurityFilterChain operatorSecurityFilterChain(
		HttpSecurity http,
		AdminOperatorAuditFilter auditFilter,
		@Value("${easysubway.admin.basic-auth.enabled:true}") boolean basicAuthEnabled
	) throws Exception {
		// 운영기관 전용 화면은 전역 관리자와 별도 역할로 분리해 이후 기관별 범위 제한을 붙일 수 있게 한다.
		HttpSecurity configured = http
			.securityMatcher("/operator/**")
			.authorizeHttpRequests(authorize -> authorize
				.requestMatchers("/operator/login").permitAll()
				.anyRequest().hasRole("OPERATOR_ADMIN")
			)
			.exceptionHandling(exception -> exception
				.defaultAuthenticationEntryPointFor(
					new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
					new AntPathRequestMatcher("/operator/api/**")
				)
				.defaultAuthenticationEntryPointFor(
					new LoginUrlAuthenticationEntryPoint("/operator/login"),
					new AntPathRequestMatcher("/operator/**")
				)
			)
			.formLogin(form -> form
				.loginPage("/operator/login")
				.defaultSuccessUrl("/operator/accessibility-report/page", true)
				.permitAll()
			)
			.addFilterAfter(auditFilter, BasicAuthenticationFilter.class);
		configureBasicAuth(configured, basicAuthEnabled);
		return configured.build();
	}

	@Bean
	@Order(3)
	SecurityFilterChain reportSecurityFilterChain(HttpSecurity http) throws Exception {
		// 신고 접수와 상태 조회는 신고별 receipt token 흐름을 사용한다.
		return http
			.securityMatcher(
				"/api/v1/report-uploads",
				"/api/v1/report-uploads/*",
				"/api/v1/reports",
				"/api/v1/reports/*",
				"/api/v1/reports/*/confirm"
			)
			.csrf(AbstractHttpConfigurer::disable)
			.authorizeHttpRequests(authorize -> authorize
				.anyRequest().permitAll()
			)
			.httpBasic(Customizer.withDefaults())
			.build();
	}

	@Bean
	@Order(4)
	SecurityFilterChain publicSecurityFilterChain(HttpSecurity http) throws Exception {
		// 신고/관리자/운영 matcher 밖의 새 경로가 실수로 공개되지 않도록 기본 차단한다.
		return http
			.csrf(AbstractHttpConfigurer::disable)
			.authorizeHttpRequests(authorize -> authorize
				.requestMatchers(
					"/api/health",
					"/actuator/health",
					"/actuator/health/**",
					"/actuator/prometheus",
					"/api/v1/realtime/**",
					"/favicon.ico",
					"/css/**",
					"/js/**",
					"/images/**",
					"/webjars/**"
				).permitAll()
				.anyRequest().denyAll()
			)
			.build();
	}

	@Bean
	UserDetailsService userDetailsService(
		@Value("${easysubway.admin.username:}") String adminUsername,
		@Value("${easysubway.admin.password:}") String adminPassword,
		@Value("${easysubway.admin.break-glass.username:}") String breakGlassUsername,
		@Value("${easysubway.admin.break-glass.password:}") String breakGlassPassword,
		@Value("${easysubway.admin.break-glass.reason:}") String breakGlassReason,
		@Value("${easysubway.operator.username:}") String operatorUsername,
		@Value("${easysubway.operator.password:}") String operatorPassword,
		@Value("${easysubway.user.username:}") String userUsername,
		@Value("${easysubway.user.password:}") String userPassword,
		@Value("${easysubway.admin.basic-auth.enabled:true}") boolean basicAuthEnabled,
		@Value("${easysubway.admin.basic-auth.exception-owner:}") String basicAuthExceptionOwner,
		@Value("${easysubway.admin.basic-auth.exception-expires-at:}") String basicAuthExceptionExpiresAt,
		AdminIdentityRepository adminIdentityRepository,
		PasswordEncoder passwordEncoder,
		Environment environment
	) {
		validateProdAdminCredentials(adminUsername, adminPassword, environment);
		validateAdminCredentials(adminUsername, adminPassword);
		validateProdBasicAuthPolicy(
			basicAuthEnabled,
			basicAuthExceptionOwner,
			basicAuthExceptionExpiresAt,
			environment
		);
		validateOperatorCredentials(operatorUsername, operatorPassword);
		validateBreakGlassCredentials(breakGlassUsername, breakGlassPassword, breakGlassReason);
		validateDistinctAdminLoginIds(adminUsername, operatorUsername, breakGlassUsername, userUsername);

		LocalDateTime now = LocalDateTime.now(Clock.systemUTC());
		Set<String> activeBootstrapLoginIds = new LinkedHashSet<>();
		if (!adminUsername.isBlank() && !adminPassword.isBlank()) {
			activeBootstrapLoginIds.add(normalizeLoginId(adminUsername));
			bootstrapIdentity(adminIdentityRepository, passwordEncoder, adminPassword, localIdentity(
				adminUsername,
				"관리자",
				passwordEncoder.encode(adminPassword),
				AdminIdentityRole.ADMIN,
				now
			), now);
		}
		if (!operatorUsername.isBlank() && !operatorPassword.isBlank()) {
			activeBootstrapLoginIds.add(normalizeLoginId(operatorUsername));
			bootstrapIdentity(adminIdentityRepository, passwordEncoder, operatorPassword, localIdentity(
				operatorUsername,
				"운영기관 관리자",
				passwordEncoder.encode(operatorPassword),
				AdminIdentityRole.OPERATOR_ADMIN,
				now
			), now);
		}
		if (!breakGlassUsername.isBlank() && !breakGlassPassword.isBlank()) {
			activeBootstrapLoginIds.add(normalizeLoginId(breakGlassUsername));
			bootstrapIdentity(adminIdentityRepository, passwordEncoder, breakGlassPassword, breakGlassIdentity(
				breakGlassUsername,
				passwordEncoder.encode(breakGlassPassword),
				breakGlassReason,
				now
			), now);
		}
		adminIdentityRepository.disableStaleBootstrapIdentities(activeBootstrapLoginIds, now);
		var users = new ConcurrentUserDetailsManager();
		if (!userUsername.isBlank() && !userPassword.isBlank()) {
			users.createUser(User.withUsername(userUsername)
				.password(passwordEncoder.encode(userPassword))
				.roles("USER")
				.build());
		}
		return new AdminIdentityUserDetailsService(adminIdentityRepository, users, Clock.systemUTC());
	}

	@Bean
	AuthenticationProvider adminOperatorLockoutAuthenticationProvider(
		UserDetailsService userDetailsService,
		PasswordEncoder passwordEncoder,
		AdminIdentityRepository adminIdentityRepository,
		@Value("${easysubway.admin.lockout.max-failures:5}") int maxFailures,
		@Value("${easysubway.admin.lockout.duration:PT15M}") String lockoutDuration
	) {
		return new AdminOperatorLockoutAuthenticationProvider(
			userDetailsService,
			passwordEncoder,
			adminIdentityRepository,
			maxFailures,
			Duration.parse(lockoutDuration),
			Clock.systemUTC()
		);
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder();
	}

	@Bean
	AdminOperatorAuditFilter adminOperatorAuditFilter() {
		return new AdminOperatorAuditFilter();
	}

	private void configureBasicAuth(HttpSecurity http, boolean basicAuthEnabled) throws Exception {
		if (basicAuthEnabled) {
			http.httpBasic(Customizer.withDefaults());
			return;
		}
		http.httpBasic(AbstractHttpConfigurer::disable);
	}

	private void bootstrapIdentity(
		AdminIdentityRepository adminIdentityRepository,
		PasswordEncoder passwordEncoder,
		String rawPassword,
		AdminIdentity bootstrap,
		LocalDateTime now
	) {
		var current = adminIdentityRepository.findByLoginId(bootstrap.loginId());
		if (current.isEmpty()) {
			adminIdentityRepository.upsertBootstrap(bootstrap);
			return;
		}
		AdminIdentity existing = current.orElseThrow();
		if (breakGlassRotationRequiredWithSamePassword(existing, rawPassword, passwordEncoder)) {
			return;
		}
		if (sameBootstrapSecret(existing, bootstrap, rawPassword, passwordEncoder)) {
			return;
		}
		adminIdentityRepository.save(existing.refreshBootstrap(bootstrap, now));
	}

	private boolean sameBootstrapSecret(
		AdminIdentity existing,
		AdminIdentity bootstrap,
		String rawPassword,
		PasswordEncoder passwordEncoder
	) {
		return existing.authMethod() == bootstrap.authMethod()
			&& existing.role() == bootstrap.role()
			&& existing.status() == bootstrap.status()
			&& Objects.equals(existing.displayName(), bootstrap.displayName())
			&& Objects.equals(existing.email(), bootstrap.email())
			&& Objects.equals(existing.breakGlassReason(), bootstrap.breakGlassReason())
			&& existing.bootstrapManaged() == bootstrap.bootstrapManaged()
			&& passwordEncoder.matches(rawPassword, existing.passwordHash());
	}

	private boolean breakGlassRotationRequiredWithSamePassword(
		AdminIdentity existing,
		String rawPassword,
		PasswordEncoder passwordEncoder
	) {
		return existing.authMethod() == AdminIdentityAuthMethod.BREAK_GLASS
			&& existing.credentialRotationRequired()
			&& passwordEncoder.matches(rawPassword, existing.passwordHash());
	}

	private AdminIdentity localIdentity(
		String loginId,
		String displayName,
		String passwordHash,
		AdminIdentityRole role,
		LocalDateTime now
	) {
		return new AdminIdentity(
			loginId,
			displayName,
			null,
			passwordHash,
			AdminIdentityAuthMethod.LOCAL,
			role,
			AdminIdentityStatus.ACTIVE,
			0,
			null,
			now,
			null,
			false,
			null,
			true,
			now,
			now
		);
	}

	private AdminIdentity breakGlassIdentity(
		String loginId,
		String passwordHash,
		String reason,
		LocalDateTime now
	) {
		return new AdminIdentity(
			loginId,
			"break-glass 관리자",
			null,
			passwordHash,
			AdminIdentityAuthMethod.BREAK_GLASS,
			AdminIdentityRole.ADMIN,
			AdminIdentityStatus.ACTIVE,
			0,
			null,
			now,
			null,
			false,
			reason,
			true,
			now,
			now
		);
	}

	private void validateProdAdminCredentials(String adminUsername, String adminPassword, Environment environment) {
		if (Arrays.asList(environment.getActiveProfiles()).contains("prod")
			&& (adminUsername.isBlank() || adminPassword.isBlank())) {
			throw new IllegalStateException("운영 관리자 계정 설정이 필요합니다.");
		}
	}

	private void validateAdminCredentials(String adminUsername, String adminPassword) {
		if (adminUsername.isBlank() != adminPassword.isBlank()) {
			throw new IllegalStateException("관리자 계정 설정은 아이디와 비밀번호를 함께 입력해야 합니다.");
		}
	}

	private void validateOperatorCredentials(String operatorUsername, String operatorPassword) {
		if (operatorUsername.isBlank() != operatorPassword.isBlank()) {
			throw new IllegalStateException("운영기관 관리자 계정 설정은 아이디와 비밀번호를 함께 입력해야 합니다.");
		}
	}

	private void validateBreakGlassCredentials(String username, String password, String reason) {
		boolean anyConfigured = !username.isBlank() || !password.isBlank() || !reason.isBlank();
		boolean partiallyConfigured = username.isBlank() || password.isBlank() || reason.isBlank();
		if (anyConfigured && partiallyConfigured) {
			throw new IllegalStateException("break-glass 계정 설정은 아이디, 비밀번호, 사유를 함께 입력해야 합니다.");
		}
	}

	private void validateDistinctAdminLoginIds(
		String adminUsername,
		String operatorUsername,
		String breakGlassUsername,
		String userUsername
	) {
		String admin = normalizeLoginId(adminUsername);
		String operator = normalizeLoginId(operatorUsername);
		String breakGlass = normalizeLoginId(breakGlassUsername);
		String user = normalizeLoginId(userUsername);
		if ((!admin.isBlank() && admin.equals(operator))
			|| (!admin.isBlank() && admin.equals(breakGlass))
			|| (!admin.isBlank() && admin.equals(user))
			|| (!operator.isBlank() && operator.equals(breakGlass))
			|| (!operator.isBlank() && operator.equals(user))
			|| (!breakGlass.isBlank() && breakGlass.equals(user))) {
			throw new IllegalStateException("관리자, 운영기관, break-glass, 일반 사용자 계정 ID는 서로 달라야 합니다.");
		}
	}

	private String normalizeLoginId(String loginId) {
		return loginId.trim().toLowerCase(Locale.ROOT);
	}

	private void validateProdBasicAuthPolicy(
		boolean basicAuthEnabled,
		String exceptionOwner,
		String exceptionExpiresAt,
		Environment environment
	) {
		if (!Arrays.asList(environment.getActiveProfiles()).contains("prod") || !basicAuthEnabled) {
			return;
		}
		if (exceptionOwner.isBlank() || exceptionExpiresAt.isBlank()) {
			throw new IllegalStateException("운영 Basic auth 예외는 owner와 만료일이 필요합니다.");
		}
		LocalDate expiresAt = LocalDate.parse(exceptionExpiresAt);
		if (expiresAt.isBefore(LocalDate.now(ZoneOffset.UTC))) {
			throw new IllegalStateException("운영 Basic auth 예외 만료일이 지났습니다.");
		}
	}
}

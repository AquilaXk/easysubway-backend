package com.easysubway.common.security;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

	@Bean
	@Order(1)
	SecurityFilterChain adminSecurityFilterChain(HttpSecurity http, AdminOperatorAuditFilter auditFilter) throws Exception {
		// 관리자 검수 화면에는 상태 변경 form이 있으므로 CSRF 보호를 유지한다.
		return http
			.securityMatcher("/admin/**")
			.authorizeHttpRequests(authorize -> authorize
				.requestMatchers("/admin/login").permitAll()
				.anyRequest().hasRole("ADMIN")
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
			.httpBasic(Customizer.withDefaults())
			.addFilterAfter(auditFilter, BasicAuthenticationFilter.class)
			.build();
	}

	@Bean
	@Order(2)
	SecurityFilterChain operatorSecurityFilterChain(HttpSecurity http, AdminOperatorAuditFilter auditFilter) throws Exception {
		// 운영기관 전용 화면은 전역 관리자와 별도 역할로 분리해 이후 기관별 범위 제한을 붙일 수 있게 한다.
		return http
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
			.httpBasic(Customizer.withDefaults())
			.addFilterAfter(auditFilter, BasicAuthenticationFilter.class)
			.build();
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
	ConcurrentUserDetailsManager userDetailsService(
		@Value("${easysubway.admin.username:}") String adminUsername,
		@Value("${easysubway.admin.password:}") String adminPassword,
		@Value("${easysubway.operator.username:}") String operatorUsername,
		@Value("${easysubway.operator.password:}") String operatorPassword,
		@Value("${easysubway.user.username:}") String userUsername,
		@Value("${easysubway.user.password:}") String userPassword,
		PasswordEncoder passwordEncoder,
		Environment environment
	) {
		validateProdAdminCredentials(adminUsername, adminPassword, environment);
		validateOperatorCredentials(operatorUsername, operatorPassword);
		var users = new ConcurrentUserDetailsManager();
		if (!adminUsername.isBlank() && !adminPassword.isBlank()) {
			users.createUser(User.withUsername(adminUsername)
				.password(passwordEncoder.encode(adminPassword))
				.roles("ADMIN")
				.build());
		}
		if (!operatorUsername.isBlank() && !operatorPassword.isBlank()) {
			users.createUser(User.withUsername(operatorUsername)
				.password(passwordEncoder.encode(operatorPassword))
				.roles("OPERATOR_ADMIN")
				.build());
		}
		if (!userUsername.isBlank() && !userPassword.isBlank()) {
			users.createUser(User.withUsername(userUsername)
				.password(passwordEncoder.encode(userPassword))
				.roles("USER")
				.build());
		}
		return users;
	}

	@Bean
	AuthenticationProvider adminOperatorLockoutAuthenticationProvider(
		ConcurrentUserDetailsManager userDetailsService,
		PasswordEncoder passwordEncoder,
		@Value("${easysubway.admin.username:}") String adminUsername,
		@Value("${easysubway.operator.username:}") String operatorUsername,
		@Value("${easysubway.admin.lockout.max-failures:5}") int maxFailures,
		@Value("${easysubway.admin.lockout.duration:PT15M}") String lockoutDuration
	) {
		return new AdminOperatorLockoutAuthenticationProvider(
			userDetailsService,
			passwordEncoder,
			List.of(adminUsername, operatorUsername),
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

	private void validateProdAdminCredentials(String adminUsername, String adminPassword, Environment environment) {
		if (Arrays.asList(environment.getActiveProfiles()).contains("prod")
			&& (adminUsername.isBlank() || adminPassword.isBlank())) {
			throw new IllegalStateException("운영 관리자 계정 설정이 필요합니다.");
		}
	}

	private void validateOperatorCredentials(String operatorUsername, String operatorPassword) {
		if (operatorUsername.isBlank() != operatorPassword.isBlank()) {
			throw new IllegalStateException("운영기관 관리자 계정 설정은 아이디와 비밀번호를 함께 입력해야 합니다.");
		}
	}
}

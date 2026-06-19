package com.easysubway.common.security;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

	@Bean
	@Order(1)
	SecurityFilterChain adminSecurityFilterChain(HttpSecurity http) throws Exception {
		// 관리자 검수 화면에는 상태 변경 form이 있으므로 CSRF 보호를 유지한다.
		return http
			.securityMatcher("/admin/**")
			.authorizeHttpRequests(authorize -> authorize
				.anyRequest().hasRole("ADMIN")
			)
			.httpBasic(Customizer.withDefaults())
			.build();
	}

	@Bean
	@Order(2)
	SecurityFilterChain operatorSecurityFilterChain(HttpSecurity http) throws Exception {
		// 운영기관 전용 화면은 전역 관리자와 별도 역할로 분리해 이후 기관별 범위 제한을 붙일 수 있게 한다.
		return http
			.securityMatcher("/operator/**")
			.authorizeHttpRequests(authorize -> authorize
				.anyRequest().hasRole("OPERATOR_ADMIN")
			)
			.httpBasic(Customizer.withDefaults())
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
	SecurityFilterChain userSecurityFilterChain(HttpSecurity http) throws Exception {
		// 사용자별 관리 API는 임시 운영 검증용 Basic 인증만 허용하고 앱 기본 경로에서는 호출하지 않는다.
		return http
			.securityMatcher(
				"/api/v1/me",
				"/api/v1/me/reports",
				"/api/v1/me/favorites/**",
				"/api/v1/routes/*/feedback",
				"/api/v1/devices",
				"/api/v1/me/notification-settings"
			)
			.csrf(AbstractHttpConfigurer::disable)
			.authorizeHttpRequests(authorize -> authorize
				.anyRequest().authenticated()
			)
			.httpBasic(Customizer.withDefaults())
			.build();
	}

	@Bean
	@Order(5)
	SecurityFilterChain publicSecurityFilterChain(HttpSecurity http) throws Exception {
		// 역 검색과 경로 검색은 로그인 전 이동 계획에 필요한 공개 조회 기능이다.
		return http
			.csrf(AbstractHttpConfigurer::disable)
			.authorizeHttpRequests(authorize -> authorize
				.anyRequest().permitAll()
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
	PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder();
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

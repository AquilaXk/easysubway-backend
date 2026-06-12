package com.easysubway.common.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

	@Bean
	@Order(1)
	SecurityFilterChain adminSecurityFilterChain(HttpSecurity http) throws Exception {
		// 관리자 검수 API는 다른 공개 API보다 먼저 매칭해야 잘못된 인증 우회를 막을 수 있다.
		return http
			.securityMatcher("/admin/**")
			.csrf(AbstractHttpConfigurer::disable)
			.authorizeHttpRequests(authorize -> authorize
				.anyRequest().hasRole("ADMIN")
			)
			.httpBasic(Customizer.withDefaults())
			.build();
	}

	@Bean
	@Order(2)
	SecurityFilterChain userSecurityFilterChain(HttpSecurity http) throws Exception {
		// 사용자별 데이터는 URL이나 본문 userId가 아니라 인증 계정을 기준으로 다룬다.
		return http
			.securityMatcher("/api/v1/me/favorites/**")
			.csrf(AbstractHttpConfigurer::disable)
			.authorizeHttpRequests(authorize -> authorize
				.anyRequest().authenticated()
			)
			.httpBasic(Customizer.withDefaults())
			.build();
	}

	@Bean
	@Order(3)
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
	UserDetailsService userDetailsService(
		@Value("${easysubway.admin.username:}") String adminUsername,
		@Value("${easysubway.admin.password:}") String adminPassword,
		@Value("${easysubway.user.username:}") String userUsername,
		@Value("${easysubway.user.password:}") String userPassword,
		PasswordEncoder passwordEncoder
	) {
		var users = new InMemoryUserDetailsManager();
		if (!adminUsername.isBlank() && !adminPassword.isBlank()) {
			users.createUser(User.withUsername(adminUsername)
				.password(passwordEncoder.encode(adminPassword))
				.roles("ADMIN")
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
}

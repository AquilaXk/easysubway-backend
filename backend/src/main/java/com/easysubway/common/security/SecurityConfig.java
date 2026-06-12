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
	SecurityFilterChain publicSecurityFilterChain(HttpSecurity http) throws Exception {
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
		PasswordEncoder passwordEncoder
	) {
		var users = new InMemoryUserDetailsManager();
		if (!adminUsername.isBlank() && !adminPassword.isBlank()) {
			users.createUser(User.withUsername(adminUsername)
				.password(passwordEncoder.encode(adminPassword))
				.roles("ADMIN")
				.build());
		}
		return users;
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder();
	}
}

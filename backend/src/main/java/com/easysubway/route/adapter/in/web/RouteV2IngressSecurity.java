package com.easysubway.route.adapter.in.web;

import com.easysubway.route.application.port.out.RouteV2AccessStore;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

public final class RouteV2IngressSecurity {

	private RouteV2IngressSecurity() {
	}

	public static SecurityFilterChain configure(
		HttpSecurity http,
		RouteV2AccessStore store,
		RouteV2Metrics metrics,
		String originSecret
	) throws Exception {
		var sessionFilter = new RouteV2SessionFilter(store, metrics);
		var originFilter = new RouteV2OriginGateFilter(originSecret, metrics);
		return http
			.securityMatcher(
				"/api/v2/routes/session",
				"/api/v2/routes/search"
			)
			.csrf(csrf -> csrf.disable())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(authorize -> authorize
				.requestMatchers(
					HttpMethod.POST,
					"/api/v2/routes/session",
					"/api/v2/routes/search"
				).permitAll()
				.anyRequest().denyAll()
			)
			.addFilterBefore(sessionFilter, UsernamePasswordAuthenticationFilter.class)
			.addFilterBefore(originFilter, RouteV2SessionFilter.class)
			.build();
	}
}

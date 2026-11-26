package com.easysubway.journey.adapter.in.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public final class JourneyReadinessServiceTokenFilter extends OncePerRequestFilter {

	public static final String READINESS_ROLE = "ROLE_JOURNEY_READINESS";
	public static final String CONTROL_ROLE = "ROLE_JOURNEY_CONTROL";
	public static final String ROLE = READINESS_ROLE;

	private final byte[] expectedReadinessToken;
	private final byte[] expectedControlToken;

	public JourneyReadinessServiceTokenFilter(String expectedReadinessToken, String expectedControlToken) {
		this.expectedReadinessToken = expectedReadinessToken.getBytes(StandardCharsets.UTF_8);
		this.expectedControlToken = expectedControlToken.getBytes(StandardCharsets.UTF_8);
	}

	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain) throws ServletException, IOException {
		String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (authorization != null
			&& authorization.length() > 7
			&& authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
			byte[] presented = authorization.substring(7).getBytes(StandardCharsets.UTF_8);
			boolean controlMatched = MessageDigest.isEqual(presented, expectedControlToken);
			boolean readinessMatched = MessageDigest.isEqual(presented, expectedReadinessToken);
			if (controlMatched) {
				var authentication = new UsernamePasswordAuthenticationToken(
					"journey-control",
					null,
					List.of(new SimpleGrantedAuthority(CONTROL_ROLE)));
				SecurityContextHolder.getContext().setAuthentication(authentication);
			} else if (readinessMatched) {
				var authentication = new UsernamePasswordAuthenticationToken(
					"journey-readiness",
					null,
					List.of(new SimpleGrantedAuthority(READINESS_ROLE)));
				SecurityContextHolder.getContext().setAuthentication(authentication);
			}
		}
		filterChain.doFilter(request, response);
	}
}

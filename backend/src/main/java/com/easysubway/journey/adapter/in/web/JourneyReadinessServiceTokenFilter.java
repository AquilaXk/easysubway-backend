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

	public static final String ROLE = "ROLE_JOURNEY_READINESS";

	private final byte[] expectedToken;

	public JourneyReadinessServiceTokenFilter(String expectedToken) {
		this.expectedToken = expectedToken.getBytes(StandardCharsets.UTF_8);
	}

	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain) throws ServletException, IOException {
		String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (authorization != null
			&& authorization.length() > 7
			&& authorization.regionMatches(true, 0, "Bearer ", 0, 7)
			&& MessageDigest.isEqual(
				authorization.substring(7).getBytes(StandardCharsets.UTF_8), expectedToken)) {
			var authentication = new UsernamePasswordAuthenticationToken(
				"journey-readiness",
				null,
				List.of(new SimpleGrantedAuthority(ROLE)));
			SecurityContextHolder.getContext().setAuthentication(authentication);
		}
		filterChain.doFilter(request, response);
	}
}

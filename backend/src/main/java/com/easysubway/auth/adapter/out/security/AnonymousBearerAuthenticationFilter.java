package com.easysubway.auth.adapter.out.security;

import com.easysubway.auth.application.port.out.AnonymousAuthTokenPort;
import com.easysubway.auth.application.service.AnonymousAuthTokenHasher;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class AnonymousBearerAuthenticationFilter extends OncePerRequestFilter {

	private static final String BEARER_PREFIX = "Bearer ";

	private final AnonymousAuthTokenPort anonymousAuthTokenPort;

	public AnonymousBearerAuthenticationFilter(AnonymousAuthTokenPort anonymousAuthTokenPort) {
		this.anonymousAuthTokenPort = anonymousAuthTokenPort;
	}

	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain
	) throws ServletException, IOException {
		String authorization = request.getHeader("Authorization");
		if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
			filterChain.doFilter(request, response);
			return;
		}

		String token = authorization.substring(BEARER_PREFIX.length()).trim();
		var userId = anonymousAuthTokenPort.findUserIdByAccessTokenHash(AnonymousAuthTokenHasher.sha256(token));
		if (userId.isPresent()) {
			var authentication = new UsernamePasswordAuthenticationToken(
				new AnonymousBearerPrincipal(userId.get()),
				null,
				List.of(new SimpleGrantedAuthority("ROLE_USER"))
			);
			SecurityContextHolder.getContext().setAuthentication(authentication);
		} else {
			anonymousAuthTokenPort.saveAuditEvent("ACCESS_TOKEN_INVALID", null, LocalDateTime.now());
		}
		filterChain.doFilter(request, response);
	}
}

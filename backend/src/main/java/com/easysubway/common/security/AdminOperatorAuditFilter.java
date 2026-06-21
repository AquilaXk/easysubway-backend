package com.easysubway.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

class AdminOperatorAuditFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(AdminOperatorAuditFilter.class);
	private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String path = request.getRequestURI();
		return !MUTATING_METHODS.contains(request.getMethod()) || !(path.startsWith("/admin/") || path.startsWith("/operator/"));
	}

	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain
	)
		throws ServletException, IOException {
		Exception failure = null;
		try {
			filterChain.doFilter(request, response);
		} catch (ServletException | IOException | RuntimeException exception) {
			failure = exception;
			throw exception;
		} finally {
			Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
			if (isAuthenticated(authentication)) {
				writeAuditLog(request, response, authentication, failure);
			}
		}
	}

	private void writeAuditLog(
		HttpServletRequest request,
		HttpServletResponse response,
		Authentication authentication,
		Exception failure
	) {
		log.info(
			"admin_operator_state_change_audit method={} path={} principal={} roles={} status={}",
			request.getMethod(),
			normalizedPath(request),
			authentication.getName(),
			roles(authentication),
			status(response, failure)
		);
	}

	private static int status(HttpServletResponse response, Exception failure) {
		if (failure == null || response.getStatus() >= HttpServletResponse.SC_BAD_REQUEST) {
			return response.getStatus();
		}
		return HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
	}

	private static boolean isAuthenticated(Authentication authentication) {
		return authentication != null
			&& authentication.isAuthenticated()
			&& !(authentication instanceof AnonymousAuthenticationToken);
	}

	private static String normalizedPath(HttpServletRequest request) {
		Object bestPattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
		if (bestPattern instanceof String pattern && !pattern.isBlank() && !"/**".equals(pattern)) {
			return pattern;
		}
		return request.getRequestURI();
	}

	private static String roles(Authentication authentication) {
		return authentication.getAuthorities()
			.stream()
			.map(authority -> authority.getAuthority())
			.sorted(Comparator.naturalOrder())
			.collect(Collectors.joining(","));
	}
}

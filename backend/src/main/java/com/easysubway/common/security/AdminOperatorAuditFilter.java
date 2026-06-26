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
			"admin_operator_state_change_audit method={} path={} principal={} roles={} tenant={} status={} outcome={} correlation_id={}",
			request.getMethod(),
			normalizedPath(request),
			authentication.getName(),
			roles(authentication),
			tenant(authentication),
			status(response, failure),
			outcome(response, failure),
			correlationId(request)
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

	private static String tenant(Authentication authentication) {
		String roles = roles(authentication);
		if (roles.contains("ROLE_OPERATOR_ADMIN")) {
			return "operator-global";
		}
		return "admin-global";
	}

	private static String outcome(HttpServletResponse response, Exception failure) {
		return failure == null && response.getStatus() < HttpServletResponse.SC_BAD_REQUEST ? "SUCCESS" : "FAILURE";
	}

	private static String correlationId(HttpServletRequest request) {
		String value = request.getHeader("X-Correlation-Id");
		if (value == null || value.isBlank()) {
			return "missing";
		}
		String trimmed = value.trim();
		if (!trimmed.matches("[A-Za-z0-9._-]{1,64}")) {
			return "invalid";
		}
		return trimmed;
	}
}

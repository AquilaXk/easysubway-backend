package com.easysubway.route.adapter.in.web;

import com.easysubway.common.error.ErrorCode;
import com.easysubway.route.application.port.out.RouteV2AccessStore;
import com.easysubway.route.application.port.out.RouteV2AccessStore.SessionUse;
import com.easysubway.route.application.service.RouteV2SessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

final class RouteV2SessionFilter extends OncePerRequestFilter {

	private static final String BEARER_PREFIX = "Bearer ";

	private final RouteV2AccessStore store;
	private final Clock clock;
	private final RouteV2Metrics metrics;

	RouteV2SessionFilter(RouteV2AccessStore store) {
		this(store, RouteV2Metrics.noop());
	}

	RouteV2SessionFilter(RouteV2AccessStore store, RouteV2Metrics metrics) {
		this(store, Clock.systemUTC(), metrics);
	}

	RouteV2SessionFilter(RouteV2AccessStore store, Clock clock) {
		this(store, clock, RouteV2Metrics.noop());
	}

	RouteV2SessionFilter(RouteV2AccessStore store, Clock clock, RouteV2Metrics metrics) {
		this.store = store;
		this.clock = clock;
		this.metrics = metrics;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !"/api/v2/routes/search".equals(request.getRequestURI());
	}

	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain
	) throws ServletException, IOException {
		String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
			unauthorized(response);
			return;
		}
		String token = authorization.substring(BEARER_PREFIX.length());
		if (!token.matches("^[A-Za-z0-9_-]{43}$")) {
			unauthorized(response);
			return;
		}

		Instant now = clock.instant();
		SessionUse session = store.consumeSession(RouteV2SessionService.tokenHash(token), now);
		switch (session.status()) {
			case MISSING, EXPIRED -> unauthorized(response);
			case LIMITED -> rateLimited(response, now, session.expiresAt());
			case VALID -> {
				if (!"route:v2:itx".equals(session.scope())) {
					metrics.recordResponse(422, ErrorCode.ROUTE_SCOPE_INVALID.code());
					RouteV2ErrorWriter.write(
						response,
						422,
						ErrorCode.ROUTE_SCOPE_INVALID.code(),
						"지원하지 않는 경로예요"
					);
					return;
				}
				filterChain.doFilter(request, response);
			}
		}
	}

	private void unauthorized(HttpServletResponse response) throws IOException {
		metrics.recordResponse(401, ErrorCode.ROUTE_SESSION_REQUIRED.code());
		RouteV2ErrorWriter.write(response, 401, ErrorCode.ROUTE_SESSION_REQUIRED.code(), "다시 시도");
	}

	private void rateLimited(HttpServletResponse response, Instant now, Instant expiresAt) throws IOException {
		long retryAfter = expiresAt == null ? 1 : Math.max(1, Duration.between(now, expiresAt).toSeconds());
		response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
		metrics.recordResponse(429, ErrorCode.ROUTE_RATE_LIMITED.code());
		RouteV2ErrorWriter.write(response, 429, ErrorCode.ROUTE_RATE_LIMITED.code(), "잠시 후 다시 시도");
	}
}

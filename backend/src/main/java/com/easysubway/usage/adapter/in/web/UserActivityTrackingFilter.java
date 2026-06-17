package com.easysubway.usage.adapter.in.web;

import com.easysubway.usage.application.port.out.RecordUserActivityPort;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.Principal;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
class UserActivityTrackingFilter extends OncePerRequestFilter {

	private static final String API_PREFIX = "/api/v1/";
	private static final String ANONYMOUS_AUTH_PATH = "/api/v1/auth/anonymous";

	private final RecordUserActivityPort recordUserActivityPort;
	private final Clock clock;
	private final AuthenticationTrustResolver authenticationTrustResolver;

	@Autowired
	UserActivityTrackingFilter(RecordUserActivityPort recordUserActivityPort, ObjectProvider<Clock> clockProvider) {
		this(recordUserActivityPort, clockProvider.getIfAvailable(Clock::systemDefaultZone));
	}

	UserActivityTrackingFilter(RecordUserActivityPort recordUserActivityPort, Clock clock) {
		this.recordUserActivityPort = recordUserActivityPort;
		this.clock = clock;
		this.authenticationTrustResolver = new AuthenticationTrustResolverImpl();
	}

	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain
	) throws ServletException, IOException {
		filterChain.doFilter(request, response);
		if (shouldRecord(request, response)) {
			recordUserActivityPort.recordUserActivity(
				request.getUserPrincipal().getName(),
				LocalDateTime.now(clock)
			);
		}
	}

	private boolean shouldRecord(HttpServletRequest request, HttpServletResponse response) {
		Principal principal = request.getUserPrincipal();
		String path = request.getRequestURI();
		return principal != null
			&& isAuthenticatedUser(principal)
			&& response.getStatus() < 400
			&& path.startsWith(API_PREFIX)
			&& !path.equals(ANONYMOUS_AUTH_PATH);
	}

	private boolean isAuthenticatedUser(Principal principal) {
		return !(principal instanceof Authentication authentication
			&& authenticationTrustResolver.isAnonymous(authentication));
	}
}

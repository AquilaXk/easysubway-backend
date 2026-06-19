package com.easysubway.usage.adapter.in.web;

import com.easysubway.usage.application.port.out.RecordApiTrafficPort;
import com.easysubway.usage.application.port.out.RecordUserActivityPort;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.function.LongSupplier;
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

	private final RecordUserActivityPort recordUserActivityPort;
	private final RecordApiTrafficPort recordApiTrafficPort;
	private final Clock clock;
	private final LongSupplier nanoTimeSupplier;
	private final AuthenticationTrustResolver authenticationTrustResolver;

	@Autowired
	UserActivityTrackingFilter(
		RecordUserActivityPort recordUserActivityPort,
		RecordApiTrafficPort recordApiTrafficPort,
		ObjectProvider<Clock> clockProvider
	) {
		this(
			recordUserActivityPort,
			recordApiTrafficPort,
			clockProvider.getIfAvailable(Clock::systemDefaultZone),
			System::nanoTime
		);
	}

	UserActivityTrackingFilter(
		RecordUserActivityPort recordUserActivityPort,
		RecordApiTrafficPort recordApiTrafficPort,
		Clock clock
	) {
		this(recordUserActivityPort, recordApiTrafficPort, clock, System::nanoTime);
	}

	UserActivityTrackingFilter(
		RecordUserActivityPort recordUserActivityPort,
		RecordApiTrafficPort recordApiTrafficPort,
		Clock clock,
		LongSupplier nanoTimeSupplier
	) {
		this.recordUserActivityPort = recordUserActivityPort;
		this.recordApiTrafficPort = recordApiTrafficPort;
		this.clock = clock;
		this.nanoTimeSupplier = nanoTimeSupplier;
		this.authenticationTrustResolver = new AuthenticationTrustResolverImpl();
	}

	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain
	) throws ServletException, IOException {
		long startedAtNanos = nanoTimeSupplier.getAsLong();
		LocalDateTime requestedAt = LocalDateTime.now(clock);
		filterChain.doFilter(request, response);
		long durationMillis = Duration.ofNanos(Math.max(0, nanoTimeSupplier.getAsLong() - startedAtNanos)).toMillis();
		if (shouldRecordApiTraffic(request)) {
			recordApiTrafficPort.recordApiTraffic(response.getStatus(), durationMillis, LocalDateTime.now(clock));
		}
		if (shouldRecord(request, response)) {
			recordUserActivityPort.recordUserActivity(
				request.getUserPrincipal().getName(),
				requestedAt
			);
		}
	}

	private boolean shouldRecordApiTraffic(HttpServletRequest request) {
		String path = request.getRequestURI();
		return path.startsWith(API_PREFIX);
	}

	private boolean shouldRecord(HttpServletRequest request, HttpServletResponse response) {
		Principal principal = request.getUserPrincipal();
		String path = request.getRequestURI();
		return principal != null
			&& isAuthenticatedUser(principal)
			&& response.getStatus() < 400
			&& path.startsWith(API_PREFIX);
	}

	private boolean isAuthenticatedUser(Principal principal) {
		return !(principal instanceof Authentication authentication
			&& authenticationTrustResolver.isAnonymous(authentication));
	}
}

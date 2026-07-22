package com.easysubway.route.adapter.in.web;

import com.easysubway.common.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.filter.OncePerRequestFilter;

final class RouteV2OriginGateFilter extends OncePerRequestFilter {

	static final String ORIGIN_HEADER = "X-EasySubway-Origin-Verify";

	private final byte[] expectedSecret;
	private final RouteV2Metrics metrics;

	RouteV2OriginGateFilter(String expectedSecret) {
		this(expectedSecret, RouteV2Metrics.noop());
	}

	RouteV2OriginGateFilter(String expectedSecret, RouteV2Metrics metrics) {
		this.expectedSecret = expectedSecret.getBytes(StandardCharsets.UTF_8);
		this.metrics = metrics;
	}

	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain
	) throws ServletException, IOException {
		String supplied = request.getHeader(ORIGIN_HEADER);
		if (expectedSecret.length == 0
			|| supplied == null
			|| !MessageDigest.isEqual(expectedSecret, supplied.getBytes(StandardCharsets.UTF_8))) {
			metrics.recordResponse(403, ErrorCode.ROUTE_ORIGIN_FORBIDDEN.code());
			RouteV2ErrorWriter.write(response, 403, ErrorCode.ROUTE_ORIGIN_FORBIDDEN.code(), "Forbidden");
			return;
		}
		filterChain.doFilter(request, response);
	}
}

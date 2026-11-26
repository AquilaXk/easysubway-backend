package com.easysubway.common.security;

import com.easysubway.common.web.TrustedClientIpResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

final class AdminLoginRateLimitFilter extends OncePerRequestFilter {

	private static final String ADMIN_LOGIN_PATH = "/admin/login";
	private static final String OPERATOR_LOGIN_PATH = "/operator/login";
	private static final long WINDOW_MILLIS = 60_000L;
	private static final int MAX_TRACKED_KEYS = 16_384;
	private static final byte[] TOO_MANY_REQUESTS_BODY =
		"{\"error\":\"TOO_MANY_REQUESTS\",\"retryable\":true}".getBytes(StandardCharsets.UTF_8);

	private final TrustedClientIpResolver clientIpResolver;
	private final int maxRequestsPerMinute;
	private final Clock clock;
	private final Map<String, WindowBucket> loginBuckets = boundedMap();
	private final Map<String, WindowBucket> basicFailureBuckets = boundedMap();

	AdminLoginRateLimitFilter(
		TrustedClientIpResolver clientIpResolver,
		int maxRequestsPerMinute,
		Clock clock
	) {
		this.clientIpResolver = clientIpResolver == null ? TrustedClientIpResolver.directOnly() : clientIpResolver;
		if (maxRequestsPerMinute < 1) {
			throw new IllegalArgumentException("maxRequestsPerMinute must be positive");
		}
		this.maxRequestsPerMinute = maxRequestsPerMinute;
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	private static Map<String, WindowBucket> boundedMap() {
		return Collections.synchronizedMap(
			new LinkedHashMap<>(256, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, WindowBucket> eldest) {
					return size() > MAX_TRACKED_KEYS;
				}
			}
		);
	}

	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain
	) throws ServletException, IOException {
		boolean formLoginPost = isFormLoginPost(request);
		boolean basicAuthAttempt = hasBasicAuthHeader(request);
		if (!formLoginPost && !basicAuthAttempt) {
			filterChain.doFilter(request, response);
			return;
		}

		String clientIp = clientIpResolver.resolve(request);
		boolean unknownClient = TrustedClientIpResolver.UNKNOWN.equals(clientIp);
		String key = unknownClient ? "unknown" : "ip:" + clientIp;
		int effectiveLimit = unknownClient ? Math.max(1, maxRequestsPerMinute / 2) : maxRequestsPerMinute;
		long nowMillis = clock.millis();

		if (formLoginPost) {
			long retryAfterSeconds = tryConsume(loginBuckets, key, effectiveLimit, nowMillis);
			if (retryAfterSeconds > 0L) {
				rejectTooManyRequests(request, response, retryAfterSeconds);
				return;
			}
			filterChain.doFilter(request, response);
			return;
		}

		long blockedSeconds = blockedRetryAfter(basicFailureBuckets, key, effectiveLimit, nowMillis);
		if (blockedSeconds > 0L) {
			rejectTooManyRequests(request, response, blockedSeconds);
			return;
		}
		filterChain.doFilter(request, response);
		if (response.getStatus() == HttpStatus.UNAUTHORIZED.value()) {
			recordFailure(basicFailureBuckets, key, nowMillis);
		}
	}

	private static boolean isFormLoginPost(HttpServletRequest request) {
		if (!HttpMethod.POST.matches(request.getMethod())) {
			return false;
		}
		String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
		return ADMIN_LOGIN_PATH.equals(path) || OPERATOR_LOGIN_PATH.equals(path);
	}

	private static boolean hasBasicAuthHeader(HttpServletRequest request) {
		String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
		return authorization != null
			&& authorization.length() > 6
			&& authorization.regionMatches(true, 0, "Basic ", 0, 6);
	}

	private static void rejectTooManyRequests(
		HttpServletRequest request,
		HttpServletResponse response,
		long retryAfterSeconds
	) throws IOException {
		response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
		response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
		response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		if (!HttpMethod.HEAD.matches(request.getMethod())) {
			response.getOutputStream().write(TOO_MANY_REQUESTS_BODY);
		}
	}

	private static long tryConsume(Map<String, WindowBucket> map, String key, int limit, long nowMillis) {
		synchronized (map) {
			WindowBucket bucket = map.get(key);
			if (bucket == null || nowMillis - bucket.windowStartMillis >= WINDOW_MILLIS) {
				map.put(key, new WindowBucket(nowMillis, 1));
				return 0L;
			}
			if (bucket.count < limit) {
				bucket.count++;
				return 0L;
			}
			long remainingMillis = WINDOW_MILLIS - (nowMillis - bucket.windowStartMillis);
			return Math.max(1L, (remainingMillis + 999L) / 1000L);
		}
	}

	private static long blockedRetryAfter(Map<String, WindowBucket> map, String key, int limit, long nowMillis) {
		synchronized (map) {
			WindowBucket bucket = map.get(key);
			if (bucket == null || nowMillis - bucket.windowStartMillis >= WINDOW_MILLIS) {
				return 0L;
			}
			if (bucket.count < limit) {
				return 0L;
			}
			long remainingMillis = WINDOW_MILLIS - (nowMillis - bucket.windowStartMillis);
			return Math.max(1L, (remainingMillis + 999L) / 1000L);
		}
	}

	private static void recordFailure(Map<String, WindowBucket> map, String key, long nowMillis) {
		synchronized (map) {
			WindowBucket bucket = map.get(key);
			if (bucket == null || nowMillis - bucket.windowStartMillis >= WINDOW_MILLIS) {
				map.put(key, new WindowBucket(nowMillis, 1));
				return;
			}
			bucket.count++;
		}
	}

	private static final class WindowBucket {
		private final long windowStartMillis;
		private int count;

		private WindowBucket(long windowStartMillis, int count) {
			this.windowStartMillis = windowStartMillis;
			this.count = count;
		}
	}
}

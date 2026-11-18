package com.easysubway.report.adapter.in.web;

import com.easysubway.common.web.TrustedClientIpResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
final class FacilityReportAbuseControl extends OncePerRequestFilter {

	private final FacilityReportAbuseControlLimiter limiter;
	private final FacilityReportClientIdentityResolver clientIdentityResolver;

	FacilityReportAbuseControl(
		@Value("${easysubway.report.abuse-control.window-seconds:60}") long windowSeconds,
		@Value("${easysubway.report.abuse-control.upload-intent-limit:1000}") int uploadIntentLimit,
		@Value("${easysubway.report.abuse-control.upload-claim-limit:1000}") int uploadClaimLimit,
		@Value("${easysubway.report.abuse-control.report-submit-limit:1000}") int reportSubmitLimit,
		@Value("${easysubway.report.abuse-control.status-limit:1000}") int statusLimit,
		@Value("${easysubway.report.abuse-control.confirm-limit:1000}") int confirmLimit,
		@Value("${easysubway.report.abuse-control.max-counter-keys:16384}") int maxCounterKeys,
		@Value("${easysubway.report.abuse-control.store-mode:local}") String storeMode,
		@Value("${easysubway.auth.client-ip.trusted-proxies:}") String trustedProxies,
		ObjectProvider<Clock> clockProvider
	) {
		FacilityReportAbuseControlPolicy policy = new FacilityReportAbuseControlPolicy(
			windowSeconds,
			maxCounterKeys,
			storeMode,
			Map.of(
				ReportAbuseGroup.UPLOAD_INTENT, uploadIntentLimit,
				ReportAbuseGroup.UPLOAD_CLAIM, uploadClaimLimit,
				ReportAbuseGroup.REPORT_SUBMIT, reportSubmitLimit,
				ReportAbuseGroup.STATUS, statusLimit,
				ReportAbuseGroup.CONFIRM, confirmLimit
			)
		);
		this.limiter = new FacilityReportAbuseControlLimiter(
			policy,
			clockProvider.getIfAvailable(Clock::systemUTC)
		);
		this.clientIdentityResolver = new FacilityReportClientIdentityResolver(trustedProxies);
	}

	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain
	) throws ServletException, IOException {
		Optional<ReportAbuseGroup> reportAbuseGroup = ReportAbuseGroup.from(request);
		if (reportAbuseGroup.isPresent()) {
			ReportAbuseGroup group = reportAbuseGroup.get();
			FacilityReportAbuseControlLimiter.AcquireResult result = limiter.acquire(
				group,
				clientIdentityResolver.resolve(request)
			);
			if (!result.allowed()) {
				response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
				response.setHeader("Cache-Control", "no-store");
				response.setHeader("Retry-After", Long.toString(result.retryAfterSeconds()));
				return;
			}
		}
		filterChain.doFilter(request, response);
	}
}

enum ReportAbuseGroup {
	UPLOAD_INTENT,
	UPLOAD_CLAIM,
	REPORT_SUBMIT,
	STATUS,
	CONFIRM;

	private static final Pattern REPORT_STATUS_PATH = Pattern.compile("^/api/v1/reports/[^/]+$");
	private static final Pattern REPORT_CONFIRM_PATH = Pattern.compile("^/api/v1/reports/[^/]+/confirm$");
	private static final Pattern UPLOAD_CLAIM_PATH = Pattern.compile("^/api/v1/report-uploads/[^/]+$");

	static Optional<ReportAbuseGroup> from(HttpServletRequest request) {
		String method = request.getMethod();
		String path = request.getRequestURI();
		if (HttpMethod.POST.matches(method) && "/api/v1/report-uploads".equals(path)) {
			return Optional.of(UPLOAD_INTENT);
		}
		if (HttpMethod.PUT.matches(method) && UPLOAD_CLAIM_PATH.matcher(path).matches()) {
			return Optional.of(UPLOAD_CLAIM);
		}
		if (HttpMethod.POST.matches(method) && "/api/v1/reports".equals(path)) {
			return Optional.of(REPORT_SUBMIT);
		}
		if (HttpMethod.GET.matches(method) && REPORT_STATUS_PATH.matcher(path).matches()) {
			return Optional.of(STATUS);
		}
		if (HttpMethod.POST.matches(method) && REPORT_CONFIRM_PATH.matcher(path).matches()) {
			return Optional.of(CONFIRM);
		}
		return Optional.empty();
	}
}

record FacilityReportAbuseControlPolicy(
	long windowSeconds,
	int maxCounterKeys,
	String storeMode,
	Map<ReportAbuseGroup, Integer> limits
) {

	FacilityReportAbuseControlPolicy {
		if (windowSeconds < 1) {
			throw new IllegalArgumentException("report abuse control window must be positive");
		}
		if (maxCounterKeys < 1) {
			throw new IllegalArgumentException("report abuse control max counter keys must be positive");
		}
		if (!"local".equals(storeMode) && !"single-serving-replica".equals(storeMode) && !"single-replica".equals(storeMode)) {
			throw new IllegalArgumentException("report abuse control store mode must be local or single-serving-replica until distributed store is implemented");
		}
		for (ReportAbuseGroup group : ReportAbuseGroup.values()) {
			Integer limit = limits.get(group);
			if (limit == null || limit < 1) {
				throw new IllegalArgumentException("report abuse control limit must be positive: " + group);
			}
		}
	}

	int limit(ReportAbuseGroup group) {
		return limits.get(group);
	}

	boolean usesReleaseBlockingLocalStore() {
		return "local".equals(storeMode);
	}
}

class FacilityReportAbuseControlLimiter {

	static final int MAX_KEYS_PER_GROUP = 16_384;

	record AcquireResult(boolean allowed, long retryAfterSeconds) {
		static AcquireResult allow() {
			return new AcquireResult(true, 0);
		}

		static AcquireResult limit(long retryAfterSeconds) {
			return new AcquireResult(false, Math.max(1, retryAfterSeconds));
		}
	}

	private final FacilityReportAbuseControlPolicy policy;
	private final Clock clock;
	private final EnumMap<ReportAbuseGroup, GroupWindowLru> groupLimiters;

	FacilityReportAbuseControlLimiter(FacilityReportAbuseControlPolicy policy, Clock clock) {
		this.policy = policy;
		this.clock = clock;
		int capacityPerGroup = Math.min(policy.maxCounterKeys(), MAX_KEYS_PER_GROUP);
		this.groupLimiters = new EnumMap<>(ReportAbuseGroup.class);
		for (ReportAbuseGroup group : ReportAbuseGroup.values()) {
			this.groupLimiters.put(group, new GroupWindowLru(capacityPerGroup));
		}
	}

	AcquireResult acquire(ReportAbuseGroup group, String clientIdentity) {
		int limit = policy.limit(group);
		Instant now = Instant.now(clock);
		long windowStartedAt = currentWindowStartedAt(now);
		long elapsed = now.getEpochSecond() - windowStartedAt;
		long retryAfterSeconds = Math.max(1, policy.windowSeconds() - elapsed);
		return groupLimiters.get(group).acquire(clientIdentity, limit, windowStartedAt, retryAfterSeconds);
	}

	boolean tryAcquire(ReportAbuseGroup group, String clientIdentity) {
		return acquire(group, clientIdentity).allowed();
	}

	int maxKeysPerGroup() {
		return Math.min(policy.maxCounterKeys(), MAX_KEYS_PER_GROUP);
	}

	int groupSize(ReportAbuseGroup group) {
		return groupLimiters.get(group).size();
	}

	private long currentWindowStartedAt(Instant now) {
		long epochSecond = now.getEpochSecond();
		return epochSecond - Math.floorMod(epochSecond, policy.windowSeconds());
	}

	private static final class GroupWindowLru {

		private static final int CLEANUP_INTERVAL_OPS = 64;
		private static final int CLEANUP_MAX_BATCH = 32;

		private final int capacity;
		private final LinkedHashMap<String, WindowCounter> counters;
		private int opsSinceCleanup;

		private GroupWindowLru(int capacity) {
			this.capacity = capacity;
			this.counters = new LinkedHashMap<>(Math.min(capacity, 256), 0.75f, true);
		}

		private synchronized AcquireResult acquire(
			String clientIdentity,
			int limit,
			long windowStartedAt,
			long retryAfterSeconds
		) {
			opsSinceCleanup++;
			if (opsSinceCleanup >= CLEANUP_INTERVAL_OPS) {
				opsSinceCleanup = 0;
				evictExpiredHead(windowStartedAt, CLEANUP_MAX_BATCH);
			}
			WindowCounter counter = counters.get(clientIdentity);
			if (counter == null) {
				if (counters.size() >= capacity) {
					evictExpiredHead(windowStartedAt, capacity);
					if (counters.size() >= capacity) {
						return AcquireResult.limit(retryAfterSeconds);
					}
				}
				counter = new WindowCounter(windowStartedAt);
				counters.put(clientIdentity, counter);
			}
			boolean allowed = counter.incrementWithin(windowStartedAt, limit);
			return allowed ? AcquireResult.allow() : AcquireResult.limit(retryAfterSeconds);
		}

		private void evictExpiredHead(long currentWindowStartedAt, int maxRemovals) {
			int removed = 0;
			Iterator<Map.Entry<String, WindowCounter>> iterator = counters.entrySet().iterator();
			while (iterator.hasNext() && removed < maxRemovals) {
				WindowCounter eldest = iterator.next().getValue();
				if (!eldest.isBefore(currentWindowStartedAt)) {
					break;
				}
				iterator.remove();
				removed++;
			}
		}

		private synchronized int size() {
			return counters.size();
		}
	}

	private static final class WindowCounter {

		private long windowStartedAt;
		private int count;

		private WindowCounter(long windowStartedAt) {
			this.windowStartedAt = windowStartedAt;
		}

		private boolean incrementWithin(long currentWindowStartedAt, int limit) {
			if (windowStartedAt != currentWindowStartedAt) {
				windowStartedAt = currentWindowStartedAt;
				count = 0;
			}
			if (count >= limit) {
				return false;
			}
			count++;
			return true;
		}

		private boolean isBefore(long currentWindowStartedAt) {
			return windowStartedAt < currentWindowStartedAt;
		}
	}
}

class FacilityReportClientIdentityResolver {

	static final String UNKNOWN_CLIENT = TrustedClientIpResolver.UNKNOWN;
	private final TrustedClientIpResolver ipResolver;

	FacilityReportClientIdentityResolver(String trustedProxyCidrs) {
		this.ipResolver = TrustedClientIpResolver.fromCidrs(trustedProxyCidrs);
	}

	String resolve(HttpServletRequest request) {
		return "ip:" + ipResolver.resolve(request);
	}

	static String normalizeAddress(String address) {
		if (address == null || address.isBlank()) {
			return UNKNOWN_CLIENT;
		}
		try {
			return parseLiteral(address).getHostAddress().toLowerCase(Locale.ROOT);
		} catch (IllegalArgumentException exception) {
			return UNKNOWN_CLIENT;
		}
	}

	static InetAddress parseLiteral(String value) {
		return TrustedClientIpResolver.parseLiteral(value);
	}
}

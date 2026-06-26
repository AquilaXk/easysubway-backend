package com.easysubway.report.adapter.in.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
class FacilityReportAbuseControl extends OncePerRequestFilter {

	private final FacilityReportAbuseControlLimiter limiter;
	private final FacilityReportClientIdentityResolver clientIdentityResolver;

	FacilityReportAbuseControl(
		@Value("${easysubway.report.abuse-control.window-seconds:60}") long windowSeconds,
		@Value("${easysubway.report.abuse-control.upload-intent-limit:1000}") int uploadIntentLimit,
		@Value("${easysubway.report.abuse-control.upload-claim-limit:1000}") int uploadClaimLimit,
		@Value("${easysubway.report.abuse-control.report-submit-limit:1000}") int reportSubmitLimit,
		@Value("${easysubway.report.abuse-control.status-limit:1000}") int statusLimit,
		@Value("${easysubway.report.abuse-control.confirm-limit:1000}") int confirmLimit,
		@Value("${easysubway.report.abuse-control.max-counter-keys:4096}") int maxCounterKeys,
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
			if (!limiter.tryAcquire(group, clientIdentityResolver.resolve(request))) {
				response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
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
		if (!"local".equals(storeMode)) {
			throw new IllegalArgumentException("report abuse control store mode must be local until distributed store is implemented");
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

	private final FacilityReportAbuseControlPolicy policy;
	private final Clock clock;
	private final Map<LimiterKey, WindowCounter> counters = new ConcurrentHashMap<>();

	FacilityReportAbuseControlLimiter(FacilityReportAbuseControlPolicy policy, Clock clock) {
		this.policy = policy;
		this.clock = clock;
	}

	boolean tryAcquire(ReportAbuseGroup group, String clientIdentity) {
		int limit = policy.limit(group);
		if (limit < 1) {
			return true;
		}
		long windowStartedAt = currentWindowStartedAt(Instant.now(clock));
		WindowCounter counter = counterFor(new LimiterKey(group, clientIdentity), windowStartedAt);
		if (counter == null) {
			return false;
		}
		boolean allowed = counter.incrementWithin(windowStartedAt, limit);
		return allowed;
	}

	private WindowCounter counterFor(LimiterKey key, long windowStartedAt) {
		WindowCounter existingCounter = counters.get(key);
		if (existingCounter != null) {
			return existingCounter;
		}
		synchronized (counters) {
			WindowCounter counter = counters.get(key);
			if (counter != null) {
				return counter;
			}
			counters.entrySet().removeIf(entry -> entry.getValue().isBefore(windowStartedAt));
			if (counters.size() >= policy.maxCounterKeys()) {
				return null;
			}
			WindowCounter newCounter = new WindowCounter(windowStartedAt);
			counters.put(key, newCounter);
			return newCounter;
		}
	}

	private long currentWindowStartedAt(Instant now) {
		long epochSecond = now.getEpochSecond();
		return epochSecond - Math.floorMod(epochSecond, policy.windowSeconds());
	}

	private record LimiterKey(ReportAbuseGroup group, String clientIdentity) {
	}

	private static final class WindowCounter {

		private long windowStartedAt;
		private int count;

		private WindowCounter(long windowStartedAt) {
			this.windowStartedAt = windowStartedAt;
		}

		private synchronized boolean incrementWithin(long currentWindowStartedAt, int limit) {
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

		private synchronized boolean isBefore(long currentWindowStartedAt) {
			return windowStartedAt < currentWindowStartedAt;
		}
	}
}

class FacilityReportClientIdentityResolver {

	private final List<IpCidr> trustedProxies;

	FacilityReportClientIdentityResolver(String trustedProxyCidrs) {
		this.trustedProxies = parseTrustedProxies(trustedProxyCidrs);
	}

	String resolve(HttpServletRequest request) {
		String remoteAddress = normalizeAddress(request.getRemoteAddr());
		if (isTrustedProxy(remoteAddress)) {
			String forwardedFor = request.getHeader("X-Forwarded-For");
			if (forwardedFor != null && !forwardedFor.isBlank()) {
				return "ip:" + trustedClientAddress(forwardedFor);
			}
		}
		return "ip:" + remoteAddress;
	}

	private String trustedClientAddress(String forwardedFor) {
		String[] addresses = forwardedFor.split(",");
		for (int index = addresses.length - 1; index >= 0; index--) {
			String candidate = normalizeAddress(addresses[index]);
			if (!isTrustedProxy(candidate) && IpCidr.isValidIpv4(candidate)) {
				return candidate;
			}
		}
		return "unknown";
	}

	private boolean isTrustedProxy(String remoteAddress) {
		return trustedProxies.stream().anyMatch(proxy -> proxy.contains(remoteAddress));
	}

	private static String normalizeAddress(String address) {
		if (address == null || address.isBlank()) {
			return "unknown";
		}
		return address.trim().toLowerCase(Locale.ROOT);
	}

	private static List<IpCidr> parseTrustedProxies(String trustedProxyCidrs) {
		if (trustedProxyCidrs == null || trustedProxyCidrs.isBlank()) {
			return List.of();
		}
		List<IpCidr> cidrs = new ArrayList<>();
		for (String value : trustedProxyCidrs.split(",")) {
			String normalized = value.trim();
			if (!normalized.isBlank()) {
				cidrs.add(IpCidr.parse(normalized));
			}
		}
		return List.copyOf(cidrs);
	}
}

record IpCidr(int address, int mask) {

	static IpCidr parse(String value) {
		String[] parts = value.split("/", -1);
		if (parts.length < 1 || parts.length > 2 || parts[0].isBlank()) {
			throw new IllegalArgumentException("invalid IPv4 CIDR: " + value);
		}
		String address = parts[0].trim();
		int prefixLength = parts.length == 2 ? Integer.parseInt(parts[1].trim()) : 32;
		if (prefixLength < 0 || prefixLength > 32) {
			throw new IllegalArgumentException("invalid IPv4 CIDR prefix: " + value);
		}
		int mask = prefixLength == 0 ? 0 : -1 << (32 - prefixLength);
		return new IpCidr(ipv4ToInt(address), mask);
	}

	boolean contains(String candidateAddress) {
		try {
			return (ipv4ToInt(candidateAddress) & mask) == (address & mask);
		} catch (IllegalArgumentException exception) {
			return false;
		}
	}

	static boolean isValidIpv4(String value) {
		try {
			ipv4ToInt(value);
			return true;
		} catch (IllegalArgumentException exception) {
			return false;
		}
	}

	private static int ipv4ToInt(String value) {
		String[] octets = value.trim().split("\\.");
		if (octets.length != 4) {
			throw new IllegalArgumentException("only IPv4 CIDR is supported");
		}
		int result = 0;
		for (String octet : octets) {
			int parsed = Integer.parseInt(octet);
			if (parsed < 0 || parsed > 255) {
				throw new IllegalArgumentException("invalid IPv4 address: " + value);
			}
			result = (result << 8) | parsed;
		}
		return result;
	}
}

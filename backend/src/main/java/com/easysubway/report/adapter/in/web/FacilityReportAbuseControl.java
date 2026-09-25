package com.easysubway.report.adapter.in.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
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
	private final Map<LimiterKey, WindowCounter> counters = new ConcurrentHashMap<>();

	FacilityReportAbuseControlLimiter(FacilityReportAbuseControlPolicy policy, Clock clock) {
		this.policy = policy;
		this.clock = clock;
	}

	AcquireResult acquire(ReportAbuseGroup group, String clientIdentity) {
		int limit = policy.limit(group);
		if (limit < 1) {
			return AcquireResult.allow();
		}
		Instant now = Instant.now(clock);
		long windowStartedAt = currentWindowStartedAt(now);
		long elapsed = now.getEpochSecond() - windowStartedAt;
		long retryAfterSeconds = Math.max(1, policy.windowSeconds() - elapsed);

		WindowCounter counter = counterFor(new LimiterKey(group, clientIdentity), windowStartedAt);
		if (counter == null) {
			return AcquireResult.limit(retryAfterSeconds);
		}
		boolean allowed = counter.incrementWithin(windowStartedAt, limit);
		return allowed ? AcquireResult.allow() : AcquireResult.limit(retryAfterSeconds);
	}

	boolean tryAcquire(ReportAbuseGroup group, String clientIdentity) {
		return acquire(group, clientIdentity).allowed();
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
			if (!"unknown".equals(candidate) && !isTrustedProxy(candidate) && IpCidr.isValidIp(candidate)) {
				return candidate;
			}
		}
		return "unknown";
	}

	private boolean isTrustedProxy(String remoteAddress) {
		return trustedProxies.stream().anyMatch(proxy -> proxy.contains(remoteAddress));
	}

	static String normalizeAddress(String address) {
		if (address == null || address.isBlank()) {
			return "unknown";
		}
		try {
			return parseLiteral(address).getHostAddress().toLowerCase(Locale.ROOT);
		} catch (IllegalArgumentException exception) {
			return "unknown";
		}
	}

	static InetAddress parseLiteral(String value) {
		if (value == null) {
			throw new IllegalArgumentException("IP address cannot be null");
		}
		String candidate = value.trim();
		if (candidate.startsWith("[") && candidate.contains("]")) {
			int closeBracket = candidate.indexOf("]");
			candidate = candidate.substring(1, closeBracket);
		} else if (candidate.contains(":") && candidate.indexOf(":") == candidate.lastIndexOf(":")) {
			candidate = candidate.substring(0, candidate.indexOf(":"));
		}
		if (candidate.contains("%")) {
			candidate = candidate.substring(0, candidate.indexOf("%"));
		}
		if (candidate.isBlank()) {
			throw new IllegalArgumentException("invalid IP address: " + value);
		}
		if (candidate.contains(":")) {
			if (!candidate.matches("[0-9A-Fa-f:.]+")) {
				throw new IllegalArgumentException("invalid IPv6 address: " + value);
			}
		} else if (!validIpv4(candidate)) {
			throw new IllegalArgumentException("invalid IPv4 address: " + value);
		}
		try {
			return InetAddress.getByName(candidate);
		} catch (UnknownHostException exception) {
			throw new IllegalArgumentException("invalid IP address: " + value, exception);
		}
	}

	private static boolean validIpv4(String value) {
		String[] parts = value.split("\\.", -1);
		if (parts.length != 4) {
			return false;
		}
		for (String part : parts) {
			if (part.isEmpty() || !part.chars().allMatch(Character::isDigit)) {
				return false;
			}
			try {
				int octet = Integer.parseInt(part);
				if (octet < 0 || octet > 255) {
					return false;
				}
			} catch (NumberFormatException exception) {
				return false;
			}
		}
		return true;
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

record IpCidr(byte[] address, int prefixLength) {

	static IpCidr parse(String value) {
		String[] parts = value.split("/", -1);
		if (parts.length < 1 || parts.length > 2 || parts[0].isBlank()) {
			throw new IllegalArgumentException("invalid IPv4 CIDR: " + value);
		}
		InetAddress inetAddress;
		try {
			inetAddress = FacilityReportClientIdentityResolver.parseLiteral(parts[0].trim());
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("invalid IPv4 CIDR: " + value, e);
		}
		byte[] addressBytes = inetAddress.getAddress();
		int maxPrefix = addressBytes.length * Byte.SIZE;
		int prefixLength;
		try {
			prefixLength = parts.length == 2 ? Integer.parseInt(parts[1].trim()) : maxPrefix;
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("invalid IPv4 CIDR prefix: " + value, e);
		}
		if (prefixLength < 0 || prefixLength > maxPrefix) {
			throw new IllegalArgumentException("invalid IPv4 CIDR prefix: " + value);
		}
		return new IpCidr(addressBytes, prefixLength);
	}

	boolean contains(String candidateAddress) {
		if (candidateAddress == null || "unknown".equals(candidateAddress)) {
			return false;
		}
		try {
			InetAddress inetAddress = FacilityReportClientIdentityResolver.parseLiteral(candidateAddress);
			byte[] other = inetAddress.getAddress();
			if (address.length != other.length) {
				return false;
			}
			int fullBytes = prefixLength / Byte.SIZE;
			if (!Arrays.equals(Arrays.copyOf(address, fullBytes), Arrays.copyOf(other, fullBytes))) {
				return false;
			}
			int remainingBits = prefixLength % Byte.SIZE;
			if (remainingBits == 0) {
				return true;
			}
			int mask = 0xff << (Byte.SIZE - remainingBits);
			return (address[fullBytes] & mask) == (other[fullBytes] & mask);
		} catch (IllegalArgumentException exception) {
			return false;
		}
	}

	static boolean isValidIpv4(String value) {
		try {
			InetAddress inet = FacilityReportClientIdentityResolver.parseLiteral(value);
			return inet instanceof java.net.Inet4Address;
		} catch (IllegalArgumentException exception) {
			return false;
		}
	}

	static boolean isValidIp(String value) {
		try {
			FacilityReportClientIdentityResolver.parseLiteral(value);
			return true;
		} catch (IllegalArgumentException exception) {
			return false;
		}
	}

	@Override
	public byte[] address() {
		return address.clone();
	}
}

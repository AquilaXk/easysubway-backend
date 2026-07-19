package com.easysubway.train.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

@Component
class TrainSearchRateLimitFilter extends OncePerRequestFilter {

	private static final String STATIONS_PATH = "/api/v1/trains/stations";
	private static final String SEARCH_PATH = "/api/v1/trains/search";

	private final ObjectMapper objectMapper;
	private final TrainSearchRateLimiter stationLimiter;
	private final TrainSearchRateLimiter searchLimiter;
	private final TrainSearchClientIdentityResolver identityResolver;

	@Autowired
	TrainSearchRateLimitFilter(
		ObjectMapper objectMapper,
		@Value("${EASYSUBWAY_TRAIN_STATION_RATE_LIMIT_PER_MINUTE:60}") int stationLimit,
		@Value("${EASYSUBWAY_TRAIN_SEARCH_RATE_LIMIT_PER_MINUTE:24}") int searchLimit,
		@Value("${EASYSUBWAY_TRAIN_SEARCH_RATE_LIMIT_PER_DAY:64}") int searchDailyLimit,
		@Value("${EASYSUBWAY_TRAIN_SEARCH_RATE_LIMIT_MAX_KEYS:4096}") int maxKeys,
		@Value("${easysubway.auth.client-ip.trusted-proxies:}") String trustedProxies,
		ObjectProvider<Clock> clockProvider
	) {
		this.objectMapper = objectMapper;
		Clock clock = clockProvider.getIfAvailable(Clock::systemUTC);
		this.stationLimiter = new TrainSearchRateLimiter(stationLimit, maxKeys, clock);
		this.searchLimiter = new TrainSearchRateLimiter(
			searchLimit,
			searchDailyLimit,
			maxKeys,
			clock,
			ZoneId.of("Asia/Seoul")
		);
		this.identityResolver = new TrainSearchClientIdentityResolver(trustedProxies);
	}

	TrainSearchRateLimitFilter(
		ObjectMapper objectMapper,
		TrainSearchRateLimiter limiter,
		TrainSearchClientIdentityResolver identityResolver
	) {
		this.objectMapper = objectMapper;
		this.stationLimiter = limiter;
		this.searchLimiter = limiter;
		this.identityResolver = identityResolver;
	}

	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain
	) throws ServletException, IOException {
		String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
		TrainSearchRateLimiter limiter = limiter(request, path);
		if (limiter == null) {
			filterChain.doFilter(request, response);
			return;
		}
		int cost = SEARCH_PATH.equals(path) ? searchCost(request) : 1;
		TrainSearchRateLimiter.AcquireResult result = limiter.acquire(identityResolver.resolve(request), cost);
		if (result.allowed()) {
			filterChain.doFilter(request, response);
			return;
		}
		response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
		response.setHeader("Cache-Control", "no-store");
		response.setHeader("Retry-After", Long.toString(result.retryAfterSeconds()));
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		if (HttpMethod.HEAD.matches(request.getMethod())) return;
		objectMapper.writeValue(response.getOutputStream(), Map.of(
			"success", false,
			"data", Map.of("code", "TRAIN_SEARCH_RATE_LIMITED"),
			"message", "기차검색 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."
		));
	}

	private TrainSearchRateLimiter limiter(HttpServletRequest request, String path) {
		if (!HttpMethod.GET.matches(request.getMethod()) && !HttpMethod.HEAD.matches(request.getMethod())) return null;
		return switch (path) {
			case STATIONS_PATH -> stationLimiter;
			case SEARCH_PATH -> searchLimiter;
			default -> null;
		};
	}

	private int searchCost(HttpServletRequest request) {
		int cost = hasText(request.getParameter("trainType")) ? 1 : 8;
		return hasText(request.getParameter("returnDate")) ? cost * 2 : cost;
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}
}

final class TrainSearchRateLimiter {

	private static final long WINDOW_SECONDS = 60;

	private final int limit;
	private final Integer dailyLimit;
	private final int maxKeys;
	private final Clock clock;
	private final ZoneId dayZone;
	private final Map<String, WindowCounter> counters = new ConcurrentHashMap<>();
	private long countersWindow = Long.MIN_VALUE;

	TrainSearchRateLimiter(int limit, int maxKeys, Clock clock) {
		this(limit, null, maxKeys, clock, ZoneId.of("UTC"));
	}

	TrainSearchRateLimiter(int limit, int dailyLimit, int maxKeys, Clock clock, ZoneId dayZone) {
		this(limit, Integer.valueOf(dailyLimit), maxKeys, clock, dayZone);
	}

	private TrainSearchRateLimiter(int limit, Integer dailyLimit, int maxKeys, Clock clock, ZoneId dayZone) {
		if (limit < 1 || (dailyLimit != null && dailyLimit < 1) || maxKeys < 1) {
			throw new IllegalArgumentException("train-search rate limit must be positive");
		}
		this.limit = limit;
		this.dailyLimit = dailyLimit;
		this.maxKeys = maxKeys;
		this.clock = clock;
		this.dayZone = dayZone;
	}

	synchronized AcquireResult acquire(String identity, int cost) {
		if (cost < 1) throw new IllegalArgumentException("train-search request cost must be positive");
		Instant currentInstant = Instant.now(clock);
		long now = currentInstant.getEpochSecond();
		long window = now - Math.floorMod(now, WINDOW_SECONDS);
		LocalDate currentDate = currentInstant.atZone(dayZone).toLocalDate();
		long day = currentDate.toEpochDay();
		long retentionWindow = dailyLimit == null ? window : day;
		if (countersWindow != retentionWindow) {
			counters.clear();
			countersWindow = retentionWindow;
		}
		WindowCounter counter = counterFor(identity, window, day);
		long minuteRetryAfter = WINDOW_SECONDS - Math.floorMod(now, WINDOW_SECONDS);
		var untilNextDay = java.time.Duration.between(
			currentInstant,
			currentDate.plusDays(1).atStartOfDay(dayZone).toInstant()
		);
		long dailyRetryAfter = untilNextDay.getSeconds() + (untilNextDay.getNano() == 0 ? 0 : 1);
		WindowCounter.Decision decision = counter == null
			? WindowCounter.Decision.CARDINALITY_DENIED
			: counter.acquire(window, day, cost, limit, dailyLimit);
		boolean retriesNextDay = decision == WindowCounter.Decision.DAILY_DENIED
			|| (decision == WindowCounter.Decision.CARDINALITY_DENIED && dailyLimit != null);
		long retryAfter = retriesNextDay ? dailyRetryAfter : minuteRetryAfter;
		return decision == WindowCounter.Decision.ALLOWED
			? new AcquireResult(true, 0)
			: new AcquireResult(false, retryAfter);
	}

	private WindowCounter counterFor(String identity, long window, long day) {
		WindowCounter existing = counters.get(identity);
		if (existing != null) return existing;
		if (counters.size() >= maxKeys) return null;
		WindowCounter created = new WindowCounter(window, day);
		counters.put(identity, created);
		return created;
	}

	record AcquireResult(boolean allowed, long retryAfterSeconds) {}

	private static final class WindowCounter {
		private long window;
		private long day;
		private int used;
		private int dailyUsed;

		private WindowCounter(long window, long day) {
			this.window = window;
			this.day = day;
		}

		private synchronized Decision acquire(
			long currentWindow,
			long currentDay,
			int cost,
			int limit,
			Integer dailyLimit
		) {
			if (window != currentWindow) {
				window = currentWindow;
				used = 0;
			}
			if (day != currentDay) {
				day = currentDay;
				dailyUsed = 0;
			}
			if (dailyLimit != null && cost > dailyLimit - dailyUsed) return Decision.DAILY_DENIED;
			if (cost > limit - used) return Decision.MINUTE_DENIED;
			used += cost;
			if (dailyLimit != null) dailyUsed += cost;
			return Decision.ALLOWED;
		}

		private enum Decision { ALLOWED, MINUTE_DENIED, DAILY_DENIED, CARDINALITY_DENIED }
	}
}

final class TrainSearchClientIdentityResolver {

	private final List<IpCidrRange> trustedProxies;

	TrainSearchClientIdentityResolver(String trustedProxyCidrs) {
		this.trustedProxies = parseCidrs(trustedProxyCidrs);
	}

	String resolve(HttpServletRequest request) {
		String remote = normalize(request.getRemoteAddr());
		if (trusted(remote)) {
			String forwardedFor = request.getHeader("X-Forwarded-For");
			if (forwardedFor != null && !forwardedFor.isBlank()) {
				String[] chain = forwardedFor.split(",");
				for (int index = chain.length - 1; index >= 0; index--) {
					String candidate = normalize(chain[index]);
					if (!"unknown".equals(candidate) && !trusted(candidate)) return "ip:" + candidate;
				}
				return "ip:unknown";
			}
		}
		return "ip:" + remote;
	}

	private boolean trusted(String address) {
		return trustedProxies.stream().anyMatch(cidr -> cidr.contains(address));
	}

	private static List<IpCidrRange> parseCidrs(String values) {
		if (values == null || values.isBlank()) return List.of();
		List<IpCidrRange> result = new ArrayList<>();
		for (String value : values.split(",")) {
			if (!value.isBlank()) result.add(IpCidrRange.parse(value.trim()));
		}
		return List.copyOf(result);
	}

	private static String normalize(String value) {
		if (value == null || value.isBlank()) return "unknown";
		try {
			return parseLiteral(value.trim()).getHostAddress().toLowerCase(Locale.ROOT);
		} catch (IllegalArgumentException exception) {
			return "unknown";
		}
	}

	static InetAddress parseLiteral(String value) {
		String candidate = value.trim();
		if (candidate.contains(":")) {
			if (!candidate.matches("[0-9A-Fa-f:.]+")) throw new IllegalArgumentException("invalid IPv6 address");
		} else if (!validIpv4(candidate)) {
			throw new IllegalArgumentException("invalid IPv4 address");
		}
		try {
			return InetAddress.getByName(candidate);
		} catch (UnknownHostException exception) {
			throw new IllegalArgumentException("invalid IP address", exception);
		}
	}

	private static boolean validIpv4(String value) {
		String[] parts = value.split("\\.", -1);
		if (parts.length != 4) return false;
		for (String part : parts) {
			if (part.isEmpty() || !part.chars().allMatch(Character::isDigit)) return false;
			try {
				if (Integer.parseInt(part) > 255) return false;
			} catch (NumberFormatException exception) {
				return false;
			}
		}
		return true;
	}
}

record IpCidrRange(byte[] address, int prefixLength) {

	static IpCidrRange parse(String value) {
		String[] parts = value.split("/", -1);
		if (parts.length > 2 || parts[0].isBlank()) throw new IllegalArgumentException("invalid IP CIDR");
		byte[] address = TrainSearchClientIdentityResolver.parseLiteral(parts[0]).getAddress();
		int maxPrefix = address.length * Byte.SIZE;
		int prefix = parts.length == 2 ? Integer.parseInt(parts[1]) : maxPrefix;
		if (prefix < 0 || prefix > maxPrefix) throw new IllegalArgumentException("invalid IP CIDR prefix");
		return new IpCidrRange(address, prefix);
	}

	boolean contains(String candidate) {
		if ("unknown".equals(candidate)) return false;
		byte[] other;
		try {
			other = TrainSearchClientIdentityResolver.parseLiteral(candidate).getAddress();
		} catch (IllegalArgumentException exception) {
			return false;
		}
		if (address.length != other.length) return false;
		int fullBytes = prefixLength / Byte.SIZE;
		if (!Arrays.equals(Arrays.copyOf(address, fullBytes), Arrays.copyOf(other, fullBytes))) return false;
		int remainingBits = prefixLength % Byte.SIZE;
		if (remainingBits == 0) return true;
		int mask = 0xff << (Byte.SIZE - remainingBits);
		return (address[fullBytes] & mask) == (other[fullBytes] & mask);
	}

	@Override
	public byte[] address() {
		return address.clone();
	}
}

package com.easysubway.train.adapter.in.web;

import com.easysubway.common.time.BusinessTime;
import com.easysubway.common.web.ClientIpHashResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
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
	private final ClientIpHashResolver identityResolver;

	@Autowired
	TrainSearchRateLimitFilter(
		ObjectMapper objectMapper,
		@Value("${EASYSUBWAY_TRAIN_STATION_RATE_LIMIT_PER_MINUTE:60}") int stationLimit,
		@Value("${EASYSUBWAY_TRAIN_SEARCH_RATE_LIMIT_PER_MINUTE:24}") int searchLimit,
		@Value("${EASYSUBWAY_TRAIN_SEARCH_RATE_LIMIT_PER_DAY:64}") int searchDailyLimit,
		@Value("${EASYSUBWAY_TRAIN_SEARCH_RATE_LIMIT_MAX_KEYS:65536}") int maxKeys,
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
			BusinessTime.ZONE
		);
		this.identityResolver = ClientIpHashResolver.fromTrustedProxies(trustedProxies, "");
	}

	TrainSearchRateLimitFilter(
		ObjectMapper objectMapper,
		TrainSearchRateLimiter limiter,
		ClientIpHashResolver identityResolver
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

	static final int MAX_KEYS = 65_536;
	private static final long WINDOW_SECONDS = 60;
	private static final int HEAD_EXPIRY_BUDGET = 4;

	private final int limit;
	private final Integer dailyLimit;
	private final int maxKeys;
	private final Clock clock;
	private final ZoneId dayZone;
	private final LinkedHashMap<String, WindowCounter> counters;

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
		this.maxKeys = Math.min(maxKeys, MAX_KEYS);
		this.clock = clock;
		this.dayZone = dayZone;
		this.counters = new LinkedHashMap<>(Math.min(this.maxKeys, 256), 0.75f, true);
	}

	synchronized AcquireResult acquire(String identity, int cost) {
		if (cost < 1) throw new IllegalArgumentException("train-search request cost must be positive");
		Instant currentInstant = Instant.now(clock);
		long now = currentInstant.getEpochSecond();
		long window = now - Math.floorMod(now, WINDOW_SECONDS);
		LocalDate currentDate = currentInstant.atZone(dayZone).toLocalDate();
		long day = currentDate.toEpochDay();
		boolean trackDaily = dailyLimit != null;

		evictExpiredHead(window, day, trackDaily, HEAD_EXPIRY_BUDGET);
		WindowCounter counter = counterFor(identity, window, day, trackDaily);

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
			|| (decision == WindowCounter.Decision.CARDINALITY_DENIED && trackDaily);
		long retryAfter = retriesNextDay ? dailyRetryAfter : minuteRetryAfter;
		return decision == WindowCounter.Decision.ALLOWED
			? new AcquireResult(true, 0)
			: new AcquireResult(false, retryAfter);
	}

	int maxKeys() {
		return maxKeys;
	}

	int currentSize() {
		synchronized (this) {
			return counters.size();
		}
	}

	private WindowCounter counterFor(String identity, long window, long day, boolean trackDaily) {
		WindowCounter existing = counters.get(identity);
		if (existing != null) return existing;
		if (counters.size() >= maxKeys) {
			evictExpiredHead(window, day, trackDaily, maxKeys);
			if (counters.size() >= maxKeys) return null;
		}
		WindowCounter created = new WindowCounter(window, day);
		counters.put(identity, created);
		return created;
	}

	private void evictExpiredHead(long currentWindow, long currentDay, boolean trackDaily, int maxRemovals) {
		int removed = 0;
		Iterator<Map.Entry<String, WindowCounter>> iterator = counters.entrySet().iterator();
		while (iterator.hasNext() && removed < maxRemovals) {
			WindowCounter eldest = iterator.next().getValue();
			if (!eldest.isExpired(currentWindow, currentDay, trackDaily)) {
				break;
			}
			iterator.remove();
			removed++;
		}
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

		private boolean isExpired(long currentWindow, long currentDay, boolean trackDaily) {
			return trackDaily ? day < currentDay : window < currentWindow;
		}

		private Decision acquire(
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

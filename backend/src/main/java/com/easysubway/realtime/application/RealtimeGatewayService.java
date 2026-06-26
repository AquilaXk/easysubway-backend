package com.easysubway.realtime.application;

import com.easysubway.realtime.domain.RealtimeArrival;
import com.easysubway.realtime.domain.RealtimeTrainPosition;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RealtimeGatewayService {

	private static final Duration CACHE_TTL = Duration.ofSeconds(20);
	private static final Duration STALE_TTL = Duration.ofSeconds(120);
	private static final Duration PROVIDER_FRESHNESS_TTL = Duration.ofSeconds(90);
	private static final Duration QUOTA_CIRCUIT_OPEN = Duration.ofSeconds(60);
	private static final ZoneId PROVIDER_ZONE = ZoneId.of("Asia/Seoul");
	private static final DateTimeFormatter PROVIDER_TIMESTAMP_FORMATTER =
		DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	private static final String SANGNOKSU_STATION_ID = "station-sangnoksu";
	private static final String SANGNOKSU_STATION_NAME = "상록수";
	private static final String LINE_ID = "4";
	private static final String LINE_NAME = "4호선";
	private static final String PROVIDER_LINE_ID = "1004";

	private final RealtimeProvider provider;
	private final Clock clock;
	private final Map<String, CachedArrival> arrivalCache = new ConcurrentHashMap<>();
	private final Map<String, CachedTrainPosition> trainPositionCache = new ConcurrentHashMap<>();
	private final Map<String, CompletableFuture<RealtimeArrivalResult>> arrivalRequests = new ConcurrentHashMap<>();
	private final Map<String, CompletableFuture<RealtimeTrainPositionResult>> trainPositionRequests = new ConcurrentHashMap<>();
	private volatile java.time.Instant quotaCircuitOpenUntil;

	@Autowired
	public RealtimeGatewayService(RealtimeProvider provider) {
		this(provider, Clock.systemUTC());
	}

	RealtimeGatewayService(RealtimeProvider provider, Clock clock) {
		this.provider = provider;
		this.clock = clock;
	}

	public RealtimeArrivalResult arrivals(RealtimeQuery query) {
		RealtimeQuery normalizedQuery = normalizeArrivalQuery(query);
		if (normalizedQuery == null) {
			return RealtimeArrivalResult.unsupported(
				"UNSUPPORTED_REGION",
				"서울 TOPIS 실시간 지원 범위 밖입니다."
			);
		}
		String cacheKey = "ARRIVALS:%s:%s:%s".formatted(
			normalizedQuery.providerLineId(),
			normalizedQuery.stationId(),
			normalizedQuery.stationQueryName()
		);
		CachedArrival cached = arrivalCache.get(cacheKey);
		if (cached != null && isFresh(cached.cachedAt())) {
			return cached.result();
		}
		if (isQuotaCircuitOpen()) {
			return cached != null && isStaleUsable(cached.cachedAt())
				? cached.result().stale()
				: RealtimeArrivalResult.unavailable("PROVIDER_QUOTA_EXCEEDED");
		}
		CompletableFuture<RealtimeArrivalResult> request = new CompletableFuture<>();
		CompletableFuture<RealtimeArrivalResult> existing = arrivalRequests.putIfAbsent(cacheKey, request);
		if (existing != null) {
			return joinArrival(existing);
		}
		try {
			RealtimeArrivalResult result = fetchArrivals(normalizedQuery, cacheKey, cached);
			request.complete(result);
			return result;
		} catch (RuntimeException exception) {
			request.completeExceptionally(exception);
			throw exception;
		} finally {
			arrivalRequests.remove(cacheKey, request);
		}
	}

	private RealtimeArrivalResult fetchArrivals(RealtimeQuery normalizedQuery, String cacheKey, CachedArrival cached) {
		try {
			List<RealtimeArrival> arrivals = provider.arrivals(normalizedQuery);
			if (arrivals.isEmpty()) {
				return RealtimeArrivalResult.unavailable("EMPTY_PROVIDER_RESULT");
			}
			Instant receivedAt = clock.instant();
			List<RealtimeArrival> freshArrivals = freshArrivals(arrivals, receivedAt);
			if (freshArrivals.isEmpty()) {
				return staleArrivalOrUnavailable(cached, "PROVIDER_ERROR");
			}
			RealtimeArrivalResult result = RealtimeArrivalResult.fresh(
				receivedAt.toString(),
				freshArrivals
			);
			arrivalCache.put(cacheKey, new CachedArrival(result, receivedAt));
			return result;
		} catch (RealtimeProviderException exception) {
			openQuotaCircuitIfNeeded(exception);
			return staleArrivalOrUnavailable(cached, exception.fallbackCode());
		}
	}

	public RealtimeTrainPositionResult trainPositions(RealtimeQuery query) {
		RealtimeQuery normalizedQuery = normalizeTrainPositionQuery(query);
		if (normalizedQuery == null) {
			return RealtimeTrainPositionResult.unsupported(
				"UNSUPPORTED_REGION",
				"서울 TOPIS 실시간 지원 범위 밖입니다."
			);
		}
		String cacheKey = "POSITIONS:%s:%s".formatted(normalizedQuery.providerLineId(), normalizedQuery.lineName());
		CachedTrainPosition cached = trainPositionCache.get(cacheKey);
		if (cached != null && isFresh(cached.cachedAt())) {
			return cached.result();
		}
		if (isQuotaCircuitOpen()) {
			return cached != null && isStaleUsable(cached.cachedAt())
				? cached.result().stale()
				: RealtimeTrainPositionResult.unavailable("PROVIDER_QUOTA_EXCEEDED");
		}
		CompletableFuture<RealtimeTrainPositionResult> request = new CompletableFuture<>();
		CompletableFuture<RealtimeTrainPositionResult> existing = trainPositionRequests.putIfAbsent(cacheKey, request);
		if (existing != null) {
			return joinTrainPosition(existing);
		}
		try {
			RealtimeTrainPositionResult result = fetchTrainPositions(normalizedQuery, cacheKey, cached);
			request.complete(result);
			return result;
		} catch (RuntimeException exception) {
			request.completeExceptionally(exception);
			throw exception;
		} finally {
			trainPositionRequests.remove(cacheKey, request);
		}
	}

	private RealtimeTrainPositionResult fetchTrainPositions(
		RealtimeQuery normalizedQuery,
		String cacheKey,
		CachedTrainPosition cached
	) {
		try {
			List<RealtimeTrainPosition> trainPositions = provider.trainPositions(normalizedQuery);
			if (trainPositions.isEmpty()) {
				return RealtimeTrainPositionResult.unavailable("EMPTY_PROVIDER_RESULT");
			}
			Instant receivedAt = clock.instant();
			List<RealtimeTrainPosition> freshTrainPositions = freshTrainPositions(trainPositions, receivedAt);
			if (freshTrainPositions.isEmpty()) {
				return staleTrainPositionOrUnavailable(cached, "PROVIDER_ERROR");
			}
			RealtimeTrainPositionResult result = RealtimeTrainPositionResult.fresh(
				receivedAt.toString(),
				freshTrainPositions
			);
			trainPositionCache.put(cacheKey, new CachedTrainPosition(result, receivedAt));
			return result;
		} catch (RealtimeProviderException exception) {
			openQuotaCircuitIfNeeded(exception);
			return staleTrainPositionOrUnavailable(cached, exception.fallbackCode());
		}
	}

	private RealtimeArrivalResult staleArrivalOrUnavailable(CachedArrival cached, String fallbackCode) {
		if (cached != null && isStaleUsable(cached.cachedAt())) {
			return cached.result().stale();
		}
		return RealtimeArrivalResult.unavailable(fallbackCode);
	}

	private RealtimeTrainPositionResult staleTrainPositionOrUnavailable(
		CachedTrainPosition cached,
		String fallbackCode
	) {
		if (cached != null && isStaleUsable(cached.cachedAt())) {
			return cached.result().stale();
		}
		return RealtimeTrainPositionResult.unavailable(fallbackCode);
	}

	private List<RealtimeArrival> freshArrivals(List<RealtimeArrival> arrivals, Instant receivedAt) {
		List<RealtimeArrival> freshArrivals = new ArrayList<>();
		for (RealtimeArrival arrival : arrivals) {
			Instant providerReceivedAt = parseProviderReceivedAt(arrival.providerReceivedAt());
			if (providerReceivedAt == null || !isProviderFresh(providerReceivedAt, receivedAt)) {
				continue;
			}
			freshArrivals.add(adjustArrivalEta(arrival, providerReceivedAt, receivedAt));
		}
		return List.copyOf(freshArrivals);
	}

	private List<RealtimeTrainPosition> freshTrainPositions(
		List<RealtimeTrainPosition> trainPositions,
		Instant receivedAt
	) {
		List<RealtimeTrainPosition> freshTrainPositions = new ArrayList<>();
		for (RealtimeTrainPosition trainPosition : trainPositions) {
			Instant providerReceivedAt = parseProviderReceivedAt(trainPosition.providerReceivedAt());
			if (providerReceivedAt == null || !isProviderFresh(providerReceivedAt, receivedAt)) {
				continue;
			}
			freshTrainPositions.add(trainPosition);
		}
		return List.copyOf(freshTrainPositions);
	}

	private RealtimeArrival adjustArrivalEta(RealtimeArrival arrival, Instant providerReceivedAt, Instant receivedAt) {
		Integer etaSeconds = arrival.etaSeconds();
		if (etaSeconds == null) {
			return arrival;
		}
		long delaySeconds = Math.max(0, Duration.between(providerReceivedAt, receivedAt).toSeconds());
		int adjustedEtaSeconds = (int) Math.max(0, etaSeconds - delaySeconds);
		return new RealtimeArrival(
			arrival.lineId(),
			arrival.stationName(),
			arrival.destination(),
			arrival.direction(),
			arrival.trainNo(),
			adjustedEtaSeconds,
			arrivalMessage(adjustedEtaSeconds),
			arrival.positionMessage(),
			arrival.providerReceivedAt()
		);
	}

	private String arrivalMessage(int etaSeconds) {
		if (etaSeconds <= 0) {
			return "곧 도착";
		}
		if (etaSeconds < 60) {
			return "1분 이내";
		}
		return "%d분 후".formatted((etaSeconds + 59) / 60);
	}

	private boolean isProviderFresh(Instant providerReceivedAt, Instant receivedAt) {
		return Duration.between(providerReceivedAt, receivedAt).compareTo(PROVIDER_FRESHNESS_TTL) <= 0;
	}

	private Instant parseProviderReceivedAt(String providerReceivedAt) {
		if (providerReceivedAt == null || providerReceivedAt.isBlank()) {
			return null;
		}
		try {
			return Instant.parse(providerReceivedAt);
		} catch (DateTimeParseException ignored) {
			try {
				return LocalDateTime.parse(providerReceivedAt, PROVIDER_TIMESTAMP_FORMATTER)
					.atZone(PROVIDER_ZONE)
					.toInstant();
			} catch (DateTimeParseException exception) {
				return null;
			}
		}
	}

	private RealtimeArrivalResult joinArrival(CompletableFuture<RealtimeArrivalResult> request) {
		try {
			return request.join();
		} catch (CompletionException exception) {
			throw unwrapCompletionException(exception);
		}
	}

	private RealtimeTrainPositionResult joinTrainPosition(CompletableFuture<RealtimeTrainPositionResult> request) {
		try {
			return request.join();
		} catch (CompletionException exception) {
			throw unwrapCompletionException(exception);
		}
	}

	private RuntimeException unwrapCompletionException(CompletionException exception) {
		Throwable cause = exception.getCause();
		if (cause instanceof RuntimeException runtimeException) {
			return runtimeException;
		}
		return exception;
	}

	private RealtimeQuery normalizeArrivalQuery(RealtimeQuery query) {
		if (!SANGNOKSU_STATION_ID.equals(query.stationId()) || !SANGNOKSU_STATION_NAME.equals(query.stationQueryName())) {
			return null;
		}
		if (!isBlankOrOneOf(query.lineId(), LINE_ID, "seoul-4")) {
			return null;
		}
		if (!isBlankOrOneOf(query.providerLineId(), PROVIDER_LINE_ID, "448")) {
			return null;
		}
		return new RealtimeQuery(
			SANGNOKSU_STATION_ID,
			LINE_ID,
			PROVIDER_LINE_ID,
			SANGNOKSU_STATION_NAME,
			LINE_NAME
		);
	}

	private RealtimeQuery normalizeTrainPositionQuery(RealtimeQuery query) {
		if (!LINE_NAME.equals(query.lineName())) {
			return null;
		}
		if (!isBlankOrOneOf(query.lineId(), LINE_ID, "seoul-4")) {
			return null;
		}
		if (!isBlankOrOneOf(query.providerLineId(), PROVIDER_LINE_ID)) {
			return null;
		}
		return new RealtimeQuery(
			SANGNOKSU_STATION_ID,
			LINE_ID,
			PROVIDER_LINE_ID,
			SANGNOKSU_STATION_NAME,
			LINE_NAME
		);
	}

	private boolean isBlankOrOneOf(String value, String... allowedValues) {
		if (value == null || value.isBlank()) {
			return true;
		}
		for (String allowedValue : allowedValues) {
			if (allowedValue.equals(value)) {
				return true;
			}
		}
		return false;
	}

	private boolean isFresh(java.time.Instant cachedAt) {
		return Duration.between(cachedAt, clock.instant()).compareTo(CACHE_TTL) <= 0;
	}

	private boolean isStaleUsable(java.time.Instant cachedAt) {
		return Duration.between(cachedAt, clock.instant()).compareTo(STALE_TTL) <= 0;
	}

	private boolean isQuotaCircuitOpen() {
		java.time.Instant openUntil = quotaCircuitOpenUntil;
		return openUntil != null && clock.instant().isBefore(openUntil);
	}

	private void openQuotaCircuitIfNeeded(RealtimeProviderException exception) {
		if ("PROVIDER_QUOTA_EXCEEDED".equals(exception.fallbackCode())) {
			quotaCircuitOpenUntil = clock.instant().plus(QUOTA_CIRCUIT_OPEN);
		}
	}

	private record CachedArrival(RealtimeArrivalResult result, java.time.Instant cachedAt) {
	}

	private record CachedTrainPosition(RealtimeTrainPositionResult result, java.time.Instant cachedAt) {
	}
}

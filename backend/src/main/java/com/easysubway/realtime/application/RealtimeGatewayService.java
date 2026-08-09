package com.easysubway.realtime.application;

import com.easysubway.realtime.application.port.out.RealtimeArrivalArchivePort;
import com.easysubway.realtime.application.port.out.RealtimeMappingPort;
import com.easysubway.realtime.application.port.out.RealtimeProviderCallQuotaPort;
import com.easysubway.realtime.domain.RealtimeArrival;
import com.easysubway.realtime.domain.RealtimeArrivalObservation;
import com.easysubway.realtime.domain.RealtimeMapping;
import com.easysubway.realtime.domain.RealtimeStatus;
import com.easysubway.realtime.domain.RealtimeTrainPosition;
import com.easysubway.realtime.domain.RealtimeTripMapping;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RealtimeGatewayService {
	private static final Logger log = LoggerFactory.getLogger(RealtimeGatewayService.class);

	private static final Duration CACHE_TTL = Duration.ofSeconds(20);
	private static final Duration PROVIDER_FRESHNESS_TTL = Duration.ofSeconds(90);
	private static final Duration ARRIVAL_ARCHIVE_RETENTION = Duration.ofDays(30);
	private static final Duration QUOTA_CIRCUIT_OPEN = Duration.ofSeconds(60);
	private static final int DEFAULT_PROVIDER_CALL_LIMIT_PER_MINUTE = 1;
	private static final int DEFAULT_PROVIDER_CALL_LIMIT_PER_DAY = 800;
	private static final int MAX_PROVIDER_CALL_LIMIT_PER_MINUTE = 1;
	private static final int MAX_PROVIDER_CALL_LIMIT_PER_DAY = 800;
	private static final ZoneId PROVIDER_ZONE = ZoneId.of("Asia/Seoul");
	private static final DateTimeFormatter PROVIDER_TIMESTAMP_FORMATTER =
		DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	private static final String PROVIDER_ID = "seoul-topis";
	private static final Set<String> PUBLIC_UNAVAILABLE_CAUSE_ALLOWLIST = Set.of(
		"EMPTY_PROVIDER_RESULT",
		"PROVIDER_ERROR",
		"PROVIDER_QUOTA_EXCEEDED",
		"PROVIDER_RATE_LIMITED",
		"PROVIDER_TIMEOUT",
		"PROVIDER_UNAVAILABLE"
	);

	private final RealtimeProvider provider;
	private final RealtimeMappingPort mappingPort;
	private final RealtimeArrivalArchivePort arrivalArchivePort;
	private final Clock clock;
	private final RealtimeProviderControl providerControl;
	private final RealtimeProviderCallQuotaPort providerCallQuotaPort;
	private final Executor archiveExecutor;
	private final int providerCallLimitPerMinute;
	private final int providerCallLimitPerDay;
	private final ProviderMetrics providerMetrics = new ProviderMetrics();
	private final Map<String, CachedArrival> arrivalCache = new ConcurrentHashMap<>();
	private final Map<String, CachedTrainPosition> trainPositionCache = new ConcurrentHashMap<>();
	private final Map<String, CompletableFuture<RealtimeArrivalResult>> arrivalRequests = new ConcurrentHashMap<>();
	private final Map<String, CompletableFuture<RealtimeTrainPositionResult>> trainPositionRequests = new ConcurrentHashMap<>();
	private volatile java.time.Instant quotaCircuitOpenUntil;

	@Autowired
	public RealtimeGatewayService(
		RealtimeProvider provider,
		RealtimeMappingPort mappingPort,
		RealtimeProviderControl providerControl,
		RealtimeArrivalArchivePort arrivalArchivePort,
		RealtimeProviderCallQuotaPort providerCallQuotaPort,
		@Qualifier("realtimeArchiveExecutor") Executor archiveExecutor,
		@Value("${EASYSUBWAY_SEOUL_TOPIS_CALL_LIMIT_PER_MINUTE:1}") int providerCallLimitPerMinute,
		@Value("${EASYSUBWAY_SEOUL_TOPIS_CALL_LIMIT_PER_DAY:800}") int providerCallLimitPerDay
	) {
		this(
			provider,
			Clock.systemUTC(),
			mappingPort,
			providerControl,
			arrivalArchivePort,
			providerCallQuotaPort,
			providerCallLimitPerMinute,
			providerCallLimitPerDay,
			archiveExecutor
		);
	}

	RealtimeGatewayService(RealtimeProvider provider, Clock clock, RealtimeMappingPort mappingPort) {
		this(provider, clock, mappingPort, new RealtimeProviderControl());
	}

	RealtimeGatewayService(
		RealtimeProvider provider,
		Clock clock,
		RealtimeMappingPort mappingPort,
		RealtimeArrivalArchivePort arrivalArchivePort
	) {
		this(
			provider,
			clock,
			mappingPort,
			new RealtimeProviderControl(),
			arrivalArchivePort,
			DEFAULT_PROVIDER_CALL_LIMIT_PER_MINUTE,
			DEFAULT_PROVIDER_CALL_LIMIT_PER_DAY
		);
	}

	RealtimeGatewayService(
		RealtimeProvider provider,
		Clock clock,
		RealtimeMappingPort mappingPort,
		RealtimeProviderControl providerControl
	) {
		this(provider, clock, mappingPort, providerControl, DEFAULT_PROVIDER_CALL_LIMIT_PER_MINUTE);
	}

	RealtimeGatewayService(
		RealtimeProvider provider,
		Clock clock,
		RealtimeMappingPort mappingPort,
		RealtimeProviderControl providerControl,
		int providerCallLimitPerMinute
	) {
		this(provider, clock, mappingPort, providerControl, providerCallLimitPerMinute, DEFAULT_PROVIDER_CALL_LIMIT_PER_DAY);
	}

	RealtimeGatewayService(
		RealtimeProvider provider,
		Clock clock,
		RealtimeMappingPort mappingPort,
		RealtimeProviderControl providerControl,
		int providerCallLimitPerMinute,
		int providerCallLimitPerDay
	) {
		this(
			provider,
			clock,
			mappingPort,
			providerControl,
			RealtimeArrivalArchivePort.NO_OP,
			providerCallLimitPerMinute,
			providerCallLimitPerDay
		);
	}

	RealtimeGatewayService(
		RealtimeProvider provider,
		Clock clock,
		RealtimeMappingPort mappingPort,
		RealtimeProviderControl providerControl,
		RealtimeArrivalArchivePort arrivalArchivePort,
		int providerCallLimitPerMinute,
		int providerCallLimitPerDay
	) {
		this(
			provider,
			clock,
			mappingPort,
			providerControl,
			arrivalArchivePort,
			new ProviderCallRateLimiter(),
			providerCallLimitPerMinute,
			providerCallLimitPerDay
		);
	}

	RealtimeGatewayService(
		RealtimeProvider provider,
		Clock clock,
		RealtimeMappingPort mappingPort,
		RealtimeProviderControl providerControl,
		RealtimeArrivalArchivePort arrivalArchivePort,
		RealtimeProviderCallQuotaPort providerCallQuotaPort,
		int providerCallLimitPerMinute,
		int providerCallLimitPerDay
	) {
		this(
			provider,
			clock,
			mappingPort,
			providerControl,
			arrivalArchivePort,
			providerCallQuotaPort,
			providerCallLimitPerMinute,
			providerCallLimitPerDay,
			Runnable::run
		);
	}

	RealtimeGatewayService(
		RealtimeProvider provider,
		Clock clock,
		RealtimeMappingPort mappingPort,
		RealtimeProviderControl providerControl,
		RealtimeArrivalArchivePort arrivalArchivePort,
		RealtimeProviderCallQuotaPort providerCallQuotaPort,
		int providerCallLimitPerMinute,
		int providerCallLimitPerDay,
		Executor archiveExecutor
	) {
		this.provider = provider;
		this.clock = clock;
		this.mappingPort = mappingPort;
		this.providerControl = providerControl;
		this.arrivalArchivePort = arrivalArchivePort;
		this.providerCallQuotaPort = providerCallQuotaPort;
		this.archiveExecutor = Objects.requireNonNull(archiveExecutor, "archiveExecutor must not be null");
		this.providerCallLimitPerMinute = Math.min(
			MAX_PROVIDER_CALL_LIMIT_PER_MINUTE,
			Math.max(1, providerCallLimitPerMinute)
		);
		this.providerCallLimitPerDay = Math.min(MAX_PROVIDER_CALL_LIMIT_PER_DAY, Math.max(1, providerCallLimitPerDay));
	}

	public RealtimeArrivalResult arrivals(RealtimeQuery query) {
		NormalizedRealtimeQuery normalizedQuery = normalizeArrivalQuery(query);
		if (normalizedQuery.rejected()) {
			return recordArrivalResult(RealtimeArrivalResult.unsupported(
				normalizedQuery.fallbackCode(),
				"서울 TOPIS 실시간 지원 범위 밖입니다."
			));
		}
		if (!providerControl.providerEnabled(PROVIDER_ID)) {
			return recordArrivalResult(RealtimeArrivalResult.unsupported(
				"PROVIDER_DISABLED",
				"실시간 provider가 운영자 설정으로 일시 중지되었습니다."
			));
		}
		String cacheKey = "ARRIVALS:%s:%s:%s".formatted(
			normalizedQuery.query().providerLineId(),
			normalizedQuery.query().stationId(),
			normalizedQuery.query().stationQueryName()
		);
		cacheKey = "%s:%d".formatted(cacheKey, normalizedQuery.cacheVersion());
		CachedArrival cached = arrivalCache.get(cacheKey);
		if (cached != null && canReuseCache(arrivalCache, cacheKey, cached, cached.cachedAt())) {
			return recordArrivalResult(cached.result());
		}
		if (isQuotaCircuitOpen()) {
			return recordArrivalResult(RealtimeArrivalResult.unavailable("PROVIDER_QUOTA_EXCEEDED"));
		}
		CompletableFuture<RealtimeArrivalResult> request = new CompletableFuture<>();
		CompletableFuture<RealtimeArrivalResult> existing = arrivalRequests.putIfAbsent(cacheKey, request);
		if (existing != null) {
			return recordArrivalResult(joinArrival(existing));
		}
		try {
			ProviderCallQuotaDecision quotaDecision = tryAcquireProviderCall();
			if (quotaDecision != ProviderCallQuotaDecision.ACQUIRED) {
				String fallbackCode = quotaDecision == ProviderCallQuotaDecision.UNAVAILABLE
					? "PROVIDER_UNAVAILABLE"
					: "PROVIDER_RATE_LIMITED";
				RealtimeArrivalResult result = RealtimeArrivalResult.unavailable(fallbackCode);
				request.complete(result);
				return recordArrivalResult(result);
			}
			RealtimeArrivalResult result = fetchArrivals(normalizedQuery, cacheKey);
			request.complete(result);
			return recordArrivalResult(result);
		} catch (RuntimeException exception) {
			request.completeExceptionally(exception);
			throw exception;
		} finally {
			arrivalRequests.remove(cacheKey, request);
		}
	}

	private RealtimeArrivalResult fetchArrivals(NormalizedRealtimeQuery normalizedQuery, String cacheKey) {
		Instant providerCallStartedAt = clock.instant();
		try {
			List<RealtimeArrival> arrivals = provider.arrivals(normalizedQuery.query());
			if (arrivals.isEmpty()) {
				providerMetrics.recordEmptyResult();
				return RealtimeArrivalResult.unavailable("EMPTY_PROVIDER_RESULT");
			}
			Instant receivedAt = clock.instant();
			ProcessedArrivals processed = freshArrivals(arrivals, receivedAt, normalizedQuery);
			if (processed.arrivals().isEmpty()) {
				return RealtimeArrivalResult.unavailable("PROVIDER_ERROR");
			}
			dispatchArchiveArrivals(processed.observations());
			RealtimeArrivalResult result = RealtimeArrivalResult.fresh(
				receivedAt.toString(),
				processed.arrivals()
			);
			arrivalCache.put(cacheKey, new CachedArrival(result, receivedAt));
			return result;
		} catch (RealtimeProviderException exception) {
			String providerCause = exception.providerCause();
			String publicUnavailableCause = publicUnavailableCause(providerCause);
			providerMetrics.recordProviderException(publicUnavailableCause);
			openQuotaCircuitIfNeeded(providerCause);
			return RealtimeArrivalResult.unavailable(publicUnavailableCause);
		} finally {
			providerMetrics.recordProviderCall(Duration.between(providerCallStartedAt, clock.instant()));
		}
	}

	public RealtimeTrainPositionResult trainPositions(RealtimeQuery query) {
		NormalizedRealtimeQuery normalizedQuery = normalizeTrainPositionQuery(query);
		if (normalizedQuery.rejected()) {
			return recordTrainPositionResult(RealtimeTrainPositionResult.unsupported(
				normalizedQuery.fallbackCode(),
				"서울 TOPIS 실시간 지원 범위 밖입니다."
			));
		}
		if (!providerControl.providerEnabled(PROVIDER_ID)) {
			return recordTrainPositionResult(RealtimeTrainPositionResult.unsupported(
				"PROVIDER_DISABLED",
				"실시간 provider가 운영자 설정으로 일시 중지되었습니다."
			));
		}
		String cacheKey = "POSITIONS:%s:%s:%d".formatted(
			normalizedQuery.query().providerLineId(),
			normalizedQuery.query().lineName(),
			normalizedQuery.cacheVersion()
		);
		CachedTrainPosition cached = trainPositionCache.get(cacheKey);
		if (cached != null && canReuseCache(trainPositionCache, cacheKey, cached, cached.cachedAt())) {
			return recordTrainPositionResult(cached.result());
		}
		if (isQuotaCircuitOpen()) {
			return recordTrainPositionResult(RealtimeTrainPositionResult.unavailable("PROVIDER_QUOTA_EXCEEDED"));
		}
		CompletableFuture<RealtimeTrainPositionResult> request = new CompletableFuture<>();
		CompletableFuture<RealtimeTrainPositionResult> existing = trainPositionRequests.putIfAbsent(cacheKey, request);
		if (existing != null) {
			return recordTrainPositionResult(joinTrainPosition(existing));
		}
		try {
			ProviderCallQuotaDecision quotaDecision = tryAcquireProviderCall();
			if (quotaDecision != ProviderCallQuotaDecision.ACQUIRED) {
				String fallbackCode = quotaDecision == ProviderCallQuotaDecision.UNAVAILABLE
					? "PROVIDER_UNAVAILABLE"
					: "PROVIDER_RATE_LIMITED";
				RealtimeTrainPositionResult result = RealtimeTrainPositionResult.unavailable(fallbackCode);
				request.complete(result);
				return recordTrainPositionResult(result);
			}
			RealtimeTrainPositionResult result = fetchTrainPositions(normalizedQuery.query(), cacheKey);
			request.complete(result);
			return recordTrainPositionResult(result);
		} catch (RuntimeException exception) {
			request.completeExceptionally(exception);
			throw exception;
		} finally {
			trainPositionRequests.remove(cacheKey, request);
		}
	}

	private RealtimeTrainPositionResult fetchTrainPositions(
		RealtimeQuery normalizedQuery,
		String cacheKey
	) {
		Instant providerCallStartedAt = clock.instant();
		try {
			List<RealtimeTrainPosition> trainPositions = provider.trainPositions(normalizedQuery);
			if (trainPositions.isEmpty()) {
				providerMetrics.recordEmptyResult();
				return RealtimeTrainPositionResult.unavailable("EMPTY_PROVIDER_RESULT");
			}
			Instant receivedAt = clock.instant();
			List<RealtimeTrainPosition> freshTrainPositions = freshTrainPositions(trainPositions, receivedAt);
			if (freshTrainPositions.isEmpty()) {
				return RealtimeTrainPositionResult.unavailable("PROVIDER_ERROR");
			}
			RealtimeTrainPositionResult result = RealtimeTrainPositionResult.fresh(
				receivedAt.toString(),
				freshTrainPositions
			);
			trainPositionCache.put(cacheKey, new CachedTrainPosition(result, receivedAt));
			return result;
		} catch (RealtimeProviderException exception) {
			String providerCause = exception.providerCause();
			String publicUnavailableCause = publicUnavailableCause(providerCause);
			providerMetrics.recordProviderException(publicUnavailableCause);
			openQuotaCircuitIfNeeded(providerCause);
			return RealtimeTrainPositionResult.unavailable(publicUnavailableCause);
		} finally {
			providerMetrics.recordProviderCall(Duration.between(providerCallStartedAt, clock.instant()));
		}
	}

	public RealtimeProviderHealthSnapshot providerHealthSnapshot() {
		RealtimeProviderControl.RealtimeProviderSwitchState switchState = providerControl.switchState(PROVIDER_ID);
		return providerMetrics.snapshot(
			PROVIDER_ID,
			switchState.enabled(),
			switchState.disabledReason()
		);
	}

	private RealtimeArrivalResult recordArrivalResult(RealtimeArrivalResult result) {
		providerMetrics.recordResult(result.status());
		return result;
	}

	private RealtimeTrainPositionResult recordTrainPositionResult(RealtimeTrainPositionResult result) {
		providerMetrics.recordResult(result.status());
		return result;
	}

	private ProviderCallQuotaDecision tryAcquireProviderCall() {
		try {
			return providerCallQuotaPort.tryAcquire(
				PROVIDER_ID,
				clock.instant(),
				PROVIDER_ZONE,
				providerCallLimitPerMinute,
				providerCallLimitPerDay
			) ? ProviderCallQuotaDecision.ACQUIRED : ProviderCallQuotaDecision.DENIED;
		} catch (RuntimeException exception) {
			log.warn("Realtime provider quota store unavailable. providerId={}", PROVIDER_ID, exception);
			return ProviderCallQuotaDecision.UNAVAILABLE;
		}
	}

	private ProcessedArrivals freshArrivals(
		List<RealtimeArrival> arrivals,
		Instant receivedAt,
		NormalizedRealtimeQuery normalizedQuery
	) {
		List<RealtimeArrival> freshArrivals = new ArrayList<>();
		List<RealtimeArrivalObservation> observations = new ArrayList<>();
		for (RealtimeArrival arrival : arrivals) {
			Instant providerReceivedAt = parseProviderReceivedAt(arrival.providerReceivedAt());
			if (providerReceivedAt == null || !isProviderFresh(providerReceivedAt, receivedAt)) {
				continue;
			}
			// provider 로컬(예: TOPIS KST "yyyy-MM-dd HH:mm:ss") timestamp를 경계에서 ISO(Instant)로
			// 정규화해 하류 소비처(route resolver의 Instant.parse)가 파싱 가능하게 한다. 원문 포맷 누출 차단.
			RealtimeArrival normalized = arrival.withProviderReceivedAt(providerReceivedAt.toString());
			RealtimeArrival adjusted = adjustArrivalEta(normalized, providerReceivedAt, receivedAt);
			RealtimeArrival canonical = canonicalizeArrival(adjusted, normalizedQuery.query());
			freshArrivals.add(canonical);
			try {
				observations.add(new RealtimeArrivalObservation(
					PROVIDER_ID,
					normalizedQuery.query().stationId(),
					normalizedQuery.query().lineId(),
					normalizedQuery.query().providerLineId(),
					normalizedQuery.providerStationId(),
					arrival.trainNo(),
					providerReceivedAt,
					receivedAt,
					arrival.etaSeconds(),
					canonical.etaSeconds(),
					arrival.rawDirection(),
					arrival.rawDestination(),
					receivedAt.plus(ARRIVAL_ARCHIVE_RETENTION)
				));
			} catch (RuntimeException exception) {
				providerMetrics.recordArchiveFailure();
				log.warn(
					"Realtime arrival observation rejected. providerId={}, stationId={}",
					PROVIDER_ID,
					normalizedQuery.query().stationId(),
					exception
				);
			}
		}
		return new ProcessedArrivals(List.copyOf(freshArrivals), List.copyOf(observations));
	}

	private void archiveArrivals(List<RealtimeArrivalObservation> observations) {
		if (observations.isEmpty()) {
			return;
		}
		try {
			arrivalArchivePort.saveAll(observations);
		} catch (RuntimeException exception) {
			providerMetrics.recordArchiveFailure();
			log.warn(
				"Realtime arrival archive failed. providerId={}, observationCount={}",
				PROVIDER_ID,
				observations.size(),
				exception
			);
		}
	}

	private void dispatchArchiveArrivals(List<RealtimeArrivalObservation> observations) {
		if (observations.isEmpty()) {
			return;
		}
		try {
			archiveExecutor.execute(() -> archiveArrivals(observations));
		} catch (RuntimeException exception) {
			providerMetrics.recordArchiveFailure();
			log.warn(
				"Realtime arrival archive dispatch failed. providerId={}, observationCount={}",
				PROVIDER_ID,
				observations.size(),
				exception
			);
		}
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
		return arrival.withEtaAndMessage(adjustedEtaSeconds, arrivalMessage(adjustedEtaSeconds));
	}

	private RealtimeArrival canonicalizeArrival(RealtimeArrival arrival, RealtimeQuery normalizedQuery) {
		return mappingPort.findTripMapping(
			PROVIDER_ID,
			normalizedQuery.lineId(),
			normalizedQuery.providerLineId(),
			arrival.rawDirection(),
			arrival.rawDestination(),
			arrival.rawServicePattern()
		).map((mapping) -> mappedArrival(arrival, mapping))
			.orElseGet(() -> {
				providerMetrics.recordTripMappingFailure();
				return arrival;
			});
	}

	private RealtimeArrival mappedArrival(RealtimeArrival arrival, RealtimeTripMapping mapping) {
		return arrival.withCanonical(
			mapping.canonicalDestination(arrival.destination()),
			mapping.canonicalDirection(arrival.direction()),
			mapping.canonicalServicePattern(arrival.servicePattern())
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
		Duration age = Duration.between(providerReceivedAt, receivedAt);
		return !age.isNegative() && age.compareTo(PROVIDER_FRESHNESS_TTL) <= 0;
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

	private NormalizedRealtimeQuery normalizeArrivalQuery(RealtimeQuery query) {
		return mappingPort.findArrivalMapping(PROVIDER_ID, query)
			.map((mapping) -> normalizeArrivalMapping(query, mapping))
			.orElseGet(() -> NormalizedRealtimeQuery.rejected("MAPPING_MISSING"));
	}

	private NormalizedRealtimeQuery normalizeArrivalMapping(RealtimeQuery query, RealtimeMapping mapping) {
		if (!providerLineMatches(query, mapping)) {
			return NormalizedRealtimeQuery.rejected("MAPPING_MISSING");
		}
		if (!mapping.supportsArrivals()) {
			return NormalizedRealtimeQuery.rejected("UNSUPPORTED_CAPABILITY");
		}
		if (!mapping.liveEligible()) {
			return NormalizedRealtimeQuery.rejected(mapping.ineligibleReason());
		}
		return NormalizedRealtimeQuery.mapped(new RealtimeQuery(
			mapping.stationId(),
			mapping.lineId(),
			mapping.providerLineId(),
			mapping.effectiveQueryName(query.stationQueryName()),
			mapping.effectiveProviderLineName(query.lineName())
		), mapping.cacheVersion(), mapping.providerStationId());
	}

	private NormalizedRealtimeQuery normalizeTrainPositionQuery(RealtimeQuery query) {
		return mappingPort.findTrainPositionMapping(PROVIDER_ID, query)
			.map((mapping) -> normalizeTrainPositionMapping(query, mapping))
			.orElseGet(() -> NormalizedRealtimeQuery.rejected("MAPPING_MISSING"));
	}

	private NormalizedRealtimeQuery normalizeTrainPositionMapping(RealtimeQuery query, RealtimeMapping mapping) {
		if (!providerLineMatches(query, mapping)) {
			return NormalizedRealtimeQuery.rejected("MAPPING_MISSING");
		}
		if (!mapping.supportsTrainPositions()) {
			return NormalizedRealtimeQuery.rejected("UNSUPPORTED_CAPABILITY");
		}
		if (!mapping.liveEligible()) {
			return NormalizedRealtimeQuery.rejected(mapping.ineligibleReason());
		}
		return NormalizedRealtimeQuery.mapped(new RealtimeQuery(
			mapping.stationId(),
			mapping.lineId(),
			mapping.providerLineId(),
			mapping.effectiveQueryName(query.stationQueryName()),
			mapping.effectiveProviderLineName(query.lineName())
		), mapping.cacheVersion(), mapping.providerStationId());
	}

	private boolean providerLineMatches(RealtimeQuery query, RealtimeMapping mapping) {
		return mapping.matchesProviderLine(query.providerLineId());
	}

	private <T> boolean canReuseCache(Map<String, T> cache, String cacheKey, T cached, java.time.Instant cachedAt) {
		Duration age = Duration.between(cachedAt, clock.instant());
		if (age.isNegative()) {
			cache.remove(cacheKey, cached);
			return false;
		}
		return age.compareTo(CACHE_TTL) <= 0;
	}

	private boolean isQuotaCircuitOpen() {
		java.time.Instant openUntil = quotaCircuitOpenUntil;
		return openUntil != null && clock.instant().isBefore(openUntil);
	}

	private void openQuotaCircuitIfNeeded(String providerCause) {
		if ("PROVIDER_QUOTA_EXCEEDED".equals(providerCause)) {
			quotaCircuitOpenUntil = clock.instant().plus(QUOTA_CIRCUIT_OPEN);
		}
	}

	private String publicUnavailableCause(String providerCause) {
		return providerCause != null && PUBLIC_UNAVAILABLE_CAUSE_ALLOWLIST.contains(providerCause)
			? providerCause
			: "PROVIDER_ERROR";
	}

	private static final class ProviderCallRateLimiter implements RealtimeProviderCallQuotaPort {
		private long windowMinute = Long.MIN_VALUE;
		private long windowDay = Long.MIN_VALUE;
		private int calls;
		private int dailyCalls;

		@Override
		public synchronized boolean tryAcquire(
			String providerId,
			Instant now,
			ZoneId providerZone,
			int limitPerMinute,
			int limitPerDay
		) {
			long minute = now.getEpochSecond() / 60;
			long day = now.atZone(providerZone).toLocalDate().toEpochDay();
			if (minute != windowMinute) {
				windowMinute = minute;
				calls = 0;
			}
			if (day != windowDay) {
				windowDay = day;
				dailyCalls = 0;
			}
			if (calls >= limitPerMinute || dailyCalls >= limitPerDay) {
				return false;
			}
			calls += 1;
			dailyCalls += 1;
			return true;
		}
	}

	private record CachedArrival(RealtimeArrivalResult result, java.time.Instant cachedAt) {
	}

	private record CachedTrainPosition(RealtimeTrainPositionResult result, java.time.Instant cachedAt) {
	}

	private static final class ProviderMetrics {
		private final AtomicLong providerCallCount = new AtomicLong();
		private final AtomicLong providerTimeoutCount = new AtomicLong();
		private final AtomicLong providerQuotaExceededCount = new AtomicLong();
		private final AtomicLong providerEmptyResultCount = new AtomicLong();
		private final AtomicLong tripMappingFailureCount = new AtomicLong();
		private final AtomicLong archiveFailureCount = new AtomicLong();
		private final AtomicLong providerLatencyMsTotal = new AtomicLong();
		private final AtomicLong resultCount = new AtomicLong();
		private final AtomicLong freshResultCount = new AtomicLong();
		private final AtomicLong staleResultCount = new AtomicLong();
		private final AtomicLong unsupportedResultCount = new AtomicLong();

		private void recordProviderCall(Duration latency) {
			providerCallCount.incrementAndGet();
			providerLatencyMsTotal.addAndGet(Math.max(0, latency.toMillis()));
		}

		private void recordProviderException(String publicUnavailableCause) {
			if ("PROVIDER_TIMEOUT".equals(publicUnavailableCause)) {
				providerTimeoutCount.incrementAndGet();
			}
			if ("PROVIDER_QUOTA_EXCEEDED".equals(publicUnavailableCause)) {
				providerQuotaExceededCount.incrementAndGet();
			}
		}

		private void recordEmptyResult() {
			providerEmptyResultCount.incrementAndGet();
		}

		private void recordTripMappingFailure() {
			tripMappingFailureCount.incrementAndGet();
		}

		private void recordArchiveFailure() {
			archiveFailureCount.incrementAndGet();
		}

		private void recordResult(RealtimeStatus status) {
			resultCount.incrementAndGet();
			if (status == RealtimeStatus.FRESH) {
				freshResultCount.incrementAndGet();
			}
			if (status == RealtimeStatus.UNSUPPORTED) {
				unsupportedResultCount.incrementAndGet();
			}
		}

		private RealtimeProviderHealthSnapshot snapshot(
			String providerId,
			boolean providerEnabled,
			String disabledReason
		) {
			long calls = providerCallCount.get();
			long results = resultCount.get();
			return new RealtimeProviderHealthSnapshot(
				providerId,
				providerEnabled,
				disabledReason,
				calls,
				providerTimeoutCount.get(),
				providerQuotaExceededCount.get(),
				providerEmptyResultCount.get(),
				tripMappingFailureCount.get(),
				archiveFailureCount.get(),
				ratio(freshResultCount.get(), results),
				ratio(staleResultCount.get(), results),
				ratio(unsupportedResultCount.get(), results),
				calls == 0 ? 0 : providerLatencyMsTotal.get() / calls
			);
		}

		private double ratio(long count, long total) {
			return total == 0 ? 0.0 : (double) count / total;
		}
	}

	private record ProcessedArrivals(
		List<RealtimeArrival> arrivals,
		List<RealtimeArrivalObservation> observations
	) {
	}

	private enum ProviderCallQuotaDecision {
		ACQUIRED,
		DENIED,
		UNAVAILABLE
	}

	private record NormalizedRealtimeQuery(
		RealtimeQuery query,
		long cacheVersion,
		String providerStationId,
		String fallbackCode
	) {
		static NormalizedRealtimeQuery mapped(RealtimeQuery query, long cacheVersion, String providerStationId) {
			return new NormalizedRealtimeQuery(query, cacheVersion, providerStationId, null);
		}

		static NormalizedRealtimeQuery rejected(String fallbackCode) {
			return new NormalizedRealtimeQuery(null, 0, null, fallbackCode);
		}

		boolean rejected() {
			return fallbackCode != null;
		}
	}
}

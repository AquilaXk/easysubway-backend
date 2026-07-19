package com.easysubway.train.application;

import com.easysubway.train.application.TrainSearchCache.CachedCatalog;
import com.easysubway.train.application.TrainSearchCache.CachedLeg;
import com.easysubway.train.application.TrainSearchProvider.Catalog;
import com.easysubway.train.application.TrainSearchProvider.ProviderFailure;
import com.easysubway.train.domain.TrainSearchModels.Journey;
import com.easysubway.train.domain.TrainSearchModels.LegQuery;
import com.easysubway.train.domain.TrainSearchModels.SearchCriteria;
import com.easysubway.train.domain.TrainSearchModels.SearchResult;
import com.easysubway.train.domain.TrainSearchModels.Station;
import com.easysubway.train.domain.TrainSearchModels.TrainType;
import com.easysubway.train.domain.TrainSearchScopePolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class TrainSearchService {

	private static final String CATALOG_KIND = "catalog";
	private static final String CATALOG_LEASE_KEY = "catalog-refresh-v1";
	private static final Duration CATALOG_TTL = Duration.ofHours(24);
	private static final Duration TODAY_TTL = Duration.ofMinutes(5);
	private static final Duration FUTURE_TTL = Duration.ofHours(6);
	// 한 구간도 영업일 2일, 페이지네이션, 복수 열차종 코드, 요청별 재시도를 순차 수행할 수 있다.
	private static final Duration LEG_LEASE_TTL = Duration.ofMinutes(15);
	private static final Duration CATALOG_LEASE_TTL = Duration.ofMinutes(6);
	private static final Duration CATALOG_LOAD_BUDGET = Duration.ofMinutes(5);
	private static final Duration CATALOG_RECOVERY_GRACE = Duration.ofSeconds(1);
	private static final Duration HTTP_REQUEST_BUDGET = Duration.ofSeconds(30);
	private static final Duration LEASE_WAIT_BUDGET = Duration.ofSeconds(5);
	private static final List<Duration> LEASE_POLLS = leasePolls(LEASE_WAIT_BUDGET);
	private static final List<Duration> CATALOG_RECOVERY_POLLS = leasePolls(
		CATALOG_LEASE_TTL.plus(CATALOG_RECOVERY_GRACE)
	);
	private static final int L1_LIMIT = 20_000;
	private static final TypeReference<List<Journey>> JOURNEYS = new TypeReference<>() {};

	private final TrainSearchProvider provider;
	private final TrainSearchCache cache;
	private final ObjectMapper objectMapper;
	private final Clock clock;
	private final Sleeper sleeper;
	private final Supplier<String> ownerSupplier;
	private final ConcurrentHashMap<String, CachedLeg> l1 = new ConcurrentHashMap<>();
	private final ConcurrentLinkedQueue<String> l1Order = new ConcurrentLinkedQueue<>();
	private final ConcurrentHashMap<String, CompletableFuture<CachedLeg>> singleFlights = new ConcurrentHashMap<>();

	@Autowired
	public TrainSearchService(TrainSearchProvider provider, TrainSearchCache cache, ObjectMapper objectMapper) {
		this(
			provider,
			cache,
			objectMapper,
			Clock.systemUTC(),
			duration -> Thread.sleep(duration.toMillis()),
			() -> UUID.randomUUID().toString()
		);
	}

	TrainSearchService(
		TrainSearchProvider provider,
		TrainSearchCache cache,
		ObjectMapper objectMapper,
		Clock clock,
		Sleeper sleeper,
		Supplier<String> ownerSupplier
	) {
		this.provider = provider;
		this.cache = cache;
		this.objectMapper = objectMapper;
		this.clock = clock;
		this.sleeper = sleeper;
		this.ownerSupplier = ownerSupplier;
	}

	public Catalog catalog() {
		return catalogWithMetadata(requestDeadline()).catalog();
	}

	private CatalogEntry catalogWithMetadata(Instant deadline) {
		try {
			requireBefore(deadline);
			Instant now = clock.instant();
			var cached = cache.freshCatalog(CATALOG_KIND, now);
			requireBefore(deadline);
			return cached
				.map(value -> new CatalogEntry(decodeCatalog(value), value.expiresAt()))
				.orElseGet(() -> refreshCatalogEntry(now, false, false, deadline));
		} catch (TrainSearchFailure failure) {
			throw failure;
		} catch (RuntimeException exception) {
			throw failure("TRAIN_SEARCH_UNAVAILABLE", exception);
		}
	}

	public List<Station> stations(String query, String trainType) {
		return stationsWithMetadata(query, trainType).stations();
	}

	public StationSearchSnapshot stationsWithMetadata(String query, String trainType) {
		String normalizedQuery = query == null ? "" : query.trim();
		if (normalizedQuery.codePointCount(0, normalizedQuery.length()) < 2) {
			throw failure("TRAIN_SEARCH_INVALID_ARGUMENT");
		}
		if (trainType != null) requireSupported(trainType);
		String folded = normalizedQuery.toLowerCase(Locale.ROOT);
		CatalogEntry catalog = catalogWithMetadata(requestDeadline());
		List<Station> stations = catalog.catalog().stations().stream()
			.filter(station -> station.name().toLowerCase(Locale.ROOT).contains(folded))
			.sorted(Comparator.comparing(Station::name).thenComparing(Station::id))
			.toList();
		return new StationSearchSnapshot(stations, catalog.expiresAt());
	}

	public SearchResult search(SearchCriteria criteria) {
		return searchWithMetadata(criteria).result();
	}

	public TrainSearchSnapshot searchWithMetadata(SearchCriteria criteria) {
		Instant deadline = requestDeadline();
		LocalDate admittedServiceDay = TrainSearchScopePolicy.currentServiceDay(clock);
		SearchCriteria normalized = validateStructure(criteria, admittedServiceDay);
		try {
			CatalogEntry catalogEntry = catalogWithMetadata(deadline);
			Catalog catalog = catalogEntry.catalog();
			validateStations(normalized, catalog);
			LegResult outbound = direction(
				catalog,
				normalized.departureStationId(),
				normalized.arrivalStationId(),
				normalized.departureDate(),
				normalized.trainType(),
				admittedServiceDay,
				deadline
			);
			LegResult inbound = normalized.returnDate() == null ? null : direction(
				catalog,
				normalized.arrivalStationId(),
				normalized.departureStationId(),
				normalized.returnDate(),
				normalized.trainType(),
				admittedServiceDay,
				deadline
			);
			Instant observedAt = inbound == null || outbound.observedAt().isAfter(inbound.observedAt())
				? outbound.observedAt()
				: inbound.observedAt();
			SearchResult result = new SearchResult(
				OffsetDateTime.ofInstant(observedAt, ZoneOffset.UTC),
				outbound.journeys(),
				inbound == null ? List.of() : inbound.journeys()
			);
			Instant expiresAt = inbound == null || outbound.expiresAt().isBefore(inbound.expiresAt())
				? outbound.expiresAt()
				: inbound.expiresAt();
			if (catalogEntry.expiresAt().isBefore(expiresAt)) expiresAt = catalogEntry.expiresAt();
			if (!clock.instant().isBefore(expiresAt)) throw failure("TRAIN_SEARCH_UNAVAILABLE");
			return new TrainSearchSnapshot(result, expiresAt);
		} catch (TrainSearchFailure failure) {
			throw failure;
		} catch (RuntimeException exception) {
			throw failure("TRAIN_SEARCH_UNAVAILABLE", exception);
		}
	}

	public Catalog refreshCatalog() {
		try {
			Instant now = clock.instant();
			return refreshCatalogEntry(now, true, true, catalogRecoveryDeadline(now)).catalog();
		} catch (TrainSearchFailure failure) {
			throw failure;
		} catch (RuntimeException exception) {
			throw failure("TRAIN_SEARCH_UNAVAILABLE", exception);
		}
	}

	public Catalog ensureCatalogAvailable() {
		try {
			Instant now = clock.instant();
			return refreshCatalogEntry(now, false, true, catalogRecoveryDeadline(now)).catalog();
		} catch (TrainSearchFailure failure) {
			throw failure;
		} catch (RuntimeException exception) {
			throw failure("TRAIN_SEARCH_UNAVAILABLE", exception);
		}
	}

	public int purgeExpired() {
		Instant now = clock.instant();
		return purgeExpiredL1(now) + cache.purgeExpiredBefore(now.minus(Duration.ofHours(48)));
	}

	private Instant catalogRecoveryDeadline(Instant now) {
		return now.plus(CATALOG_LEASE_TTL).plus(CATALOG_RECOVERY_GRACE).plus(CATALOG_LOAD_BUDGET);
	}

	private CatalogEntry refreshCatalogEntry(
		Instant now,
		boolean force,
		boolean recoverOrphanLease,
		Instant deadline
	) {
		var existing = cache.freshCatalog(CATALOG_KIND, now);
		requireBefore(deadline);
		if (!force && existing.isPresent()) {
			CachedCatalog cached = existing.orElseThrow();
			return new CatalogEntry(decodeCatalog(cached), cached.expiresAt());
		}
		String owner = ownerSupplier.get();
		if (!cache.tryAcquireLease(CATALOG_LEASE_KEY, owner, now, CATALOG_LEASE_TTL)) {
			return pollForCatalog(force ? existing.orElse(null) : null, deadline, recoverOrphanLease);
		}
		return loadCatalogAfterLease(owner, existing.orElse(null), force, clock.instant(), deadline);
	}

	private CatalogEntry pollForCatalog(CachedCatalog baseline, Instant deadline, boolean recoverOrphanLease) {
		List<Duration> polls = recoverOrphanLease ? CATALOG_RECOVERY_POLLS : LEASE_POLLS;
		for (Duration delay : polls) {
			sleep(delay, deadline);
			Instant now = clock.instant();
			var cached = cache.freshCatalog(CATALOG_KIND, now);
			requireBefore(deadline);
			if (cached.isPresent() && catalogChanged(baseline, cached.orElseThrow())) {
				CachedCatalog value = cached.orElseThrow();
				return new CatalogEntry(decodeCatalog(value), value.expiresAt());
			}
			String owner = ownerSupplier.get();
			if (cache.tryAcquireLease(CATALOG_LEASE_KEY, owner, now, CATALOG_LEASE_TTL)) {
				return loadCatalogAfterLease(owner, baseline, true, clock.instant(), deadline);
			}
		}
		throw failure("TRAIN_SEARCH_UNAVAILABLE");
	}

	private CatalogEntry loadCatalogAfterLease(
		String owner,
		CachedCatalog baseline,
		boolean force,
		Instant acquiredAt,
		Instant deadline
	) {
		boolean delegatedToLoad = false;
		try {
			var current = cache.freshCatalog(CATALOG_KIND, clock.instant());
			requireBefore(deadline);
			if (current.isPresent() && (!force || catalogChanged(baseline, current.orElseThrow()))) {
				CachedCatalog value = current.orElseThrow();
				return new CatalogEntry(decodeCatalog(value), value.expiresAt());
			}
			delegatedToLoad = true;
			return loadCatalog(owner, catalogLoadDeadline(acquiredAt, deadline));
		} finally {
			if (!delegatedToLoad) cache.releaseLease(CATALOG_LEASE_KEY, owner);
		}
	}

	private Instant catalogLoadDeadline(Instant acquiredAt, Instant overallDeadline) {
		Instant ownedLoadDeadline = acquiredAt.plus(CATALOG_LOAD_BUDGET);
		return ownedLoadDeadline.isBefore(overallDeadline) ? ownedLoadDeadline : overallDeadline;
	}

	private boolean catalogChanged(CachedCatalog baseline, CachedCatalog current) {
		return baseline == null
			|| !Objects.equals(baseline.payloadSha256(), current.payloadSha256())
			|| !Objects.equals(baseline.expiresAt(), current.expiresAt());
	}

	private CatalogEntry loadCatalog(String owner, Instant deadline) {
		try {
			requireBefore(deadline);
			Catalog loaded = provider.catalog(deadline);
			requireBefore(deadline);
			Instant completedAt = clock.instant();
			Catalog filtered = new Catalog(
				loaded.observedAt(),
				loaded.stations(),
				TrainSearchScopePolicy.retainSupported(loaded.trainTypes(), TrainType::code)
			);
			String payload = write(filtered);
			Instant expiresAt = completedAt.plus(CATALOG_TTL);
			requireBefore(deadline);
			cache.replaceCatalog(List.of(new CachedCatalog(
				CATALOG_KIND,
				payload,
				sha256(payload),
				filtered.observedAt(),
				expiresAt
			)));
			return new CatalogEntry(filtered, expiresAt);
		} catch (ProviderFailure exception) {
			throw failure(providerFailureCode(exception), exception);
		} finally {
			cache.releaseLease(CATALOG_LEASE_KEY, owner);
		}
	}

	private LegResult direction(
		Catalog catalog,
		String departureStationId,
		String arrivalStationId,
		LocalDate date,
		String requestedTrainType,
		LocalDate admittedServiceDay,
		Instant deadline
	) {
		Station departure = station(catalog, departureStationId);
		Station arrival = station(catalog, arrivalStationId);
		List<TrainType> types = requestedTrainType == null
			? catalog.trainTypes()
			: catalog.trainTypes().stream().filter(type -> requestedTrainType.equals(type.code())).toList();
		if (types.isEmpty() || types.stream().anyMatch(type -> type.providerCodes().isEmpty())) {
			throw failure("TRAIN_SEARCH_UNAVAILABLE");
		}
		var legs = new java.util.ArrayList<CachedLeg>();
		for (TrainType type : types) {
			requireBefore(deadline);
			legs.add(leg(new LegQuery(
				departure.id(),
				arrival.id(),
				date,
				type.code(),
				type.providerCodes(),
				departure.name(),
				arrival.name()
			), admittedServiceDay, deadline));
		}
		Map<String, Journey> unique = new LinkedHashMap<>();
		legs.stream()
			.flatMap(value -> decodeJourneys(value).stream())
			.sorted(Comparator.comparing(Journey::departureAt)
				.thenComparing(Journey::arrivalAt)
				.thenComparing(Journey::trainType)
				.thenComparing(Journey::trainNumber))
			.forEach(journey -> unique.putIfAbsent(journeyKey(journey), journey));
		Instant observedAt = legs.stream().map(CachedLeg::observedAt).max(Comparator.naturalOrder()).orElseThrow();
		Instant expiresAt = legs.stream().map(CachedLeg::expiresAt).min(Comparator.naturalOrder()).orElseThrow();
		return new LegResult(observedAt, expiresAt, List.copyOf(unique.values()));
	}

	private CachedLeg leg(LegQuery query, LocalDate admittedServiceDay, Instant deadline) {
		requireBefore(deadline);
		String key = key(query);
		Instant now = clock.instant();
		CachedLeg local = l1.get(key);
		if (local != null && local.expiresAt().isAfter(now)) return validLeg(key, local);
		if (local != null) forget(key, local);
		var shared = cache.freshLeg(key, now);
		requireBefore(deadline);
		if (shared.isPresent()) {
			CachedLeg value = validLeg(key, shared.orElseThrow());
			remember(key, value);
			return value;
		}
		var pending = new CompletableFuture<CachedLeg>();
		var existing = singleFlights.putIfAbsent(key, pending);
		if (existing != null) return await(existing, deadline);
		try {
			CachedLeg loaded = loadLeg(key, query, admittedServiceDay, now, deadline);
			pending.complete(loaded);
			return loaded;
		} catch (RuntimeException exception) {
			pending.completeExceptionally(exception);
			throw exception;
		} finally {
			singleFlights.remove(key, pending);
		}
	}

	private CachedLeg loadLeg(String key, LegQuery query, LocalDate admittedServiceDay, Instant now, Instant deadline) {
		String owner = ownerSupplier.get();
		if (!cache.tryAcquireLease(key, owner, now, LEG_LEASE_TTL)) {
			return pollForShared(key, query, admittedServiceDay, deadline);
		}
		return loadOwnedLeg(key, query, admittedServiceDay, owner, deadline);
	}

	private CachedLeg loadOwnedLeg(
		String key,
		LegQuery query,
		LocalDate admittedServiceDay,
		String owner,
		Instant deadline
	) {
		boolean released = false;
		try {
			requireBefore(deadline);
			List<Journey> journeys = provider.search(query, deadline);
			requireBefore(deadline);
			Instant completedAt = clock.instant();
			String normalizedQuery = write(canonical(query));
			String payload = write(journeys);
			var loaded = new CachedLeg(
				key,
				normalizedQuery,
				payload,
				sha256(payload),
				completedAt,
				expiresAt(query, admittedServiceDay, completedAt)
			);
			validLeg(key, loaded);
			if (!cache.storeLegAndRelease(key, owner, loaded)) throw failure("TRAIN_SEARCH_UNAVAILABLE");
			released = true;
			remember(key, loaded);
			return loaded;
		} catch (ProviderFailure exception) {
			throw failure(providerFailureCode(exception), exception);
		} finally {
			if (!released) cache.releaseLease(key, owner);
		}
	}

	private Instant expiresAt(LegQuery query, LocalDate admittedServiceDay, Instant now) {
		if (query.departureDate().equals(admittedServiceDay)) {
			Instant todayTtl = now.plus(TODAY_TTL);
			Instant nextServiceDayStart = TrainSearchScopePolicy.serviceDayStartsAt(query.departureDate().plusDays(1));
			return nextServiceDayStart.isAfter(now) && nextServiceDayStart.isBefore(todayTtl)
				? nextServiceDayStart
				: todayTtl;
		}
		Instant futureTtl = now.plus(FUTURE_TTL);
		Instant serviceDayStart = TrainSearchScopePolicy.serviceDayStartsAt(query.departureDate());
		if (!serviceDayStart.isAfter(now)) return now.plus(TODAY_TTL);
		return futureTtl.isBefore(serviceDayStart) ? futureTtl : serviceDayStart;
	}

	private CachedLeg pollForShared(String key, LegQuery query, LocalDate admittedServiceDay, Instant deadline) {
		for (Duration delay : LEASE_POLLS) {
			sleep(delay, deadline);
			Instant now = clock.instant();
			var cached = cache.freshLeg(key, now);
			requireBefore(deadline);
			if (cached.isPresent()) {
				CachedLeg value = validLeg(key, cached.orElseThrow());
				remember(key, value);
				return value;
			}
			String owner = ownerSupplier.get();
			if (cache.tryAcquireLease(key, owner, now, LEG_LEASE_TTL)) {
				return loadOwnedLeg(key, query, admittedServiceDay, owner, deadline);
			}
		}
		throw failure("TRAIN_SEARCH_UNAVAILABLE");
	}

	private SearchCriteria validateStructure(SearchCriteria criteria, LocalDate admittedServiceDay) {
		String departureStationId = criteria == null ? null : trimmed(criteria.departureStationId());
		String arrivalStationId = criteria == null ? null : trimmed(criteria.arrivalStationId());
		if (criteria == null
			|| blank(departureStationId)
			|| blank(arrivalStationId)
			|| Objects.equals(departureStationId, arrivalStationId)
			|| criteria.departureDate() == null
			|| criteria.departureDate().isBefore(admittedServiceDay)
			|| (criteria.returnDate() != null && criteria.returnDate().isBefore(criteria.departureDate()))) {
			throw failure("TRAIN_SEARCH_INVALID_ARGUMENT");
		}
		String trainType = criteria.trainType() == null ? null : requireSupported(criteria.trainType());
		return new SearchCriteria(
			departureStationId,
			arrivalStationId,
			criteria.departureDate(),
			criteria.returnDate(),
			trainType
		);
	}

	private void validateStations(SearchCriteria criteria, Catalog catalog) {
		if (catalog.stations().stream().map(Station::id).noneMatch(criteria.departureStationId()::equals)
			|| catalog.stations().stream().map(Station::id).noneMatch(criteria.arrivalStationId()::equals)) {
			throw failure("TRAIN_SEARCH_INVALID_ARGUMENT");
		}
	}

	private String requireSupported(String trainType) {
		try {
			return TrainSearchScopePolicy.requireSupported(trainType);
		} catch (IllegalArgumentException exception) {
			throw failure("TRAIN_SEARCH_UNSUPPORTED_TRAIN_TYPE", exception);
		}
	}

	private Station station(Catalog catalog, String id) {
		return catalog.stations().stream().filter(value -> id.equals(value.id())).findFirst()
			.orElseThrow(() -> failure("TRAIN_SEARCH_INVALID_ARGUMENT"));
	}

	private Map<String, Object> canonical(LegQuery query) {
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("version", 1);
		value.put("departureStationId", query.departureStationId());
		value.put("arrivalStationId", query.arrivalStationId());
		value.put("departureDate", query.departureDate());
		value.put("trainType", query.trainType());
		return value;
	}

	private String key(LegQuery query) {
		return sha256(write(canonical(query)));
	}

	private String journeyKey(Journey journey) {
		return String.join(
			"|",
			journey.trainType(),
			journey.trainNumber(),
			journey.departureStationId(),
			journey.departureAt().toString(),
			journey.arrivalStationId(),
			journey.arrivalAt().toString()
		);
	}

	private Catalog decodeCatalog(CachedCatalog cached) {
		if (!Objects.equals(cached.payloadSha256(), sha256(cached.payloadJson()))) {
			throw failure("TRAIN_SEARCH_UNAVAILABLE");
		}
		try {
			return objectMapper.readValue(cached.payloadJson(), Catalog.class);
		} catch (JsonProcessingException exception) {
			throw failure("TRAIN_SEARCH_UNAVAILABLE", exception);
		}
	}

	private CachedLeg validLeg(String requestedKey, CachedLeg cached) {
		if (!Objects.equals(requestedKey, cached.key())
			|| !Objects.equals(requestedKey, sha256(cached.normalizedQueryJson()))) {
			throw failure("TRAIN_SEARCH_UNAVAILABLE");
		}
		return cached;
	}

	private List<Journey> decodeJourneys(CachedLeg cached) {
		if (!Objects.equals(cached.payloadSha256(), sha256(cached.payloadJson()))) {
			throw failure("TRAIN_SEARCH_UNAVAILABLE");
		}
		try {
			return objectMapper.readValue(cached.payloadJson(), JOURNEYS);
		} catch (JsonProcessingException exception) {
			throw failure("TRAIN_SEARCH_UNAVAILABLE", exception);
		}
	}

	private String write(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (JsonProcessingException exception) {
			throw failure("TRAIN_SEARCH_UNAVAILABLE", exception);
		}
	}

	private String sha256(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 unavailable", exception);
		}
	}

	private synchronized void remember(String key, CachedLeg value) {
		CachedLeg previous = l1.put(key, value);
		if (previous == null) l1Order.add(key);
		while (l1.size() > L1_LIMIT) {
			String oldest = l1Order.poll();
			if (oldest == null) break;
			l1.remove(oldest);
		}
	}

	private synchronized void forget(String key, CachedLeg value) {
		if (l1.remove(key, value)) l1Order.remove(key);
	}

	private synchronized int purgeExpiredL1(Instant now) {
		int purged = 0;
		for (Map.Entry<String, CachedLeg> entry : l1.entrySet()) {
			if (!entry.getValue().expiresAt().isAfter(now) && l1.remove(entry.getKey(), entry.getValue())) {
				purged += 1;
			}
		}
		l1Order.removeIf(key -> !l1.containsKey(key));
		return purged;
	}

	private CachedLeg await(CompletableFuture<CachedLeg> future, Instant deadline) {
		try {
			Duration remaining = remaining(deadline);
			return future.get(Math.max(1L, remaining.toMillis()), TimeUnit.MILLISECONDS);
		} catch (ExecutionException exception) {
			if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
			throw failure("TRAIN_SEARCH_UNAVAILABLE", exception);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw failure("TRAIN_SEARCH_UNAVAILABLE", exception);
		} catch (TimeoutException exception) {
			throw failure("TRAIN_SEARCH_UNAVAILABLE", exception);
		}
	}

	private void sleep(Duration duration, Instant deadline) {
		try {
			Duration remaining = remaining(deadline);
			sleeper.sleep(duration.compareTo(remaining) < 0 ? duration : remaining);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw failure("TRAIN_SEARCH_UNAVAILABLE", exception);
		}
	}

	private Instant requestDeadline() {
		return clock.instant().plus(HTTP_REQUEST_BUDGET);
	}

	private Duration remaining(Instant deadline) {
		Instant now = clock.instant();
		if (!now.isBefore(deadline)) throw failure("TRAIN_SEARCH_UNAVAILABLE");
		return Duration.between(now, deadline);
	}

	private void requireBefore(Instant deadline) {
		remaining(deadline);
	}

	private String providerFailureCode(ProviderFailure failure) {
		return switch (failure.getMessage()) {
			case "TRAIN_SEARCH_PROVIDER_ERROR", "TRAIN_SEARCH_NO_VALID_ROWS", "TRAIN_SEARCH_UNAVAILABLE" -> failure.getMessage();
			default -> "TRAIN_SEARCH_UNAVAILABLE";
		};
	}

	private String trimmed(String value) {
		return value == null ? null : value.trim();
	}

	private boolean blank(String value) {
		return value == null || value.isBlank();
	}

	private TrainSearchFailure failure(String code) {
		return new TrainSearchFailure(code);
	}

	private TrainSearchFailure failure(String code, Throwable cause) {
		return new TrainSearchFailure(code, cause);
	}

	private static List<Duration> leasePolls(Duration budget) {
		var result = new java.util.ArrayList<Duration>();
		Duration elapsed = Duration.ZERO;
		for (Duration delay : List.of(
			Duration.ofMillis(100),
			Duration.ofMillis(200),
			Duration.ofMillis(400),
			Duration.ofMillis(800)
		)) {
			result.add(delay);
			elapsed = elapsed.plus(delay);
		}
		while (elapsed.compareTo(budget) < 0) {
			Duration remaining = budget.minus(elapsed);
			Duration delay = remaining.compareTo(Duration.ofSeconds(1)) < 0 ? remaining : Duration.ofSeconds(1);
			result.add(delay);
			elapsed = elapsed.plus(delay);
		}
		return List.copyOf(result);
	}

	private record CatalogEntry(Catalog catalog, Instant expiresAt) {}

	private record LegResult(Instant observedAt, Instant expiresAt, List<Journey> journeys) {}

	public record StationSearchSnapshot(List<Station> stations, Instant expiresAt) {
		public StationSearchSnapshot {
			stations = List.copyOf(stations);
		}
	}

	public record TrainSearchSnapshot(SearchResult result, Instant expiresAt) {}

	@FunctionalInterface
	interface Sleeper {
		void sleep(Duration duration) throws InterruptedException;
	}

	public static final class TrainSearchFailure extends RuntimeException {
		private final String code;

		public TrainSearchFailure(String code) {
			super(code);
			this.code = code;
		}

		public TrainSearchFailure(String code, Throwable cause) {
			super(code, cause);
			this.code = code;
		}

		public String getCode() {
			return code;
		}
	}
}

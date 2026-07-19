package com.easysubway.train.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import com.easysubway.train.application.TrainSearchCache.CachedCatalog;
import com.easysubway.train.application.TrainSearchCache.CachedLeg;
import com.easysubway.train.application.TrainSearchProvider.Catalog;
import com.easysubway.train.domain.TrainSearchModels.Journey;
import com.easysubway.train.domain.TrainSearchModels.LegQuery;
import com.easysubway.train.domain.TrainSearchModels.SearchCriteria;
import com.easysubway.train.domain.TrainSearchModels.Station;
import com.easysubway.train.domain.TrainSearchModels.TrainType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TrainSearchServiceTest {

	private static final Instant NOW = Instant.parse("2026-07-19T00:00:00Z");
	private FakeProvider provider;
	private FakeCache cache;
	private TrainSearchService service;

	@BeforeEach
	void setUp() {
		provider = new FakeProvider();
		cache = new FakeCache();
		service = new TrainSearchService(
			provider,
			cache,
			new ObjectMapper().registerModule(new JavaTimeModule()),
			Clock.fixed(NOW, ZoneOffset.UTC),
			duration -> {},
			() -> "owner"
		);
	}

	@Test
	void joinsConcurrentMissesIntoOneProviderSearch() throws Exception {
		provider.blockSearch = true;
		var first = CompletableFuture.supplyAsync(() -> service.search(criteria(null)));
		assertThat(provider.searchStarted.await(5, TimeUnit.SECONDS)).isTrue();
		var second = CompletableFuture.supplyAsync(() -> service.search(criteria(null)));
		assertThat(cache.secondLegRead.await(5, TimeUnit.SECONDS)).isTrue();
		provider.continueSearch.countDown();

		assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(second.get(5, TimeUnit.SECONDS));
		assertThat(provider.searchCalls).hasValue(1);
	}

	@Test
	void passesEveryProviderCodeAndStationNameInOneCanonicalLegQuery() {
		service.search(criteria(null));

		assertThat(provider.queries).singleElement().satisfies(query -> {
			assertThat(query.trainType()).isEqualTo("KTX");
			assertThat(query.providerTrainGradeCodes()).containsExactly("00", "10");
			assertThat(query.departureStationName()).isEqualTo("서울");
			assertThat(query.arrivalStationName()).isEqualTo("대전");
		});
	}

	@Test
	void usesFiveMinutesForTodayAndSixHoursForFutureRoundTrip() {
		var snapshot = service.searchWithMetadata(criteria(LocalDate.parse("2026-07-21")));
		var result = snapshot.result();

		assertThat(result.outbound()).hasSize(1);
		assertThat(result.inbound()).hasSize(1);
		assertThat(provider.queries).extracting(LegQuery::departureStationId, LegQuery::arrivalStationId)
			.containsExactly(
				tuple("NAT010000", "NAT011668"),
				tuple("NAT011668", "NAT010000")
			);
		assertThat(cache.legs.values()).extracting(CachedLeg::expiresAt)
			.containsExactlyInAnyOrder(NOW.plus(Duration.ofMinutes(5)), NOW.plus(Duration.ofHours(6)));
		assertThat(snapshot.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
	}

	@Test
	void purgeExpiredRemovesExpiredEntriesFromTheProcessLocalCache() {
		var clock = new TestClock(NOW);
		service = serviceWith(clock, duration -> {});
		service.search(criteria(null));
		clock.advance(Duration.ofMinutes(6));

		assertThat(service.purgeExpired()).isEqualTo(1);

		cache.legs.clear();
		service.search(criteria(null));
		assertThat(provider.searchCalls).hasValue(2);
	}

	@Test
	void rejectsARoundTripSnapshotWhenTheFirstLegExpiresDuringAssembly() {
		var clock = new TestClock(NOW);
		service = serviceWith(clock, duration -> {});
		service.search(criteria(null));
		CachedLeg outbound = cache.legs.values().iterator().next();
		cache.legs.put(outbound.key(), new CachedLeg(
			outbound.key(),
			outbound.normalizedQueryJson(),
			outbound.payloadJson(),
			outbound.payloadSha256(),
			outbound.observedAt(),
			NOW.plusSeconds(1)
		));
		service = serviceWith(clock, duration -> {});
		provider.beforeSearchReturn = () -> clock.advance(Duration.ofSeconds(2));

		assertThatThrownBy(() -> service.searchWithMetadata(criteria(LocalDate.parse("2026-07-21"))))
			.isInstanceOf(TrainSearchService.TrainSearchFailure.class)
			.extracting("code")
			.isEqualTo("TRAIN_SEARCH_UNAVAILABLE");
	}

	@Test
	void refreshesAFutureEntryWhenItsServiceDayStartsAtThreeAmInKorea() {
		service = serviceAt(Instant.parse("2026-07-19T17:59:00Z"));
		service.search(criteriaFor(LocalDate.parse("2026-07-20"), null));

		service = serviceAt(Instant.parse("2026-07-19T18:00:00Z"));
		service.search(criteriaFor(LocalDate.parse("2026-07-20"), null));

		assertThat(provider.searchCalls).hasValue(2);
	}

	@Test
	void capsCurrentServiceDayTtlAtTheNextThreeAmBoundary() {
		service = serviceAt(Instant.parse("2026-07-19T17:59:00Z"));

		var snapshot = service.searchWithMetadata(criteriaFor(LocalDate.parse("2026-07-19"), null));

		assertThat(cache.legs.values()).singleElement().satisfies(leg ->
			assertThat(leg.expiresAt()).isEqualTo(Instant.parse("2026-07-19T18:00:00Z"))
		);
		assertThat(snapshot.expiresAt()).isEqualTo(Instant.parse("2026-07-19T18:00:00Z"));
	}

	@Test
	void keepsAnAdmittedServiceDayResultFreshWhenProviderCompletionCrossesThreeAm() {
		var clock = new TestClock(Instant.parse("2026-07-19T17:59:59Z"));
		service = serviceWith(clock, duration -> {});
		provider.beforeSearchReturn = () -> clock.advance(Duration.ofSeconds(2));

		var snapshot = service.searchWithMetadata(criteriaFor(LocalDate.parse("2026-07-19"), null));

		assertThat(cache.legs.values()).singleElement().satisfies(leg -> {
			assertThat(leg.observedAt()).isEqualTo(Instant.parse("2026-07-19T18:00:01Z"));
			assertThat(leg.expiresAt()).isEqualTo(Instant.parse("2026-07-19T18:05:01Z"));
			assertThat(leg.expiresAt()).isAfter(leg.observedAt());
		});
		assertThat(snapshot.expiresAt()).isEqualTo(Instant.parse("2026-07-19T18:05:01Z"));
	}

	@Test
	void usesTodayTtlWhenAFutureServiceDayBecomesCurrentDuringProviderLoading() {
		var clock = new TestClock(Instant.parse("2026-07-19T17:59:59Z"));
		service = serviceWith(clock, duration -> {});
		provider.beforeSearchReturn = () -> clock.advance(Duration.ofSeconds(2));

		var snapshot = service.searchWithMetadata(criteriaFor(LocalDate.parse("2026-07-20"), null));

		assertThat(cache.legs.values()).singleElement().satisfies(leg -> {
			assertThat(leg.observedAt()).isEqualTo(Instant.parse("2026-07-19T18:00:01Z"));
			assertThat(leg.expiresAt()).isEqualTo(Instant.parse("2026-07-19T18:05:01Z"));
			assertThat(leg.expiresAt()).isAfter(leg.observedAt());
		});
		assertThat(snapshot.expiresAt()).isEqualTo(Instant.parse("2026-07-19T18:05:01Z"));
	}

	@Test
	void previousCalendarDateRemainsSearchableUntilTheThreeAmServiceDayBoundary() {
		service = serviceAt(Instant.parse("2026-07-19T17:30:00Z"));

		service.search(criteriaFor(LocalDate.parse("2026-07-19"), null));

		assertThat(provider.searchCalls).hasValue(1);

		service = serviceAt(Instant.parse("2026-07-19T18:00:00Z"));
		assertThatThrownBy(() -> service.search(criteriaFor(LocalDate.parse("2026-07-19"), null)))
			.isInstanceOf(TrainSearchService.TrainSearchFailure.class)
			.extracting("code")
			.isEqualTo("TRAIN_SEARCH_INVALID_ARGUMENT");
		assertThat(provider.searchCalls).hasValue(1);
	}

	@Test
	void keepsTheLegLeaseBeyondTheBoundedProviderSearchWindow() {
		service.search(criteria(null));

		assertThat(cache.leaseTtls.values())
			.anySatisfy(ttl -> assertThat(ttl).isGreaterThanOrEqualTo(Duration.ofMinutes(15)));
	}

	@Test
	void capsHttpWaitingForAnOccupiedLegLease() {
		service.catalog();
		cache.denyLeases = true;
		Duration[] slept = { Duration.ZERO };
		service = serviceWith(Clock.fixed(NOW, ZoneOffset.UTC), duration -> slept[0] = slept[0].plus(duration));

		assertThatThrownBy(() -> service.search(criteria(null)))
			.isInstanceOf(TrainSearchService.TrainSearchFailure.class)
			.extracting("code")
			.isEqualTo("TRAIN_SEARCH_UNAVAILABLE");
		assertThat(slept[0]).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(5));
	}

	@Test
	void returnsUnavailableBeforeAnAbandonedLegLeaseExpires() {
		service.search(criteria(null));
		String key = cache.legs.keySet().iterator().next();
		cache.legs.clear();
		cache.leases.put(key, "failed-owner");
		provider.searchCalls.set(0);
		var clock = new TestClock(NOW);
		Duration[] slept = { Duration.ZERO };
		service = serviceWith(clock, duration -> {
			slept[0] = slept[0].plus(duration);
			clock.advance(duration);
			if (slept[0].compareTo(Duration.ofMinutes(15)) >= 0) {
				cache.leases.remove(key, "failed-owner");
			}
		});

		assertThatThrownBy(() -> service.search(criteria(null)))
			.isInstanceOf(TrainSearchService.TrainSearchFailure.class)
			.extracting("code")
			.isEqualTo("TRAIN_SEARCH_UNAVAILABLE");

		assertThat(slept[0]).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(5));
		assertThat(provider.searchCalls).hasValue(0);
		assertThat(cache.legs).doesNotContainKey(key);
	}

	@Test
	void stopsStartingColdCacheTrainTypeSearchesAtTheRequestDeadline() {
		var clock = new TestClock(NOW);
		provider.catalogTrainTypes = allSupportedTrainTypes();
		provider.beforeSearchReturn = () -> clock.advance(Duration.ofSeconds(10));
		service = serviceWith(clock, duration -> clock.advance(duration));

		assertThatThrownBy(() -> service.search(new SearchCriteria(
			"NAT010000", "NAT011668", LocalDate.parse("2026-07-19"), null, null
		)))
			.isInstanceOf(TrainSearchService.TrainSearchFailure.class)
			.extracting("code")
			.isEqualTo("TRAIN_SEARCH_UNAVAILABLE");

		assertThat(provider.searchCalls).hasValue(3);
		assertThat(provider.queries).hasSize(3);
	}

	@Test
	void scheduledCatalogRefreshReplacesAStillFreshCatalog() {
		service.catalog();

		service.refreshCatalog();

		assertThat(provider.catalogCalls).hasValue(2);
	}

	@Test
	void catalogAvailabilityRechecksFreshCacheAfterAcquiringTheLease() {
		service.catalog();
		CachedCatalog concurrentlyStored = cache.catalogs.get("catalog");
		cache.catalogs.clear();
		provider.catalogCalls.set(0);
		cache.beforeLeaseAcquire = () -> cache.catalogs.put("catalog", concurrentlyStored);

		Catalog result = service.ensureCatalogAvailable();

		assertThat(result.stations()).extracting(Station::name).containsExactly("서울", "대전");
		assertThat(provider.catalogCalls).hasValue(0);
		assertThat(cache.leases).doesNotContainKey("catalog-refresh-v1");
	}

	@Test
	void forcedCatalogRefreshRechecksAChangedCacheAfterPollReacquiresTheLease() {
		service.catalog();
		CachedCatalog baseline = cache.catalogs.get("catalog");
		CachedCatalog concurrentlyRefreshed = new CachedCatalog(
			baseline.kind(),
			baseline.payloadJson(),
			baseline.payloadSha256(),
			baseline.observedAt(),
			baseline.expiresAt().plus(Duration.ofHours(1))
		);
		cache.leases.put("catalog-refresh-v1", "other-owner");
		AtomicInteger attempts = new AtomicInteger();
		cache.beforeLeaseAcquire = () -> {
			if (attempts.incrementAndGet() == 2) {
				cache.catalogs.put("catalog", concurrentlyRefreshed);
				cache.leases.remove("catalog-refresh-v1", "other-owner");
			}
		};

		Catalog result = service.refreshCatalog();

		assertThat(result.stations()).extracting(Station::name).containsExactly("서울", "대전");
		assertThat(provider.catalogCalls).hasValue(1);
		assertThat(cache.catalogs.get("catalog").expiresAt()).isEqualTo(concurrentlyRefreshed.expiresAt());
		assertThat(cache.leases).doesNotContainKey("catalog-refresh-v1");
	}

	@Test
	void catalogAvailabilityPreparationRecoversAnEmptyCatalogOutsideTheHttpBudget() {
		cache.leases.put("catalog-refresh-v1", "other-owner");
		var clock = new TestClock(NOW);
		Duration[] slept = { Duration.ZERO };
		service = serviceWith(clock, duration -> {
			slept[0] = slept[0].plus(duration);
			clock.advance(duration);
			if (slept[0].compareTo(Duration.ofMinutes(6)) >= 0) {
				cache.leases.remove("catalog-refresh-v1", "other-owner");
			}
		});

		service.ensureCatalogAvailable();

		assertThat(slept[0]).isGreaterThanOrEqualTo(Duration.ofMinutes(6));
		assertThat(provider.catalogCalls).hasValue(1);
		Instant acquiredAt = NOW.plus(slept[0]);
		assertThat(provider.catalogDeadlines).containsExactly(acquiredAt.plus(Duration.ofMinutes(5)));
		assertThat(cache.catalogs).containsKey("catalog");
	}

	@Test
	void forcedCatalogRefreshWaitsForAnOrphanLeaseAndKeepsLoadInsideTheNewLease() {
		service.catalog();
		cache.leases.put("catalog-refresh-v1", "other-owner");
		var clock = new TestClock(NOW);
		Duration[] slept = { Duration.ZERO };
		service = serviceWith(clock, duration -> {
			slept[0] = slept[0].plus(duration);
			clock.advance(duration);
			if (slept[0].compareTo(Duration.ofMinutes(6)) >= 0) {
				cache.leases.remove("catalog-refresh-v1", "other-owner");
			}
		});

		service.refreshCatalog();

		assertThat(slept[0]).isGreaterThanOrEqualTo(Duration.ofMinutes(6));
		assertThat(provider.catalogCalls).hasValue(2);
		assertThat(cache.leaseTtls.get("catalog-refresh-v1")).isEqualTo(Duration.ofMinutes(6));
		Instant acquiredAt = NOW.plus(slept[0]);
		assertThat(provider.catalogDeadlines).last().isEqualTo(acquiredAt.plus(Duration.ofMinutes(5)));
		assertThat(provider.catalogDeadlines.getLast()).isBefore(acquiredAt.plus(Duration.ofMinutes(6)));
	}

	@Test
	void catalogMissDoesNotWaitOnAnotherProviderCallMonitor() throws Exception {
		provider.blockCatalog = true;
		var first = CompletableFuture.supplyAsync(service::catalog);
		assertThat(provider.catalogStarted.await(5, TimeUnit.SECONDS)).isTrue();
		var second = CompletableFuture.supplyAsync(service::catalog);

		try {
			assertThatThrownBy(() -> second.get(1, TimeUnit.SECONDS))
				.isInstanceOf(ExecutionException.class)
				.hasCauseInstanceOf(TrainSearchService.TrainSearchFailure.class);
		} finally {
			provider.continueCatalog.countDown();
			first.get(5, TimeUnit.SECONDS);
		}
	}

	@Test
	void rejectsInvalidInputsBeforeSearchingAndKeepsItxOutOfTheCatalog() {
		assertThatThrownBy(() -> service.stations("서", null))
			.isInstanceOf(TrainSearchService.TrainSearchFailure.class)
			.extracting("code")
			.isEqualTo("TRAIN_SEARCH_INVALID_ARGUMENT");
		assertThatThrownBy(() -> service.search(new SearchCriteria(
			"UNKNOWN", "NAT011668", LocalDate.parse("2026-07-19"), null, "KTX"
		)))
			.isInstanceOf(TrainSearchService.TrainSearchFailure.class)
			.extracting("code")
			.isEqualTo("TRAIN_SEARCH_INVALID_ARGUMENT");

		assertThat(service.stations("서울", "KTX")).extracting(Station::name).containsExactly("서울");
		assertThat(service.catalog().trainTypes()).extracting(TrainType::code)
			.containsExactly("KTX")
			.doesNotContain("ITX_CHEONGCHUN");
		assertThat(provider.searchCalls).hasValue(0);
	}

	@Test
	void rejectsStructurallyInvalidSearchBeforeCatalogLookup() {
		assertThatThrownBy(() -> service.search(null))
			.isInstanceOf(TrainSearchService.TrainSearchFailure.class)
			.extracting("code")
			.isEqualTo("TRAIN_SEARCH_INVALID_ARGUMENT");

		assertThat(provider.catalogCalls).hasValue(0);
	}

	@Test
	void rejectsCatalogAndLegPayloadsWhoseSha256DoesNotMatch() {
		service.catalog();
		CachedCatalog catalog = cache.catalogs.get("catalog");
		cache.catalogs.put("catalog", new CachedCatalog(
			catalog.kind(), catalog.payloadJson(), "0".repeat(64), catalog.observedAt(), catalog.expiresAt()
		));

		assertThatThrownBy(() -> newService().catalog())
			.isInstanceOf(TrainSearchService.TrainSearchFailure.class)
			.extracting("code")
			.isEqualTo("TRAIN_SEARCH_UNAVAILABLE");

		cache.catalogs.clear();
		service.search(criteria(null));
		CachedLeg leg = cache.legs.values().iterator().next();
		cache.legs.put(leg.key(), new CachedLeg(
			leg.key(), leg.normalizedQueryJson(), leg.payloadJson(), "0".repeat(64), leg.observedAt(), leg.expiresAt()
		));

		assertThatThrownBy(() -> newService().search(criteria(null)))
			.isInstanceOf(TrainSearchService.TrainSearchFailure.class)
			.extracting("code")
			.isEqualTo("TRAIN_SEARCH_UNAVAILABLE");
	}

	@Test
	void rejectsALegWhoseNormalizedQueryDoesNotMatchItsCacheKey() {
		service.search(criteria(null));
		CachedLeg leg = cache.legs.values().iterator().next();
		cache.legs.put(leg.key(), new CachedLeg(
			leg.key(),
			leg.normalizedQueryJson().replace("NAT010000", "NAT999999"),
			leg.payloadJson(),
			leg.payloadSha256(),
			leg.observedAt(),
			leg.expiresAt()
		));

		assertThatThrownBy(() -> newService().search(criteria(null)))
			.isInstanceOf(TrainSearchService.TrainSearchFailure.class)
			.extracting("code")
			.isEqualTo("TRAIN_SEARCH_UNAVAILABLE");
	}

	@Test
	void mapsCacheReadFailureToUnavailable() {
		cache.failCatalogRead = true;

		assertThatThrownBy(service::catalog)
			.isInstanceOf(TrainSearchService.TrainSearchFailure.class)
			.hasFieldOrPropertyWithValue("code", "TRAIN_SEARCH_UNAVAILABLE")
			.hasCauseInstanceOf(IllegalStateException.class);
	}

	@Test
	void checksTheRequestDeadlineAfterCatalogCacheRead() {
		var clock = new TestClock(NOW);
		service = serviceWith(clock, duration -> clock.advance(duration));
		service.catalog();
		cache.beforeCatalogReturn = () -> clock.advance(Duration.ofSeconds(30));

		assertThatThrownBy(() -> service.stations("서울", null))
			.isInstanceOf(TrainSearchService.TrainSearchFailure.class)
			.extracting("code")
			.isEqualTo("TRAIN_SEARCH_UNAVAILABLE");
		assertThat(provider.catalogCalls).hasValue(1);
	}

	@Test
	void startsTodayTtlWhenTheProviderCallCompletes() {
		var clock = new TestClock(NOW);
		service = serviceWith(clock, duration -> {});
		provider.beforeSearchReturn = () -> clock.advance(Duration.ofSeconds(10));

		service.search(criteria(null));

		assertThat(cache.legs.values()).singleElement().satisfies(leg -> {
			assertThat(leg.observedAt()).isEqualTo(NOW.plus(Duration.ofSeconds(10)));
			assertThat(leg.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)).plusSeconds(10));
		});
	}

	@Test
	void mapsProviderQuotaFailureToUnavailableWithoutServingAnExpiredRow() {
		service.search(criteria(null));
		CachedLeg fresh = cache.legs.values().iterator().next();
		cache.legs.put(fresh.key(), new CachedLeg(
			fresh.key(),
			fresh.normalizedQueryJson(),
			fresh.payloadJson(),
			fresh.payloadSha256(),
			fresh.observedAt(),
			NOW.minusSeconds(1)
		));
		provider.failureCode = "TRAIN_SEARCH_UNAVAILABLE";

		assertThatThrownBy(() -> newService().search(criteria(null)))
			.isInstanceOf(TrainSearchService.TrainSearchFailure.class)
			.extracting("code")
			.isEqualTo("TRAIN_SEARCH_UNAVAILABLE");
	}

	@Test
	void hashesTheCanonicalLegKeyWithoutProviderCodesOrCredentials() {
		service.search(criteria(null));

		assertThat(cache.legs.keySet()).singleElement().satisfies(key -> assertThat(key).matches("^[0-9a-f]{64}$"));
		assertThat(cache.legs.values()).singleElement().satisfies(leg -> {
			assertThat(leg.normalizedQueryJson()).doesNotContain(
				"providerTrainGradeCodes", "departureStationName", "arrivalStationName", "serviceKey"
			);
			assertThat(leg.normalizedQueryJson()).contains("NAT010000", "NAT011668", "KTX");
		});
	}

	private SearchCriteria criteria(LocalDate returnDate) {
		return criteriaFor(LocalDate.parse("2026-07-19"), returnDate);
	}

	private SearchCriteria criteriaFor(LocalDate departureDate, LocalDate returnDate) {
		return new SearchCriteria(
			"NAT010000", "NAT011668", departureDate, returnDate, "KTX"
		);
	}

	private TrainSearchService serviceAt(Instant instant) {
		return serviceWith(Clock.fixed(instant, ZoneOffset.UTC), duration -> {});
	}

	private TrainSearchService serviceWith(Clock clock, TrainSearchService.Sleeper sleeper) {
		return new TrainSearchService(
			provider,
			cache,
			new ObjectMapper().registerModule(new JavaTimeModule()),
			clock,
			sleeper,
			() -> "owner"
		);
	}

	private TrainSearchService newService() {
		return new TrainSearchService(
			provider,
			cache,
			new ObjectMapper().registerModule(new JavaTimeModule()),
			Clock.fixed(NOW, ZoneOffset.UTC),
			duration -> {},
			() -> "owner"
		);
	}

	private List<TrainType> allSupportedTrainTypes() {
		return List.of(
			new TrainType("KTX", "KTX", List.of("00")),
			new TrainType("KTX_SANCHEON", "KTX-산천", List.of("01", "10")),
			new TrainType("SRT", "SRT", List.of("02")),
			new TrainType("ITX_MAUM", "ITX-마음", List.of("03")),
			new TrainType("ITX_SAEMAEUL", "ITX-새마을", List.of("04")),
			new TrainType("SAEMAEUL", "새마을호", List.of("05")),
			new TrainType("MUGUNGHWA", "무궁화호", List.of("06")),
			new TrainType("NURIRO", "누리로", List.of("08"))
		);
	}

	private static final class FakeProvider implements TrainSearchProvider {
		private final AtomicInteger catalogCalls = new AtomicInteger();
		private final AtomicInteger searchCalls = new AtomicInteger();
		private final List<LegQuery> queries = new java.util.concurrent.CopyOnWriteArrayList<>();
		private final List<Instant> catalogDeadlines = new java.util.concurrent.CopyOnWriteArrayList<>();
		private final CountDownLatch searchStarted = new CountDownLatch(1);
		private final CountDownLatch continueSearch = new CountDownLatch(1);
		private final CountDownLatch catalogStarted = new CountDownLatch(1);
		private final CountDownLatch continueCatalog = new CountDownLatch(1);
		private volatile boolean blockSearch;
		private volatile boolean blockCatalog;
		private volatile String failureCode;
		private volatile Runnable beforeSearchReturn = () -> {};
		private volatile List<TrainType> catalogTrainTypes = List.of(
			new TrainType("KTX", "KTX", List.of("00", "10")),
			new TrainType("ITX_CHEONGCHUN", "ITX-청춘", List.of("09"))
		);

		@Override
		public Catalog catalog() {
			catalogCalls.incrementAndGet();
			catalogStarted.countDown();
			if (blockCatalog) {
				try {
					continueCatalog.await(5, TimeUnit.SECONDS);
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
				}
			}
			return new Catalog(
				NOW,
				List.of(new Station("NAT010000", "서울"), new Station("NAT011668", "대전")),
				catalogTrainTypes
			);
		}

		@Override
		public Catalog catalog(Instant deadline) {
			catalogDeadlines.add(deadline);
			return catalog();
		}

		@Override
		public List<Journey> search(LegQuery query) {
			if (failureCode != null) throw new ProviderFailure(failureCode);
			searchCalls.incrementAndGet();
			queries.add(query);
			searchStarted.countDown();
			if (blockSearch) {
				try {
					continueSearch.await(5, TimeUnit.SECONDS);
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
				}
			}
			beforeSearchReturn.run();
			return List.of(new Journey(
				"101", "KTX",
				query.departureStationId(), query.departureStationName(),
				OffsetDateTime.parse(query.departureDate() + "T09:00:00+09:00"),
				query.arrivalStationId(), query.arrivalStationName(),
				OffsetDateTime.parse(query.departureDate() + "T10:00:00+09:00"),
				60, 10_000
			));
		}
	}

	private static final class FakeCache implements TrainSearchCache {
		private final Map<String, CachedCatalog> catalogs = new ConcurrentHashMap<>();
		private final Map<String, CachedLeg> legs = new ConcurrentHashMap<>();
		private final Map<String, String> leases = new ConcurrentHashMap<>();
		private final Map<String, Duration> leaseTtls = new ConcurrentHashMap<>();
		private final AtomicInteger freshLegCalls = new AtomicInteger();
		private final CountDownLatch secondLegRead = new CountDownLatch(1);
		private volatile boolean failCatalogRead;
		private volatile boolean denyLeases;
		private volatile Runnable beforeCatalogReturn = () -> {};
		private volatile Runnable beforeLeaseAcquire = () -> {};

		@Override
		public Optional<CachedCatalog> freshCatalog(String kind, Instant now) {
			if (failCatalogRead) throw new IllegalStateException("database unavailable");
			beforeCatalogReturn.run();
			return Optional.ofNullable(catalogs.get(kind)).filter(value -> value.expiresAt().isAfter(now));
		}

		@Override
		public void replaceCatalog(List<CachedCatalog> values) {
			catalogs.clear();
			values.forEach(value -> catalogs.put(value.kind(), value));
		}

		@Override
		public Optional<CachedLeg> freshLeg(String key, Instant now) {
			if (freshLegCalls.incrementAndGet() >= 2) secondLegRead.countDown();
			return Optional.ofNullable(legs.get(key)).filter(value -> value.expiresAt().isAfter(now));
		}

		@Override
		public boolean tryAcquireLease(String key, String owner, Instant now, Duration ttl) {
			leaseTtls.put(key, ttl);
			beforeLeaseAcquire.run();
			if (denyLeases) return false;
			return leases.putIfAbsent(key, owner) == null;
		}

		@Override
		public void releaseLease(String key, String owner) {
			leases.remove(key, owner);
		}

		@Override
		public boolean storeLegAndRelease(String key, String owner, CachedLeg leg) {
			if (!leases.remove(key, owner)) return false;
			legs.put(key, leg);
			return true;
		}

		@Override
		public boolean tryAcquireProviderCall(String providerId, ZoneId providerZone, int minuteLimit, int dayLimit) {
			return true;
		}

		@Override
		public int purgeExpiredBefore(Instant cutoff) {
			int before = legs.size();
			legs.values().removeIf(value -> value.expiresAt().isBefore(cutoff));
			return before - legs.size();
		}
	}

	private static final class TestClock extends Clock {
		private Instant instant;

		private TestClock(Instant instant) {
			this.instant = instant;
		}

		void advance(Duration duration) {
			instant = instant.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return Clock.fixed(instant, zone);
		}

		@Override
		public Instant instant() {
			return instant;
		}
	}
}

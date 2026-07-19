package com.easysubway.train.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.train.application.TrainSearchCache.CachedCatalog;
import com.easysubway.train.application.TrainSearchCache.CachedLeg;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

class JdbcTrainSearchCacheTest {

	private JdbcTrainSearchCache repository;
	private JdbcTemplate jdbcTemplate;
	private DriverManagerDataSource dataSource;

	@BeforeEach
	void setUp() {
		dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:train-search-cache-" + UUID.randomUUID()
				+ ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		Flyway.configure()
			.dataSource(dataSource)
			.locations("classpath:db/migration/h2")
			.load()
			.migrate();
		jdbcTemplate = new JdbcTemplate(dataSource);
		repository = proxiedRepository();
	}

	@Test
	void replacesCatalogAtomicallyAndUsesDatabaseTimeForFreshness() {
		Instant databaseNow = databaseNow();
		Instant observedAt = databaseNow.minus(Duration.ofHours(2));
		Instant expiresAt = databaseNow.plus(Duration.ofHours(1));
		var stations = new CachedCatalog("stations", "[{\"id\":\"NAT010000\"}]", hash('a'), observedAt, expiresAt);
		var trainTypes = new CachedCatalog("train-types", "[{\"code\":\"KTX\"}]", hash('b'), observedAt, expiresAt);

		repository.replaceCatalog(List.of(stations, trainTypes));

		assertThat(repository.freshCatalog("stations", databaseNow.plus(Duration.ofDays(1)))).contains(stations);
		jdbcTemplate.update(
			"UPDATE train_catalog_cache SET observed_at = ?, expires_at = ? WHERE catalog_kind = 'stations'",
			java.sql.Timestamp.from(databaseNow.minus(Duration.ofHours(2))),
			java.sql.Timestamp.from(databaseNow.minus(Duration.ofHours(1)))
		);
		assertThat(repository.freshCatalog("stations", databaseNow.minus(Duration.ofDays(1)))).isEmpty();
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM train_catalog_cache", Integer.class))
			.isEqualTo(2);
	}

	@Test
	void boundsEveryCacheStatementWithAQueryTimeout() {
		assertThat(jdbcTemplate.getQueryTimeout()).isEqualTo(2);
	}

	@Test
	void leaseHasSingleOwnerAndOnlyOwnerCanRelease() {
		Instant now = Instant.parse("2026-07-19T00:00:00Z");
		assertThat(repository.tryAcquireLease("key", "owner-a", now, Duration.ofSeconds(15))).isTrue();
		assertThat(repository.tryAcquireLease("key", "owner-b", now, Duration.ofSeconds(15))).isFalse();

		repository.releaseLease("key", "owner-b");
		assertThat(repository.tryAcquireLease("key", "owner-b", now, Duration.ofSeconds(15))).isFalse();
		repository.releaseLease("key", "owner-a");
		assertThat(repository.tryAcquireLease("key", "owner-b", now, Duration.ofSeconds(15))).isTrue();
	}

	@Test
	void doesNotAcquireLeaseAfterAnotherNodeStoredFreshPayload() {
		Instant databaseNow = jdbcTemplate.queryForObject("SELECT CURRENT_TIMESTAMP", java.sql.Timestamp.class).toInstant();
		var fresh = leg("filled", databaseNow.minusSeconds(1), databaseNow.plus(Duration.ofHours(1)));
		assertThat(repository.tryAcquireLease("filled", "owner-a", databaseNow, Duration.ofSeconds(15))).isTrue();
		assertThat(repository.storeLegAndRelease("filled", "owner-a", fresh)).isTrue();

		assertThat(repository.tryAcquireLease("filled", "owner-b", databaseNow, Duration.ofSeconds(15))).isFalse();
	}

	@Test
	void concurrentLeaseAttemptsHaveExactlyOneOwner() throws InterruptedException {
		int callers = 8;
		var ready = new CountDownLatch(callers);
		var start = new CountDownLatch(1);
		var acquired = new AtomicInteger();
		var failed = new AtomicInteger();
		var completed = new AtomicInteger();
		var executor = Executors.newFixedThreadPool(callers);
		try {
			for (int index = 0; index < callers; index++) {
				String owner = "owner-" + index;
				executor.submit(() -> {
					ready.countDown();
					start.await();
					try {
						if (repository.tryAcquireLease(
							"shared-key",
							owner,
							Instant.parse("2026-07-19T00:00:00Z"),
							Duration.ofSeconds(15)
						)) {
							acquired.incrementAndGet();
						}
					} catch (RuntimeException exception) {
						failed.incrementAndGet();
					}
					completed.incrementAndGet();
					return null;
				});
			}
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			executor.shutdown();
			assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
			assertThat(completed).hasValue(callers);
			assertThat(failed).hasValue(0);
			assertThat(acquired).hasValue(1);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void ownerStoresFreshLegAndPurgeRemovesOnlyOldExpiredRows() {
		Instant databaseNow = databaseNow();
		Instant observedAt = databaseNow.minus(Duration.ofMinutes(2));
		var expired = leg("expired", observedAt, databaseNow.minus(Duration.ofMinutes(1)));
		var fresh = leg("fresh", observedAt, databaseNow.plus(Duration.ofHours(6)));
		assertThat(repository.tryAcquireLease("expired", "owner", observedAt, Duration.ofSeconds(15))).isTrue();
		assertThat(repository.storeLegAndRelease("expired", "owner", expired)).isTrue();
		assertThat(repository.tryAcquireLease("fresh", "owner", observedAt, Duration.ofSeconds(15))).isTrue();
		assertThat(repository.storeLegAndRelease("fresh", "owner", fresh)).isTrue();

		assertThat(repository.freshLeg("expired", databaseNow.minus(Duration.ofDays(1)))).isEmpty();
		assertThat(repository.freshLeg("fresh", databaseNow.plus(Duration.ofDays(1)))).contains(fresh);
		assertThat(repository.purgeExpiredBefore(databaseNow)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM train_search_cache", Integer.class))
			.isEqualTo(1);
	}

	@Test
	void freshLegReadIsReadOnlyAndRejectsMismatchedStoredKey() {
		Instant databaseNow = databaseNow();
		var fresh = leg("leased", databaseNow.minusSeconds(1), databaseNow.plus(Duration.ofHours(1)));
		assertThat(repository.tryAcquireLease("leased", "owner", databaseNow, Duration.ofSeconds(15))).isTrue();
		assertThatThrownBy(() -> repository.storeLegAndRelease(
			"leased", "owner", leg("different", databaseNow.minusSeconds(1), databaseNow.plus(Duration.ofHours(1)))))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("leg key must match lease key");
		assertThat(jdbcTemplate.queryForObject(
			"SELECT payload_json IS NULL FROM train_search_cache WHERE cache_key = 'leased'", Boolean.class)).isTrue();
		assertThat(repository.storeLegAndRelease("leased", "owner", fresh)).isTrue();

		Instant lastAccessAt = databaseNow.minus(Duration.ofHours(3));
		jdbcTemplate.update(
			"UPDATE train_search_cache SET last_access_at = ? WHERE cache_key = 'leased'",
			java.sql.Timestamp.from(lastAccessAt)
		);
		assertThat(repository.freshLeg("leased", databaseNow.plus(Duration.ofDays(1)))).contains(fresh);
		assertThat(jdbcTemplate.queryForObject(
			"SELECT last_access_at FROM train_search_cache WHERE cache_key = 'leased'", java.sql.Timestamp.class).toInstant())
			.isEqualTo(lastAccessAt);
	}

	@Test
	void purgeRemovesOldFailedAndOrphanPlaceholdersButKeepsActiveLease() {
		Instant databaseNow = jdbcTemplate.queryForObject("SELECT CURRENT_TIMESTAMP", java.sql.Timestamp.class).toInstant();
		Instant old = databaseNow.minus(Duration.ofHours(3));
		Instant cutoff = databaseNow.minus(Duration.ofHours(2));

		assertThat(repository.tryAcquireLease("failed", "owner", databaseNow, Duration.ofMinutes(1))).isTrue();
		repository.releaseLease("failed", "owner");
		assertThat(repository.tryAcquireLease("orphan", "owner", databaseNow, Duration.ofMinutes(1))).isTrue();
		assertThat(repository.tryAcquireLease("active", "owner", databaseNow, Duration.ofHours(1))).isTrue();
		jdbcTemplate.update("UPDATE train_search_cache SET last_access_at = ?", java.sql.Timestamp.from(old));
		jdbcTemplate.update(
			"UPDATE train_search_cache SET lease_expires_at = ? WHERE cache_key = 'orphan'",
			java.sql.Timestamp.from(databaseNow.minusSeconds(1))
		);

		assertThat(repository.purgeExpiredBefore(cutoff)).isEqualTo(2);
		assertThat(jdbcTemplate.queryForList("SELECT cache_key FROM train_search_cache", String.class))
			.containsExactly("active");
	}

	@Test
	void enforcesSharedMinuteAndDayQuotaPerProvider() {
		ZoneId providerZone = ZoneId.of("Asia/Seoul");
		assertThat(repository.tryAcquireProviderCall("tago-train", providerZone, 2, 2)).isTrue();
		assertThat(repository.tryAcquireProviderCall("tago-train", providerZone, 2, 2)).isTrue();
		assertThat(repository.tryAcquireProviderCall("tago-train", providerZone, 2, 2)).isFalse();
		assertThat(repository.tryAcquireProviderCall("other", providerZone, 1, 1)).isTrue();
	}

	private CachedLeg leg(String key, Instant observedAt, Instant expiresAt) {
		return new CachedLeg(key, "{\"key\":\"" + key + "\"}", "{\"outbound\":[]}", hash('c'), observedAt, expiresAt);
	}

	private String hash(char value) {
		return String.valueOf(value).repeat(64);
	}

	private JdbcTrainSearchCache proxiedRepository() {
		var target = new JdbcTrainSearchCache(jdbcTemplate);
		var proxyFactory = new ProxyFactory(target);
		proxyFactory.setProxyTargetClass(true);
		proxyFactory.addAdvice(new TransactionInterceptor(
			new DataSourceTransactionManager(dataSource),
			new AnnotationTransactionAttributeSource()
		));
		return (JdbcTrainSearchCache) proxyFactory.getProxy();
	}

	private Instant databaseNow() {
		return jdbcTemplate.queryForObject("SELECT CURRENT_TIMESTAMP", java.sql.Timestamp.class).toInstant();
	}
}

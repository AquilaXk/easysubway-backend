package com.easysubway.realtime.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.annotation.Transactional;

@DisplayName("JDBC realtime provider 공유 quota")
class JdbcRealtimeProviderCallQuotaRepositoryTest {

	private JdbcRealtimeProviderCallQuotaRepository repository;
	private JdbcTemplate jdbcTemplate;
	private DriverManagerDataSource dataSource;

	@BeforeEach
	void setUp() {
		dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:realtime-provider-quota-" + UUID.randomUUID()
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
	@DisplayName("새 repository instance도 같은 일일 quota를 이어서 사용한다")
	void persistsDailyQuotaAcrossRepositoryInstances() {
		Instant now = Instant.parse("2026-07-13T01:00:00Z");
		ZoneId providerZone = ZoneId.of("Asia/Seoul");
		assertThat(repository.tryAcquire("seoul-topis", now, providerZone, 10, 2)).isTrue();
		assertThat(repository.tryAcquire("seoul-topis", now.plusSeconds(60), providerZone, 10, 2)).isTrue();

		var restarted = proxiedRepository();
		assertThat(restarted.tryAcquire("seoul-topis", now.plusSeconds(120), providerZone, 10, 2)).isFalse();
	}

	@Test
	@DisplayName("분당 quota도 공유 상태에서 원자적으로 제한한다")
	void usesDatabaseTimeForSharedMinuteQuota() {
		Instant now = Instant.parse("2026-07-13T01:00:00Z");
		ZoneId providerZone = ZoneId.of("Asia/Seoul");
		assertThat(repository.tryAcquire("seoul-topis", now, providerZone, 1, 800)).isTrue();
		assertThat(repository.tryAcquire("seoul-topis", now.plusSeconds(60), providerZone, 1, 800)).isFalse();
	}

	@Test
	@DisplayName("동시 호출도 공유 quota 한도를 초과하지 않는다")
	void serializesConcurrentAcquisitions() throws InterruptedException {
		int callers = 8;
		int limit = 3;
		Instant now = Instant.parse("2026-07-13T01:00:00Z");
		ZoneId providerZone = ZoneId.of("Asia/Seoul");
		CountDownLatch ready = new CountDownLatch(callers);
		CountDownLatch start = new CountDownLatch(1);
		AtomicInteger acquired = new AtomicInteger();
		AtomicInteger completed = new AtomicInteger();
		ExecutorService executor = Executors.newFixedThreadPool(callers);
		try {
			for (int index = 0; index < callers; index++) {
				executor.submit(() -> {
					ready.countDown();
					start.await();
					if (repository.tryAcquire("seoul-topis", now, providerZone, limit, limit)) {
						acquired.incrementAndGet();
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
			assertThat(acquired).hasValue(limit);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	@DisplayName("quota 획득은 transaction 경계를 선언한다")
	void declaresTransactionalAcquire() throws NoSuchMethodException {
		Transactional transactional = JdbcRealtimeProviderCallQuotaRepository.class
			.getMethod("tryAcquire", String.class, Instant.class, ZoneId.class, int.class, int.class)
			.getAnnotation(Transactional.class);
		assertThat(transactional).isNotNull();
		assertThat(transactional.timeout()).isEqualTo(2);
	}

	private JdbcRealtimeProviderCallQuotaRepository proxiedRepository() {
		var target = new JdbcRealtimeProviderCallQuotaRepository(jdbcTemplate);
		var proxyFactory = new ProxyFactory(target);
		proxyFactory.setProxyTargetClass(true);
		proxyFactory.addAdvice(new TransactionInterceptor(
			new DataSourceTransactionManager(dataSource),
			new AnnotationTransactionAttributeSource()
		));
		return (JdbcRealtimeProviderCallQuotaRepository) proxyFactory.getProxy();
	}
}

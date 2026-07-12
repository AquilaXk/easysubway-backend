package com.easysubway.ads.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.ads.application.service.AdService;
import com.easysubway.ads.domain.AdCreative;
import com.easysubway.ads.domain.AdEventType;
import com.easysubway.common.error.InvalidRequestException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@DisplayName("PostgreSQL 광고 소재 저장소")
class JdbcAdRepositoryContainerTest {

	@Container
	private static final PostgreSQLContainer<?> POSTGRES =
		new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

	@Test
	@DisplayName("V47 schema에서 소재 upsert·활성 전환·기간 겹침 조회가 동작한다")
	void managesCreativeLifecycleOnPostgresql() {
		var dataSource = migratedDataSource();
		var jdbcTemplate = new JdbcTemplate(dataSource);
		var repository = new JdbcAdRepository(jdbcTemplate, 1_000_000);
		LocalDateTime startsAt = LocalDateTime.parse("2026-07-11T00:00:00");
		jdbcTemplate.update("""
			INSERT INTO ad_placements (id, display_name, enabled)
			VALUES ('route-result-bottom', '경로 결과 하단', TRUE)
			""");
		AdCreative creative = new AdCreative(
			"creative-postgres",
			"route-result-bottom",
			"https://assets.easysubway.example/ads/postgres.png",
			"https://partner.example/postgres",
			"광고주",
			"광고 대체텍스트",
			startsAt,
			startsAt.plusDays(1),
			false);

		repository.save(creative);
		repository.save(new AdCreative(
			creative.id(), creative.placementId(), creative.imageUrl(), creative.landingUrl(),
			"수정 광고주", creative.altText(), creative.startsAt(), creative.endsAt(), creative.enabled()));
		assertThat(repository.setEnabled(creative.id(), true)).isTrue();

		assertThat(repository.findAll()).singleElement()
			.satisfies(found -> {
				assertThat(found.advertiserName()).isEqualTo("수정 광고주");
				assertThat(found.enabled()).isTrue();
			});
		assertThat(repository.hasEnabledOverlap(
			"route-result-bottom", "other", startsAt.plusHours(1), startsAt.plusHours(2))).isTrue();
		assertThat(repository.hasEnabledOverlap(
			"route-result-bottom", "other", startsAt.plusDays(1), startsAt.plusDays(2))).isFalse();
	}

	@Test
	@DisplayName("같은 id 동시 생성은 한 건만 저장하고 다른 transaction은 duplicate conflict로 끝난다")
	void concurrentSameIdCreateHasOneSuccessAndOneConflict() throws Exception {
		var dataSource = migratedDataSource();
		var jdbcTemplate = new JdbcTemplate(dataSource);
		jdbcTemplate.update("""
			INSERT INTO ad_placements (id, display_name, enabled)
			VALUES ('route-result-bottom', '경로 결과 하단', TRUE)
			""");
		var repository = new JdbcAdRepository(new UpdateBarrierJdbcTemplate(dataSource), 1_000_000);
		var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
		LocalDateTime startsAt = LocalDateTime.parse("2026-07-11T00:00:00");
		AdCreative first = creative("동시 광고주 A", startsAt);
		AdCreative second = creative("동시 광고주 B", startsAt);

		List<Throwable> failures = runConcurrently(
			() -> save(transaction, repository, first),
			() -> save(transaction, repository, second)).stream()
			.filter(result -> result != null)
			.toList();
		assertThat(failures).singleElement().isInstanceOf(DuplicateKeyException.class);

		assertThat(repository.findAll()).singleElement()
			.extracting(AdCreative::advertiserName)
			.isIn("동시 광고주 A", "동시 광고주 B");
	}

	@Test
	@DisplayName("동시 edit와 enable은 잠근 current 일정으로 overlap 우회를 허용하지 않는다")
	void concurrentEditAndEnableCannotBypassOverlap() throws Exception {
		var dataSource = migratedDataSource();
		var jdbcTemplate = new JdbcTemplate(dataSource);
		jdbcTemplate.update("""
			INSERT INTO ad_placements (id, display_name, enabled)
			VALUES ('route-result-bottom', '경로 결과 하단', TRUE),
			       ('station-detail-bottom', '역 상세 하단', TRUE)
			""");
		var repository = new JdbcAdRepository(jdbcTemplate, 1_000_000);
		var service = new AdService(repository, "https://assets.easysubway.example");
		var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
		LocalDateTime startsAt = LocalDateTime.parse("2026-07-11T00:00:00");
		AdCreative blocking = creative(
			"blocking", "광고주 B", startsAt, startsAt.plusHours(2), true);
		AdCreative editable = creative(
			"editable", "광고주 A", startsAt.plusHours(2), startsAt.plusHours(4), false);
		repository.save(blocking);
		repository.save(editable);
		AdCreative overlappingEdit = creative(
			"editable", "수정 광고주 A", startsAt.plusHours(1), startsAt.plusHours(3), true);

		List<Throwable> failures = runConcurrently(
			() -> inTransaction(transaction, () -> service.saveCreative(overlappingEdit)),
			() -> inTransaction(transaction, () -> service.setCreativeEnabled("editable", true))).stream()
			.filter(result -> result != null)
			.toList();

		assertThat(failures).singleElement().isInstanceOf(InvalidRequestException.class);
		AdCreative found = repository.findById("editable").orElseThrow();
		if (found.enabled()) {
			assertThat(found.startsAt()).isEqualTo(startsAt.plusHours(2));
		} else {
			assertThat(found.startsAt()).isEqualTo(startsAt.plusHours(1));
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"enable", "update"})
	@DisplayName("public event와 admin enable/update 교차 실행은 deadlock 없이 일관된 상태로 끝난다")
	void publicEventAndAdminMutationDoNotDeadlock(String mutation) throws Exception {
		var dataSource = migratedDataSource();
		var jdbcTemplate = new JdbcTemplate(dataSource);
		jdbcTemplate.update("""
			INSERT INTO ad_placements (id, display_name, enabled)
			VALUES ('route-result-bottom', '경로 결과 하단', TRUE),
			       ('station-detail-bottom', '역 상세 하단', TRUE)
			""");
		LocalDateTime startsAt = LocalDateTime.parse("2026-07-11T00:00:00");
		var regularRepository = new JdbcAdRepository(jdbcTemplate, 1_000_000);
		AdCreative creative = creative(
			"event-creative", "광고주", startsAt, startsAt.plusHours(2), false);
		regularRepository.save(creative);
		var creativeLocked = new CountDownLatch(1);
		var eventPlacementLocked = new CountDownLatch(1);
		var adminRepository = new JdbcAdRepository(new AdminLockOrderJdbcTemplate(
			dataSource, creativeLocked, eventPlacementLocked), 1_000_000);
		var adminService = new AdService(adminRepository, "https://assets.easysubway.example");
		var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

		List<Throwable> failures = runConcurrently(
			() -> inTransaction(transaction, () -> {
				if ("enable".equals(mutation)) {
					adminService.setCreativeEnabled(creative.id(), true);
				} else {
					adminService.saveCreative(new AdCreative(
						creative.id(), creative.placementId(), creative.imageUrl(), creative.landingUrl(),
						"수정 광고주", creative.altText(), creative.startsAt(), creative.endsAt(), true));
				}
			}),
			() -> inTransaction(transaction, () -> {
				await(creativeLocked, "admin creative lock");
				jdbcTemplate.queryForObject(
					"SELECT id FROM ad_placements WHERE id = ? FOR KEY SHARE",
					String.class,
					creative.placementId());
				eventPlacementLocked.countDown();
				regularRepository.incrementEvent(
					creative.placementId(), creative.id(), com.easysubway.ads.domain.AdEventType.IMPRESSION,
					java.time.LocalDate.of(2026, 7, 11));
			})).stream()
			.filter(result -> result != null)
			.toList();

		assertThat(failures).isEmpty();
		AdCreative found = regularRepository.findById(creative.id()).orElseThrow();
		if ("enable".equals(mutation)) {
			assertThat(found.enabled()).isTrue();
		} else {
			assertThat(found.advertiserName()).isEqualTo("수정 광고주");
			assertThat(found.enabled()).isFalse();
		}
		assertThat(jdbcTemplate.queryForObject(
			"SELECT event_count FROM ad_event_daily WHERE creative_id = ?",
			Integer.class,
			creative.id())).isEqualTo(1);
	}

	@Test
	@DisplayName("absent-row 동시 event UPSERT는 예외 없이 cap에 정확히 도달한다")
	void concurrentEventsFromAbsentRowStopExactlyAtCap() throws Exception {
		assertConcurrentEventsStopExactlyAtCap(false);
	}

	@Test
	@DisplayName("cap-1 동시 event UPSERT는 예외 없이 cap에 정확히 도달한다")
	void concurrentEventsFromCapMinusOneStopExactlyAtCap() throws Exception {
		assertConcurrentEventsStopExactlyAtCap(true);
	}

	private void assertConcurrentEventsStopExactlyAtCap(boolean seedToCapMinusOne) throws Exception {
		int cap = 5;
		int workers = 8;
		var dataSource = migratedDataSource();
		var jdbcTemplate = new JdbcTemplate(dataSource);
		var repository = new JdbcAdRepository(jdbcTemplate, cap);
		LocalDateTime startsAt = LocalDateTime.parse("2026-07-12T00:00:00");
		jdbcTemplate.update("""
			INSERT INTO ad_placements (id, display_name, enabled)
			VALUES ('route-result-bottom', '경로 결과 하단', TRUE)
			""");
		repository.save(creative("capped-event", "광고주", startsAt, startsAt.plusDays(1), true));
		LocalDate eventDate = LocalDate.of(2026, 7, 12);
		if (seedToCapMinusOne) {
			for (int count = 1; count < cap; count++) {
				repository.incrementEvent(
					"route-result-bottom", "capped-event", AdEventType.IMPRESSION, eventDate);
			}
		}

		var ready = new CountDownLatch(workers);
		var start = new CountDownLatch(1);
		var executor = Executors.newFixedThreadPool(workers);
		var failures = new ArrayList<Throwable>();
		try {
			var futures = java.util.stream.IntStream.range(0, workers)
				.mapToObj(ignored -> executor.submit(() -> {
					ready.countDown();
					if (!start.await(5, TimeUnit.SECONDS)) {
						throw new IllegalStateException("event start latch timed out");
					}
					repository.incrementEvent(
						"route-result-bottom", "capped-event", AdEventType.IMPRESSION, eventDate);
					return null;
				}))
				.toList();
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			for (var future : futures) {
				try {
					future.get(5, TimeUnit.SECONDS);
				} catch (ExecutionException exception) {
					failures.add(exception.getCause());
				}
			}
		} finally {
			start.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}

		assertThat(failures).isEmpty();
		assertThat(jdbcTemplate.queryForObject(
			"SELECT event_count FROM ad_event_daily WHERE event_date = ? AND creative_id = ?",
			Integer.class,
			eventDate,
			"capped-event"))
			.isEqualTo(cap);
	}

	private Throwable save(
		TransactionTemplate transaction,
		JdbcAdRepository repository,
		AdCreative creative
	) {
		try {
			transaction.executeWithoutResult(status -> repository.save(creative));
			return null;
		} catch (RuntimeException exception) {
			return exception;
		}
	}

	private AdCreative creative(String advertiserName, LocalDateTime startsAt) {
		return creative(
			"concurrent-creative",
			advertiserName,
			startsAt,
			startsAt.plusDays(1),
			false);
	}

	private AdCreative creative(
		String id,
		String advertiserName,
		LocalDateTime startsAt,
		LocalDateTime endsAt,
		boolean enabled
	) {
		return new AdCreative(
			id,
			"route-result-bottom",
			"https://assets.easysubway.example/ads/" + id + ".png",
			"https://partner.example/" + id,
			advertiserName,
			advertiserName + " 광고",
			startsAt,
			endsAt,
			enabled);
	}

	private Throwable inTransaction(TransactionTemplate transaction, Runnable command) {
		try {
			transaction.executeWithoutResult(status -> command.run());
			return null;
		} catch (RuntimeException exception) {
			return exception;
		}
	}

	private List<Throwable> runConcurrently(
		Callable<Throwable> first,
		Callable<Throwable> second
	) throws Exception {
		var ready = new CountDownLatch(2);
		var start = new CountDownLatch(1);
		var executor = Executors.newFixedThreadPool(2);
		try {
			var firstFuture = executor.submit(gated(first, ready, start));
			var secondFuture = executor.submit(gated(second, ready, start));
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			return java.util.Arrays.asList(
				firstFuture.get(5, TimeUnit.SECONDS),
				secondFuture.get(5, TimeUnit.SECONDS));
		} finally {
			start.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	private Callable<Throwable> gated(
		Callable<Throwable> task,
		CountDownLatch ready,
		CountDownLatch start
	) {
		return () -> {
			ready.countDown();
			if (!start.await(5, TimeUnit.SECONDS)) {
				throw new IllegalStateException("concurrency start latch timed out");
			}
			return task.call();
		};
	}

	private void await(CountDownLatch latch, String name) {
		try {
			if (!latch.await(5, TimeUnit.SECONDS)) {
				throw new IllegalStateException(name + " timed out");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(name + " interrupted", exception);
		}
	}

	private DriverManagerDataSource migratedDataSource() {
		var dataSource = new DriverManagerDataSource(
			POSTGRES.getJdbcUrl(),
			POSTGRES.getUsername(),
			POSTGRES.getPassword());
		Flyway.configure()
			.dataSource(dataSource)
			.locations("classpath:db/migration/postgresql")
			.load()
			.migrate();
		var jdbcTemplate = new JdbcTemplate(dataSource);
		jdbcTemplate.update("DELETE FROM ad_event_daily");
		jdbcTemplate.update("DELETE FROM ad_creatives");
		jdbcTemplate.update("DELETE FROM ad_placements");
		return dataSource;
	}

	private static final class UpdateBarrierJdbcTemplate extends JdbcTemplate {

		private final CyclicBarrier barrier = new CyclicBarrier(2);
		private final AtomicInteger creativeUpdateCalls = new AtomicInteger();

		private UpdateBarrierJdbcTemplate(DataSource dataSource) {
			super(dataSource);
		}

		@Override
		public int update(String sql, Object... args) {
			int updated = super.update(sql, args);
			if (sql.stripLeading().startsWith("UPDATE ad_creatives")
				&& creativeUpdateCalls.incrementAndGet() <= 2) {
				try {
					barrier.await(5, TimeUnit.SECONDS);
				} catch (Exception exception) {
					throw new IllegalStateException(exception);
				}
			}
			return updated;
		}
	}

	private static final class AdminLockOrderJdbcTemplate extends JdbcTemplate {

		private final CountDownLatch creativeLocked;
		private final CountDownLatch eventPlacementLocked;
		private volatile boolean placementLockedFirst;

		private AdminLockOrderJdbcTemplate(
			DataSource dataSource,
			CountDownLatch creativeLocked,
			CountDownLatch eventPlacementLocked
		) {
			super(dataSource);
			this.creativeLocked = creativeLocked;
			this.eventPlacementLocked = eventPlacementLocked;
		}

		@Override
		public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
			T result = super.queryForObject(sql, requiredType, args);
			if (sql.contains("FROM ad_placements") && sql.contains("FOR UPDATE")) {
				placementLockedFirst = true;
			}
			return result;
		}

		@Override
		public <T> T queryForObject(String sql, RowMapper<T> rowMapper, Object... args) {
			T result = super.queryForObject(sql, rowMapper, args);
			if (sql.contains("FROM ad_creatives") && sql.contains("FOR UPDATE")) {
				creativeLocked.countDown();
				if (!placementLockedFirst) {
					try {
						if (!eventPlacementLocked.await(5, TimeUnit.SECONDS)) {
							throw new IllegalStateException("event placement lock timed out");
						}
					} catch (InterruptedException exception) {
						Thread.currentThread().interrupt();
						throw new IllegalStateException("event placement lock interrupted", exception);
					}
				}
			}
			return result;
		}
	}
}

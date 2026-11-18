package com.easysubway.ads.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.ads.domain.AdCreative;
import com.easysubway.ads.domain.AdEventType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

@DisplayName("H2 광고 소재 저장소")
class JdbcAdRepositoryTest {

	private static final int EVENT_DAILY_CAP = 2;
	private static final LocalDate EVENT_DATE = LocalDate.of(2026, 7, 12);
	private static final LocalDateTime T0 = LocalDateTime.parse("2026-07-11T00:00:00");

	private JdbcTemplate jdbcTemplate;
	private JdbcAdRepository repository;

	@BeforeEach
	void setUp() {
		var dataSource = new EmbeddedDatabaseBuilder()
			.setType(EmbeddedDatabaseType.H2)
			.generateUniqueName(true)
			.build();
		jdbcTemplate = new JdbcTemplate(dataSource);
		Flyway.configure()
			.dataSource(dataSource)
			.locations("classpath:db/migration/h2")
			.load()
			.migrate();
		repository = new JdbcAdRepository(jdbcTemplate, EVENT_DAILY_CAP);
	}

	@Test
	@DisplayName("같은 UTC 일자 composite key count는 설정 cap에서 멈춘다")
	void capsDailyEventCount() {
		repository.save(creative("event-creative", "route-result-bottom", T0, T0.plusDays(1), true));

		for (int attempt = 0; attempt < EVENT_DAILY_CAP + 2; attempt++) {
			repository.incrementEvent(
				"route-result-bottom", "event-creative", AdEventType.IMPRESSION, EVENT_DATE);
		}

		assertThat(jdbcTemplate.queryForObject(
			"SELECT event_count FROM ad_event_daily WHERE event_date = ? AND placement_id = ? AND creative_id = ? AND event_type = ?",
			Integer.class,
			EVENT_DATE, "route-result-bottom", "event-creative", "IMPRESSION"))
			.isEqualTo(EVENT_DAILY_CAP);
	}

	@Test
	@DisplayName("unknown 또는 placement가 다른 creative event는 저장하지 않는다")
	void ignoresUnknownAndMismatchedEventIds() {
		repository.save(creative("event-creative", "route-result-bottom", T0, T0.plusDays(1), true));

		repository.incrementEvent(
			"route-result-bottom", "missing", AdEventType.IMPRESSION, EVENT_DATE);
		repository.incrementEvent(
			"station-detail-bottom", "event-creative", AdEventType.IMPRESSION, EVENT_DATE);

		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ad_event_daily", Integer.class)).isZero();
	}

	@Test
	@DisplayName("예상하지 못한 DB failure는 익명 no-op으로 숨기지 않는다")
	void propagatesUnexpectedDatabaseFailure() {
		jdbcTemplate.execute("DROP TABLE ad_event_daily");

		assertThatThrownBy(() -> repository.incrementEvent(
			"route-result-bottom", "event-creative", AdEventType.IMPRESSION, EVENT_DATE))
			.isInstanceOf(DataAccessException.class);
	}

	@Test
	@DisplayName("H2 absent-row 동시 event는 예외 없이 cap에 정확히 도달한다")
	void concurrentH2EventsFromAbsentRowStopExactlyAtCap() throws Exception {
		assertConcurrentH2EventsStopExactlyAtCap(false);
	}

	@Test
	@DisplayName("H2 cap-1 동시 event는 예외 없이 cap에 정확히 도달한다")
	void concurrentH2EventsFromCapMinusOneStopExactlyAtCap() throws Exception {
		assertConcurrentH2EventsStopExactlyAtCap(true);
	}

	private void assertConcurrentH2EventsStopExactlyAtCap(boolean seedToCapMinusOne) throws Exception {
		int workers = 8;
		repository.save(creative("concurrent-event", "route-result-bottom", T0, T0.plusDays(1), true));
		if (seedToCapMinusOne) {
			for (int count = 1; count < EVENT_DAILY_CAP; count++) {
				repository.incrementEvent(
					"route-result-bottom", "concurrent-event", AdEventType.IMPRESSION, EVENT_DATE);
			}
		}
		var ready = new CountDownLatch(workers);
		var start = new CountDownLatch(1);
		var executor = Executors.newFixedThreadPool(workers);
		var failures = new ArrayList<Throwable>();
		try {
			var futures = IntStream.range(0, workers).mapToObj(ignored -> executor.submit(() -> {
				ready.countDown();
				if (!start.await(5, TimeUnit.SECONDS)) {
					throw new IllegalStateException("H2 event start latch timed out");
				}
				repository.incrementEvent(
					"route-result-bottom", "concurrent-event", AdEventType.IMPRESSION, EVENT_DATE);
				return null;
			})).toList();
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
			"SELECT event_count FROM ad_event_daily WHERE event_date = ? AND placement_id = ? AND creative_id = ? AND event_type = ?",
			Integer.class, EVENT_DATE, "route-result-bottom", "concurrent-event", "IMPRESSION"))
			.isEqualTo(EVENT_DAILY_CAP);
	}

	@Test
	@DisplayName("소재를 생성·목록 조회하고 같은 id를 수정한 뒤 활성 상태를 전환한다")
	void createsUpdatesListsAndTogglesCreative() {
		repository.save(creative("creative-1", "route-result-bottom", T0, T0.plusDays(1), false));

		assertThat(repository.findAll()).containsExactly(
			creative("creative-1", "route-result-bottom", T0, T0.plusDays(1), false));

		AdCreative updated = new AdCreative(
			"creative-1",
			"station-detail-bottom",
			"https://assets.easysubway.example/ads/updated.png",
			"https://partner.example/updated",
			"수정 광고주",
			"수정된 광고 대체텍스트",
			T0.plusHours(1),
			T0.plusDays(2),
			false);
		repository.save(updated);

		assertThat(repository.findById("creative-1")).contains(updated);
		assertThat(repository.findByIdForUpdate("creative-1")).contains(updated);
		repository.lockPlacement("station-detail-bottom");
		assertThat(repository.setEnabled("creative-1", true)).isTrue();
		assertThat(repository.findById("creative-1")).get()
			.extracting(AdCreative::enabled)
			.isEqualTo(true);
		assertThat(repository.setEnabled("missing", true)).isFalse();
	}

	@Test
	@DisplayName("같은 placement의 enabled 반개구간만 겹침으로 판단하고 자기 자신은 제외한다")
	void detectsEnabledHalfOpenIntervalOverlap() {
		repository.save(creative("existing", "route-result-bottom", T0, T0.plusHours(2), true));
		repository.save(creative("disabled", "route-result-bottom", T0, null, false));

		assertThat(repository.hasEnabledOverlap(
			"route-result-bottom", "new", T0.plusHours(1), T0.plusHours(3))).isTrue();
		assertThat(repository.hasEnabledOverlap(
			"route-result-bottom", "new", T0.plusHours(2), T0.plusHours(3))).isFalse();
		assertThat(repository.hasEnabledOverlap(
			"route-result-bottom", "existing", T0, T0.plusHours(2))).isFalse();
		assertThat(repository.hasEnabledOverlap(
			"station-detail-bottom", "new", T0, null)).isFalse();
	}

	private AdCreative creative(
		String id,
		String placementId,
		LocalDateTime startsAt,
		LocalDateTime endsAt,
		boolean enabled
	) {
		return new AdCreative(
			id,
			placementId,
			"https://assets.easysubway.example/ads/" + id + ".png",
			"https://partner.example/" + id,
			"광고주",
			"광고 대체텍스트",
			startsAt,
			endsAt,
			enabled);
	}

}

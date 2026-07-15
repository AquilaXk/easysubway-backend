package com.easysubway.route.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.route.application.port.out.RouteV2AccessStore.RouteV2Session;
import com.easysubway.route.application.port.out.RouteV2AccessStore.RouteV2State;
import com.easysubway.route.application.port.out.RouteV2AccessStore.SessionStatus;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.Transactional;

@DisplayName("Route V2 접근·임시 상태 저장소")
class JdbcRouteV2AccessStoreTest {

	private static final Instant NOW = Instant.parse("2026-07-16T09:00:00Z");

	private JdbcRouteV2AccessStore store;
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		var dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:route-v2-access;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		jdbcTemplate = new JdbcTemplate(dataSource);
		jdbcTemplate.execute("DROP TABLE IF EXISTS route_v2_states");
		jdbcTemplate.execute("DROP TABLE IF EXISTS route_v2_nonce_replays");
		jdbcTemplate.execute("DROP TABLE IF EXISTS route_v2_sessions");
		jdbcTemplate.execute("""
			CREATE TABLE route_v2_sessions (
				token_sha256 CHAR(64) PRIMARY KEY,
				scope VARCHAR(40) NOT NULL,
				issued_at TIMESTAMP WITH TIME ZONE NOT NULL,
				expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
				request_count INTEGER NOT NULL DEFAULT 0
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE route_v2_nonce_replays (
				nonce_sha256 CHAR(64) PRIMARY KEY,
				expires_at TIMESTAMP WITH TIME ZONE NOT NULL
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE route_v2_states (
				route_state_id VARCHAR(120) PRIMARY KEY,
				origin_station_id VARCHAR(120) NOT NULL,
				destination_station_id VARCHAR(120) NOT NULL,
				transport_scope VARCHAR(40) NOT NULL,
				requested_departure_at TIMESTAMP WITH TIME ZONE NOT NULL,
				itinerary_json TEXT NOT NULL,
				timetable_artifact_id VARCHAR(160) NOT NULL,
				created_at TIMESTAMP WITH TIME ZONE NOT NULL,
				planned_arrival_at TIMESTAMP WITH TIME ZONE NOT NULL,
				expires_at TIMESTAMP WITH TIME ZONE NOT NULL
			)
			""");
		store = new JdbcRouteV2AccessStore(jdbcTemplate);
	}

	@Test
	@DisplayName("nonce claim과 session 저장은 하나의 transaction 경계로 노출한다")
	void claimsNonceAndSavesSessionInOneTransaction() {
		var methods = Arrays.stream(JdbcRouteV2AccessStore.class.getDeclaredMethods())
			.filter(method -> method.getName().equals("claimNonceAndSaveSession"))
			.toList();

		assertThat(methods).singleElement().satisfies(method ->
			assertThat(method.isAnnotationPresent(Transactional.class)).isTrue()
		);
	}

	@Test
	@DisplayName("유효 session 요청은 정확히 50회까지만 원자적으로 소비한다")
	void consumesSessionAtMostFiftyTimes() {
		store.saveSession(new RouteV2Session("a".repeat(64), "route:v2:itx", NOW, NOW.plusSeconds(600), 0));

		for (int request = 1; request <= 50; request++) {
			assertThat(store.consumeSession("a".repeat(64), NOW.plusSeconds(request)).status())
				.isEqualTo(SessionStatus.VALID);
		}

		assertThat(store.consumeSession("a".repeat(64), NOW.plusSeconds(51)).status())
			.isEqualTo(SessionStatus.LIMITED);
		assertThat(jdbcTemplate.queryForObject(
			"SELECT request_count FROM route_v2_sessions WHERE token_sha256 = ?",
			Integer.class,
			"a".repeat(64)
		)).isEqualTo(50);
	}

	@Test
	@DisplayName("운영 설정은 50 이하에서 session lifetime limit을 강화할 수 있다")
	void supportsStricterConfiguredSessionLimit() {
		store = new JdbcRouteV2AccessStore(jdbcTemplate, 2);
		store.saveSession(new RouteV2Session("5".repeat(64), "route:v2:itx", NOW, NOW.plusSeconds(600), 0));

		assertThat(store.consumeSession("5".repeat(64), NOW).status()).isEqualTo(SessionStatus.VALID);
		assertThat(store.consumeSession("5".repeat(64), NOW).status()).isEqualTo(SessionStatus.VALID);
		assertThat(store.consumeSession("5".repeat(64), NOW).status()).isEqualTo(SessionStatus.LIMITED);
	}

	@Test
	@DisplayName("동시 요청도 session 전체 50회를 넘지 않는다")
	void consumesSessionAtomicallyUnderConcurrency() throws Exception {
		store.saveSession(new RouteV2Session("e".repeat(64), "route:v2:itx", NOW, NOW.plusSeconds(600), 0));
		var start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(12)) {
			var attempts = java.util.stream.IntStream.range(0, 100)
				.mapToObj(ignored -> executor.submit(() -> {
					start.await();
					return store.consumeSession("e".repeat(64), NOW.plusSeconds(1)).status();
				}))
				.toList();
			start.countDown();
			var statuses = attempts.stream().map(future -> {
				try {
					return future.get();
				} catch (Exception exception) {
					throw new IllegalStateException(exception);
				}
			}).toList();

			assertThat(statuses).filteredOn(SessionStatus.VALID::equals).hasSize(50);
			assertThat(statuses).filteredOn(SessionStatus.LIMITED::equals).hasSize(50);
		}
		assertThat(jdbcTemplate.queryForObject(
			"SELECT request_count FROM route_v2_sessions WHERE token_sha256 = ?",
			Integer.class,
			"e".repeat(64)
		)).isEqualTo(50);
	}

	@Test
	@DisplayName("unknown 또는 만료 session은 횟수를 증가시키지 않는다")
	void rejectsUnknownAndExpiredSessionWithoutCounting() {
		store.saveSession(new RouteV2Session("b".repeat(64), "route:v2:itx", NOW.minusSeconds(700), NOW, 7));

		assertThat(store.consumeSession("c".repeat(64), NOW).status()).isEqualTo(SessionStatus.MISSING);
		assertThat(store.consumeSession("b".repeat(64), NOW).status()).isEqualTo(SessionStatus.EXPIRED);
		assertThat(jdbcTemplate.queryForObject(
			"SELECT request_count FROM route_v2_sessions WHERE token_sha256 = ?",
			Integer.class,
			"b".repeat(64)
		)).isEqualTo(7);
	}

	@Test
	@DisplayName("nonce digest는 만료 전 재사용만 거부한다")
	void claimsNonceUntilItsExpiry() {
		assertThat(store.claimNonce("d".repeat(64), NOW.plusSeconds(120), NOW)).isTrue();
		assertThat(store.claimNonce("d".repeat(64), NOW.plusSeconds(121), NOW.plusSeconds(30))).isFalse();
		assertThat(store.claimNonce("d".repeat(64), NOW.plusSeconds(241), NOW.plusSeconds(121))).isTrue();
	}

	@Test
	@DisplayName("purge는 만료된 session·nonce·임시 상태만 삭제한다")
	void rejectsAndPurgesExpiredState() {
		store.saveSession(new RouteV2Session("f".repeat(64), "route:v2:itx", NOW.minusSeconds(700), NOW, 0));
		store.saveSession(new RouteV2Session("1".repeat(64), "route:v2:itx", NOW, NOW.plusSeconds(600), 0));
		jdbcTemplate.update(
			"INSERT INTO route_v2_nonce_replays (nonce_sha256, expires_at) VALUES (?, ?), (?, ?)",
			"2".repeat(64),
			NOW.minusSeconds(1),
			"3".repeat(64),
			NOW.plusSeconds(1)
		);
		store.saveState(state("expired", NOW.minusSeconds(1)));
		store.saveState(state("active", NOW.plusSeconds(1)));

		assertThat(store.loadState("expired", NOW)).isEmpty();
		assertThat(store.loadState("active", NOW)).isPresent();
		assertThat(store.purgeExpired(NOW)).isEqualTo(3);
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM route_v2_sessions", Integer.class)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM route_v2_nonce_replays", Integer.class)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM route_v2_states", Integer.class)).isEqualTo(1);
	}

	@Test
	@DisplayName("session 재발급은 기존 route state 만료 시각을 바꾸지 않는다")
	void sessionReissueDoesNotExtendRouteState() {
		Instant originalExpiry = NOW.plusSeconds(1800);
		store.saveState(state("existing", originalExpiry));

		store.saveSession(new RouteV2Session("4".repeat(64), "route:v2:itx", NOW, NOW.plusSeconds(600), 0));

		assertThat(store.loadState("existing", NOW).orElseThrow().expiresAt()).isEqualTo(originalExpiry);
	}

	private RouteV2State state(String id, Instant expiresAt) {
		return new RouteV2State(
			id,
			"station-origin",
			"station-destination",
			"SUBWAY_AND_ITX_CHEONGCHUN",
			NOW,
			"{\"itineraries\":[]}",
			"timetable-rc-1",
			NOW,
			NOW.plusSeconds(600),
			expiresAt
		);
	}
}

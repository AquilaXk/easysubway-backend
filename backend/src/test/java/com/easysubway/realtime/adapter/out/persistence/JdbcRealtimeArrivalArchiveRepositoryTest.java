package com.easysubway.realtime.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.realtime.domain.RealtimeArrivalObservation;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.annotation.Transactional;

@DisplayName("JDBC 실시간 도착 관측 archive")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcRealtimeArrivalArchiveRepositoryTest {

	private JdbcRealtimeArrivalArchiveRepository repository;
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		var dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:realtime-arrival-archive-" + UUID.randomUUID()
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
		var target = new JdbcRealtimeArrivalArchiveRepository(jdbcTemplate);
		var proxyFactory = new ProxyFactory(target);
		proxyFactory.setProxyTargetClass(true);
		proxyFactory.addAdvice(new TransactionInterceptor(
			new DataSourceTransactionManager(dataSource),
			new AnnotationTransactionAttributeSource()
		));
		repository = (JdbcRealtimeArrivalArchiveRepository) proxyFactory.getProxy();
	}

	@Test
	@DisplayName("raw·보정 ETA와 provider mapping context를 함께 저장한다")
	void savesObservationWithoutCredentialOrUserIdentifier() {
		RealtimeArrivalObservation observation = observation(
			Instant.parse("2026-06-26T08:00:00Z"),
			Instant.parse("2026-07-26T08:00:20Z")
		);

		repository.saveAll(List.of(observation));

		var row = jdbcTemplate.queryForMap("SELECT * FROM realtime_arrival_observations");
		assertThat(row)
			.containsEntry("PROVIDER_ID", "seoul-topis")
			.containsEntry("STATION_ID", "station-sangnoksu")
			.containsEntry("PROVIDER_STATION_ID", "1004000448")
			.containsEntry("TRAIN_NO", "4123")
			.containsEntry("RAW_ETA_SECONDS", 180)
			.containsEntry("ADJUSTED_ETA_SECONDS", 160);
		assertThat(row.keySet())
			.doesNotContain("SERVICE_KEY", "USER_ID", "RAW_RESPONSE", "REQUEST_BODY");
	}

	@Test
	@DisplayName("retention 종료 시각은 backend 수신 시각보다 뒤여야 한다")
	void rejectsInvalidRetentionWindow() {
		assertThatThrownBy(() -> observation(
			Instant.parse("2026-06-26T08:00:00Z"),
			Instant.parse("2026-06-26T07:59:59Z")
		))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("retainedUntil");
	}

	@Test
	@DisplayName("필수 provider 식별자는 blank일 수 없다")
	void rejectsBlankRequiredIdentifier() {
		assertThatThrownBy(() -> new RealtimeArrivalObservation(
			" ", "station-sangnoksu", "seoul-4", "1004", "1004000448", "4123",
			Instant.parse("2026-06-26T08:00:00Z"), Instant.parse("2026-06-26T08:00:00Z"),
			180, 160, null, null, Instant.parse("2026-07-26T08:00:00Z")
		))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("providerId");
	}

	@Test
	@DisplayName("ETA는 null 또는 0 이상이어야 한다")
	void rejectsNegativeEta() {
		assertThatThrownBy(() -> new RealtimeArrivalObservation(
			"seoul-topis", "station-sangnoksu", "seoul-4", "1004", "1004000448", "4123",
			Instant.parse("2026-06-26T08:00:00Z"), Instant.parse("2026-06-26T08:00:00Z"),
			-1, 0, null, null, Instant.parse("2026-07-26T08:00:00Z")
		))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("rawEtaSeconds");
	}

	@Test
	@DisplayName("provider 원문 방향과 종착지는 DB 길이 한도를 넘을 수 없다")
	void rejectsOversizedRawLabelsBeforePersistence() {
		assertThatCode(() -> new RealtimeArrivalObservation(
			"seoul-topis", "station-sangnoksu", "seoul-4", "1004", "1004000448", "4123",
			Instant.parse("2026-06-26T08:00:00Z"), Instant.parse("2026-06-26T08:00:00Z"),
			180, 160, "😀".repeat(120), "😀".repeat(120), Instant.parse("2026-07-26T08:00:00Z")
		)).doesNotThrowAnyException();
		assertThatThrownBy(() -> new RealtimeArrivalObservation(
			"seoul-topis", "station-sangnoksu", "seoul-4", "1004", "1004000448", "4123",
			Instant.parse("2026-06-26T08:00:00Z"), Instant.parse("2026-06-26T08:00:00Z"),
			180, 160, "x".repeat(121), null, Instant.parse("2026-07-26T08:00:00Z")
		))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("rawDirection must not exceed 120 characters");
		assertThatThrownBy(() -> new RealtimeArrivalObservation(
			"seoul-topis", "station-sangnoksu", "seoul-4", "1004", "1004000448", "4123",
			Instant.parse("2026-06-26T08:00:00Z"), Instant.parse("2026-06-26T08:00:00Z"),
			180, 160, null, "x".repeat(121), Instant.parse("2026-07-26T08:00:00Z")
		))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("rawDestination must not exceed 120 characters");
	}

	@Test
	@DisplayName("DB migration도 원문 방향·종착지 120문자는 허용하고 121문자는 거부한다")
	void databaseEnforcesRawLabelLength() {
		assertThatCode(() -> insertRawLabels("x".repeat(120), "y".repeat(120)))
			.doesNotThrowAnyException();
		assertThatThrownBy(() -> insertRawLabels("x".repeat(121), "y".repeat(120)))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertRawLabels("x".repeat(120), "y".repeat(121)))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("DB도 직접 쓰기에서 음수 ETA를 거부한다")
	void databaseRejectsNegativeEta() {
		repository.saveAll(List.of(observation(
			Instant.parse("2026-06-26T08:00:00Z"),
			Instant.parse("2026-07-26T08:00:00Z")
		)));

		assertThatThrownBy(() -> jdbcTemplate.update(
			"UPDATE realtime_arrival_observations SET raw_eta_seconds = -1"
		)).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("null 입력은 거부하고 빈 batch는 no-op 처리한다")
	void validatesBatchInput() {
		assertThatThrownBy(() -> repository.saveAll(null))
			.isInstanceOf(NullPointerException.class)
			.hasMessageContaining("observations");

		repository.saveAll(List.of());
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM realtime_arrival_observations", Integer.class))
			.isZero();
	}

	@Test
	@DisplayName("만료 시각이 지난 archive row를 purge한다")
	void purgesExpiredObservations() {
		repository.saveAll(List.of(
			observation(Instant.parse("2026-06-01T08:00:00Z"), Instant.parse("2026-07-01T08:00:00Z")),
			observation(Instant.parse("2026-06-20T08:00:00Z"), Instant.parse("2026-07-20T08:00:00Z"))
		));

		assertThat(repository.deleteExpired(Instant.parse("2026-07-13T00:00:00Z"))).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM realtime_arrival_observations", Integer.class))
			.isEqualTo(1);
	}

	@Test
	@DisplayName("archive batch는 두 번째 row 실패 시 첫 번째 row도 rollback한다")
	void rollsBackWholeBatchOnConstraintFailure() throws NoSuchMethodException {
		assertThat(JdbcRealtimeArrivalArchiveRepository.class
			.getMethod("saveAll", List.class)
			.getAnnotation(Transactional.class))
			.isNotNull();

		RealtimeArrivalObservation invalid = new RealtimeArrivalObservation(
			"x".repeat(81), "station-sangnoksu", "seoul-4", "1004", "1004000448", "4123",
			Instant.parse("2026-06-26T08:00:00Z"), Instant.parse("2026-06-26T08:00:00Z"),
			180, 160, null, null, Instant.parse("2026-07-26T08:00:00Z")
		);

		assertThatThrownBy(() -> repository.saveAll(List.of(
			observation(Instant.parse("2026-06-26T08:00:00Z"), Instant.parse("2026-07-26T08:00:00Z")),
			invalid
		))).isInstanceOf(DataIntegrityViolationException.class);
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM realtime_arrival_observations", Integer.class))
			.isZero();
	}

	private RealtimeArrivalObservation observation(Instant backendReceivedAt, Instant retainedUntil) {
		return new RealtimeArrivalObservation(
			"seoul-topis",
			"station-sangnoksu",
			"seoul-4",
			"1004",
			"1004000448",
			"4123",
			Instant.parse("2026-06-26T08:00:00Z"),
			backendReceivedAt,
			180,
			160,
			"상행",
			"당고개",
			retainedUntil
		);
	}

	private void insertRawLabels(String rawDirection, String rawDestination) {
		jdbcTemplate.update(
			"""
				INSERT INTO realtime_arrival_observations (
					provider_id, station_id, line_id, provider_line_id, provider_station_id,
					train_no, provider_observed_at, backend_received_at,
					raw_eta_seconds, adjusted_eta_seconds, raw_direction, raw_destination, retained_until
				) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""",
			"seoul-topis", "station-sangnoksu", "seoul-4", "1004", "1004000448", "4123",
			Instant.parse("2026-06-26T08:00:00Z"), Instant.parse("2026-06-26T08:00:00Z"),
			180, 160, rawDirection, rawDestination, Instant.parse("2026-07-26T08:00:00Z")
		);
	}
}

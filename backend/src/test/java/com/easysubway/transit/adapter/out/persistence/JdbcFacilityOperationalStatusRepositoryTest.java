package com.easysubway.transit.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.transit.application.port.out.FacilityOperationalStatusStore.FeedApplyResult;
import com.easysubway.transit.application.port.out.FacilityOperationalStatusStore.FeedObservation;
import com.easysubway.transit.domain.FacilityOperationalState;
import com.easysubway.transit.domain.FacilityOperationalStatus;
import com.easysubway.transit.domain.FacilityStatusSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

@DisplayName("시설 운영 상태 JDBC 저장소")
class JdbcFacilityOperationalStatusRepositoryTest {

	private static final String FEED = "SEOUL_METRO_ELEVATOR";
	private static final String EXIT_1 = "smrt-elev:0201:2:1번 출입구";
	private static final String EXIT_2 = "smrt-elev:0201:2:2번 출입구";
	private static final Instant T0 = Instant.parse("2026-09-30T01:00:00Z");
	private static final Instant ADMIN_AT = Instant.parse("2026-09-30T01:00:30Z");
	private static final Instant T1 = Instant.parse("2026-09-30T01:01:00Z");
	private static final Instant T2 = Instant.parse("2026-09-30T01:02:00Z");

	private JdbcTemplate jdbcTemplate;
	private JdbcFacilityOperationalStatusRepository repository;

	@BeforeEach
	void setUp() {
		var dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:facility-status-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		new ResourceDatabasePopulator(
			new ClassPathResource("db/migration/h2/V76__facility_operational_status.sql")
		).execute(dataSource);
		jdbcTemplate = new JdbcTemplate(dataSource);
		repository = new JdbcFacilityOperationalStatusRepository(dataSource);
	}

	@Test
	@DisplayName("첫 수집은 원천 상태를 쓰고 심장박동을 기록한다")
	void firstCollectionWritesFeedStatusesAndHeartbeat() {
		assertThat(repository.lastSuccessfulCollectionAt(FEED)).isEmpty();

		FeedApplyResult result = repository.applyFeedCollection(FEED, List.of(
			new FeedObservation(EXIT_1, FacilityOperationalState.OPERATING, "M"),
			new FeedObservation(EXIT_2, FacilityOperationalState.OUT_OF_SERVICE, "S")
		), T0);

		assertThat(result).isEqualTo(new FeedApplyResult(2, 0));
		assertThat(repository.loadStatuses()).containsExactly(
			new FacilityOperationalStatus(EXIT_1, FacilityOperationalState.OPERATING, FacilityStatusSource.SEOUL_METRO_FEED, "M", T0, T0),
			new FacilityOperationalStatus(EXIT_2, FacilityOperationalState.OUT_OF_SERVICE, FacilityStatusSource.SEOUL_METRO_FEED, "S", T0, T0)
		);
		assertThat(repository.lastSuccessfulCollectionAt(FEED)).contains(T0);
		assertThat(repository.lastSuccessfulCollectionAt("OTHER_FEED")).isEmpty();
	}

	@Test
	@DisplayName("다음 수집은 원천 상태를 새 관측으로 갱신하고 심장박동을 앞으로만 옮긴다")
	void laterCollectionOverwritesFeedStatusAndAdvancesHeartbeat() {
		repository.applyFeedCollection(FEED, List.of(new FeedObservation(EXIT_1, FacilityOperationalState.OPERATING, "M")), T0);

		FeedApplyResult result = repository.applyFeedCollection(FEED, List.of(
			new FeedObservation(EXIT_1, FacilityOperationalState.OUT_OF_SERVICE, "T")
		), T1);
		FeedApplyResult stale = repository.applyFeedCollection(FEED, List.of(
			new FeedObservation(EXIT_1, FacilityOperationalState.OPERATING, "M")
		), T0);

		assertThat(result).isEqualTo(new FeedApplyResult(1, 0));
		assertThat(stale).isEqualTo(new FeedApplyResult(0, 0));
		assertThat(repository.loadStatuses()).containsExactly(
			new FacilityOperationalStatus(EXIT_1, FacilityOperationalState.OUT_OF_SERVICE, FacilityStatusSource.SEOUL_METRO_FEED, "T", T1, T1)
		);
		assertThat(repository.lastSuccessfulCollectionAt(FEED)).contains(T1);
	}

	@Test
	@DisplayName("관리자 고장 확인은 원천이 같은 '사용가능'을 계속 보내도 유지된다")
	void adminVerifiedOutageSurvivesUnchangedFeed() {
		repository.applyFeedCollection(FEED, List.of(new FeedObservation(EXIT_1, FacilityOperationalState.OPERATING, "M")), T0);

		assertThat(repository.recordAdminVerified(EXIT_1, FacilityOperationalState.OUT_OF_SERVICE, ADMIN_AT)).isTrue();
		FeedApplyResult result = repository.applyFeedCollection(FEED, List.of(
			new FeedObservation(EXIT_1, FacilityOperationalState.OPERATING, "M")
		), T1);

		assertThat(result).isEqualTo(new FeedApplyResult(0, 1));
		assertThat(repository.loadStatuses()).containsExactly(
			new FacilityOperationalStatus(EXIT_1, FacilityOperationalState.OUT_OF_SERVICE, FacilityStatusSource.ADMIN_VERIFIED, "M", ADMIN_AT, ADMIN_AT)
		);
		assertThat(repository.lastSuccessfulCollectionAt(FEED)).contains(T1);
	}

	@Test
	@DisplayName("반대 방향: 관리자 확인 뒤 원천 값이 바뀌면 원천 관측이 더 최근이라 원천 값으로 바뀐다")
	void feedChangeAfterAdminVerificationOverwrites() {
		repository.applyFeedCollection(FEED, List.of(new FeedObservation(EXIT_1, FacilityOperationalState.OUT_OF_SERVICE, "S")), T0);
		repository.recordAdminVerified(EXIT_1, FacilityOperationalState.OUT_OF_SERVICE, ADMIN_AT);

		FeedApplyResult result = repository.applyFeedCollection(FEED, List.of(
			new FeedObservation(EXIT_1, FacilityOperationalState.OPERATING, "M")
		), T1);

		assertThat(result).isEqualTo(new FeedApplyResult(1, 0));
		assertThat(repository.loadStatuses()).containsExactly(
			new FacilityOperationalStatus(EXIT_1, FacilityOperationalState.OPERATING, FacilityStatusSource.SEOUL_METRO_FEED, "M", T1, T1)
		);
	}

	@Test
	@DisplayName("원천 관측 전에 관리자 확인만 있던 시설은 첫 원천 코드를 기준값으로만 기록한다")
	void adminOnlyStatusRecordsFirstFeedCodeAsBaseline() {
		assertThat(repository.recordAdminVerified(EXIT_1, FacilityOperationalState.OUT_OF_SERVICE, ADMIN_AT)).isTrue();
		assertThat(repository.loadStatuses()).containsExactly(
			new FacilityOperationalStatus(EXIT_1, FacilityOperationalState.OUT_OF_SERVICE, FacilityStatusSource.ADMIN_VERIFIED, null, ADMIN_AT, ADMIN_AT)
		);

		FeedApplyResult baseline = repository.applyFeedCollection(FEED, List.of(
			new FeedObservation(EXIT_1, FacilityOperationalState.OPERATING, "M")
		), T1);
		FeedApplyResult changed = repository.applyFeedCollection(FEED, List.of(
			new FeedObservation(EXIT_1, FacilityOperationalState.OUT_OF_SERVICE, "S")
		), T2);

		assertThat(baseline).isEqualTo(new FeedApplyResult(0, 1));
		assertThat(changed).isEqualTo(new FeedApplyResult(1, 0));
		assertThat(repository.loadStatuses()).containsExactly(
			new FacilityOperationalStatus(EXIT_1, FacilityOperationalState.OUT_OF_SERVICE, FacilityStatusSource.SEOUL_METRO_FEED, "S", T2, T2)
		);
	}

	@Test
	@DisplayName("관리자 확인 시각보다 새 관측이 이미 있으면 관리자 기록을 쓰지 않는다")
	void adminRecordOlderThanExistingObservationIsRejected() {
		repository.applyFeedCollection(FEED, List.of(new FeedObservation(EXIT_1, FacilityOperationalState.OPERATING, "M")), T1);

		assertThat(repository.recordAdminVerified(EXIT_1, FacilityOperationalState.OUT_OF_SERVICE, ADMIN_AT)).isFalse();
		assertThat(repository.loadStatuses()).containsExactly(
			new FacilityOperationalStatus(EXIT_1, FacilityOperationalState.OPERATING, FacilityStatusSource.SEOUL_METRO_FEED, "M", T1, T1)
		);
	}

	@Test
	@DisplayName("빈 수집 결과도 원천 호출이 성공했다면 심장박동만 기록한다")
	void emptyObservationListOnlyAdvancesHeartbeat() {
		assertThat(repository.applyFeedCollection(FEED, List.of(), T0)).isEqualTo(new FeedApplyResult(0, 0));

		assertThat(repository.loadStatuses()).isEmpty();
		assertThat(repository.lastSuccessfulCollectionAt(FEED)).contains(T0);
	}

	@Test
	@DisplayName("쓰기 도중 실패하면 상태와 심장박동을 함께 되돌린다")
	void failedCollectionRollsBackStatusesAndHeartbeat() {
		repository.applyFeedCollection(FEED, List.of(new FeedObservation(EXIT_1, FacilityOperationalState.OPERATING, "M")), T0);

		assertThatThrownBy(() -> repository.applyFeedCollection(FEED, List.of(
			new FeedObservation(EXIT_2, FacilityOperationalState.OUT_OF_SERVICE, "S"),
			new FeedObservation(EXIT_2, FacilityOperationalState.OPERATING, "M")
		), T1)).isInstanceOf(DuplicateKeyException.class);

		assertThat(repository.loadStatuses()).extracting(FacilityOperationalStatus::facilityId).containsExactly(EXIT_1);
		assertThat(repository.lastSuccessfulCollectionAt(FEED)).contains(T0);
	}

	@Test
	@DisplayName("스키마는 운영 상태·출처를 정해진 값으로만 받는다")
	void schemaRejectsUnknownStatusOrSource() {
		OffsetDateTime at = OffsetDateTime.ofInstant(T0, ZoneOffset.UTC);
		assertThatThrownBy(() -> jdbcTemplate.update(
			"INSERT INTO facility_operational_status (facility_id, status, source, source_code, observed_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
			EXIT_1, "UNKNOWN", "SEOUL_METRO_FEED", "M", at, at
		)).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbcTemplate.update(
			"INSERT INTO facility_operational_status (facility_id, status, source, source_code, observed_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
			EXIT_1, "OPERATING", "USER_REPORTED", "M", at, at
		)).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbcTemplate.update(
			"INSERT INTO facility_status_feed_heartbeat (feed, last_success_at) VALUES (?, ?)",
			FEED, null
		)).isInstanceOf(DataIntegrityViolationException.class);
	}
}

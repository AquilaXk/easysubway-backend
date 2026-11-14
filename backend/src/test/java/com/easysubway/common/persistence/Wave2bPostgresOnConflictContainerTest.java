package com.easysubway.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.admin.authorization.AdminRbacRole;
import com.easysubway.admin.authorization.adapter.out.persistence.JdbcAdminRbacAuthorityRepository;
import com.easysubway.admin.code.adapter.out.persistence.JdbcAdminCommonCodeRepository;
import com.easysubway.admin.code.domain.AdminCommonCode;
import com.easysubway.admin.errors.adapter.out.persistence.JdbcErrorEventRepository;
import com.easysubway.admin.errors.domain.ErrorEvent;
import com.easysubway.admin.identity.adapter.out.persistence.JdbcAdminIdentityRepository;
import com.easysubway.admin.identity.domain.AdminIdentity;
import com.easysubway.admin.identity.domain.AdminIdentityAuthMethod;
import com.easysubway.admin.identity.domain.AdminIdentityRole;
import com.easysubway.admin.identity.domain.AdminIdentityStatus;
import com.easysubway.datapack.adapter.out.persistence.JdbcDataSourceSnapshotRepository;
import com.easysubway.datapack.domain.DataSourceSnapshot;
import com.easysubway.favorite.adapter.out.persistence.JdbcFavoriteFacilityRepository;
import com.easysubway.favorite.adapter.out.persistence.JdbcFavoriteStationRepository;
import com.easysubway.favorite.domain.FavoriteFacility;
import com.easysubway.favorite.domain.FavoriteStation;
import com.easysubway.journey.adapter.out.persistence.JdbcJourneySessionStore;
import com.easysubway.journey.application.JourneySessionStore;
import com.easysubway.notification.adapter.out.persistence.JdbcNotificationPreferenceRepository;
import com.easysubway.notification.adapter.out.persistence.JdbcPushNotificationOutboxRepository;
import com.easysubway.notification.domain.DevicePlatform;
import com.easysubway.notification.domain.NotificationSettings;
import com.easysubway.notification.domain.PushNotification;
import com.easysubway.notification.domain.PushNotificationStatus;
import com.easysubway.notification.domain.PushNotificationType;
import com.easysubway.notification.domain.RegisteredDevice;
import com.easysubway.profile.adapter.out.persistence.JdbcMobilityProfileRepository;
import com.easysubway.profile.domain.MobilityProfile;
import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.adapter.out.persistence.JdbcRouteSearchRepository;
import com.easysubway.route.application.port.out.SummarizeRouteSearchPort.RouteSearchQualitySignals;
import com.easysubway.route.domain.EtaSource;
import com.easysubway.route.domain.RouteSearchResult;
import com.easysubway.route.domain.RouteSearchStatus;
import com.easysubway.route.domain.RouteStep;
import com.easysubway.route.domain.RouteWarning;
import com.easysubway.route.domain.RouteWarningCode;
import com.easysubway.train.adapter.out.persistence.JdbcTrainSearchCache;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@DisplayName("Wave 2B PostgreSQL ON CONFLICT 트랜잭션 안전성 및 대시보드 JSONB 투영")
class Wave2bPostgresOnConflictContainerTest {

	@Container
	private static final PostgreSQLContainer<?> POSTGRES =
		new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

	private static DataSource dataSource;

	@BeforeAll
	static void migrateSchema() {
		dataSource = new DriverManagerDataSource(
			POSTGRES.getJdbcUrl(),
			POSTGRES.getUsername(),
			POSTGRES.getPassword()
		);
		Flyway.configure()
			.configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
			.dataSource(dataSource)
			.locations("classpath:db/migration/postgresql")
			.load()
			.migrate();
	}

	@Test
	@DisplayName("트랜잭션 내부 중복 upsert/insert 충돌 시 PostgreSQL 25P02 aborted transaction 없이 정상 커밋한다")
	void upsertAndConflictOperationsDoNotAbortActivePostgresTransaction() {
		var jdbcTemplate = new JdbcTemplate(dataSource);
		var txManager = new DataSourceTransactionManager(dataSource);
		var txTemplate = new TransactionTemplate(txManager);

		var mobilityRepo = new JdbcMobilityProfileRepository(dataSource);
		var favoriteStationRepo = new JdbcFavoriteStationRepository(dataSource);
		var favoriteFacilityRepo = new JdbcFavoriteFacilityRepository(dataSource);
		var notificationPrefRepo = new JdbcNotificationPreferenceRepository(dataSource);
		var pushOutboxRepo = new JdbcPushNotificationOutboxRepository(dataSource);
		var rbacRepo = new JdbcAdminRbacAuthorityRepository(dataSource);
		var identityRepo = new JdbcAdminIdentityRepository(dataSource);
		var codeRepo = new JdbcAdminCommonCodeRepository(dataSource);
		var errorRepo = new JdbcErrorEventRepository(dataSource);
		var snapshotRepo = new JdbcDataSourceSnapshotRepository(dataSource);
		var sessionStore = new JdbcJourneySessionStore(dataSource, txManager);
		var trainCache = new JdbcTrainSearchCache(dataSource);

		LocalDateTime now = LocalDateTime.of(2026, 7, 1, 9, 0);
		Instant instantNow = Instant.parse("2026-07-01T00:00:00Z");

		txTemplate.executeWithoutResult(status -> {
			mobilityRepo.saveProfile(new MobilityProfile(
				"pg-user-1", MobilityType.WHEELCHAIR, true, true, false, true, true, false, false, false, now));
			mobilityRepo.saveProfile(new MobilityProfile(
				"pg-user-1", MobilityType.SENIOR, false, false, true, false, true, true, false, false, now.plusMinutes(5)));

			favoriteStationRepo.saveFavoriteStation(new FavoriteStation("pg-user-1", "station-a", now));
			favoriteStationRepo.saveFavoriteStation(new FavoriteStation("pg-user-1", "station-a", now.plusMinutes(1)));

			favoriteFacilityRepo.saveFavoriteFacility(new FavoriteFacility("pg-user-1", "facility-1", now));
			favoriteFacilityRepo.saveFavoriteFacility(new FavoriteFacility("pg-user-1", "facility-1", now.plusMinutes(1)));

			notificationPrefRepo.saveNotificationSettings(
				new NotificationSettings("pg-user-1", true, true, false, true, now));
			notificationPrefRepo.saveNotificationSettings(
				new NotificationSettings("pg-user-1", false, true, true, false, now.plusMinutes(2)));
			notificationPrefRepo.saveRegisteredDevice(
				new RegisteredDevice("pg-user-1", DevicePlatform.ANDROID, "pg-device-token-1", now));
			notificationPrefRepo.saveRegisteredDevice(
				new RegisteredDevice("pg-user-1", DevicePlatform.ANDROID, "pg-device-token-1", now.plusMinutes(2)));

			var pending = new PushNotification(
				"pg-push-1", "pg-user-1", DevicePlatform.ANDROID, "pg-device-token-1",
				PushNotificationType.REPORT_STATUS, "알림", "내용", PushNotificationStatus.PENDING, null, now);
			pushOutboxRepo.savePushNotification(pending);
			pushOutboxRepo.savePushNotification(new PushNotification(
				"pg-push-1", "pg-user-1", DevicePlatform.ANDROID, "pg-device-token-1",
				PushNotificationType.REPORT_STATUS, "알림", "갱신", PushNotificationStatus.SENT, null, now.plusMinutes(1)));
			pushOutboxRepo.savePendingPushNotificationIfAbsent(new PushNotification(
				"pg-push-absent", "pg-user-1", DevicePlatform.ANDROID, "pg-device-token-1",
				PushNotificationType.DATA_QUALITY, "점검", "최초", PushNotificationStatus.PENDING, null, now));
			pushOutboxRepo.savePendingPushNotificationIfAbsent(new PushNotification(
				"pg-push-absent", "pg-user-1", DevicePlatform.ANDROID, "pg-device-token-1",
				PushNotificationType.DATA_QUALITY, "점검", "중복", PushNotificationStatus.PENDING, null, now.plusMinutes(1)));

			identityRepo.upsertBootstrap(new AdminIdentity(
				"pg-admin-1", "PG 관리자", "pg-admin@example.com", "{noop}pw",
				AdminIdentityAuthMethod.LOCAL, AdminIdentityRole.ADMIN, AdminIdentityStatus.ACTIVE,
				0, null, now, null, false, null, true, now, now));
			identityRepo.upsertBootstrap(new AdminIdentity(
				"pg-admin-1", "PG 관리자 수정", "pg-admin@example.com", "{noop}pw",
				AdminIdentityAuthMethod.LOCAL, AdminIdentityRole.ADMIN, AdminIdentityStatus.ACTIVE,
				0, null, now, null, false, null, true, now, now.plusMinutes(1)));

			rbacRepo.seedRole("pg-admin-1", AdminRbacRole.ADMIN_VIEWER);
			rbacRepo.seedRole("pg-admin-1", AdminRbacRole.ADMIN_VIEWER);

			jdbcTemplate.update("""
				INSERT INTO admin_common_code_groups (group_code, display_name, description, sort_order, enabled, created_at, updated_at)
				VALUES ('PG_GROUP', 'PG 테스트 그룹', NULL, 1, TRUE, ?, ?)
				ON CONFLICT (group_code) DO NOTHING
				""", now, now);
			codeRepo.saveCode(new AdminCommonCode("PG_GROUP", "C1", "초기명", null, 1, true, now, now));
			codeRepo.saveCode(new AdminCommonCode("PG_GROUP", "C1", "변경명", "설명", 2, true, now, now.plusMinutes(1)));

			var errorEvent = new ErrorEvent(
				null, instantNow, instantNow, "INTERNAL_ERROR", "SYSTEM", 500, "GET",
				"/api/pg-test", "java.lang.IllegalStateException", "e".repeat(64), "corr-1", 1L);
			errorRepo.upsertOccurrence(errorEvent);
			errorRepo.upsertOccurrence(new ErrorEvent(
				null, instantNow, instantNow.plusSeconds(10), "INTERNAL_ERROR", "SYSTEM", 500, "GET",
				"/api/pg-test", "java.lang.IllegalStateException", "e".repeat(64), "corr-2", 2L));

			var rootSnapshot = new DataSourceSnapshot(
				"pg-snap-1", "source-pg", "provider-pg", now, now, null, null, 1, 1,
				"a".repeat(64), "s3://pg/1.json", "b".repeat(64), "c".repeat(64),
				"LOCKED", "PASS", "PASS", "SUCCESS", true, true,
				null, null, null, now.plusDays(1), now.plusDays(30), "2026-07-01", "d".repeat(64));
			snapshotRepo.saveSnapshot(rootSnapshot);
			snapshotRepo.saveSnapshot(rootSnapshot);
			assertThat(snapshotRepo.insertEventIfAbsent(
				"evt-1", "source-pg", "pg-snap-1", "CREATE_LOCKED", "PASS", "ops", "initial", "idem-1", now
			)).isTrue();
			assertThat(snapshotRepo.insertEventIfAbsent(
				"evt-2", "source-pg", "pg-snap-1", "CREATE_LOCKED", "PASS", "ops", "duplicate", "idem-1", now
			)).isFalse();

			String tokenHash = "1".repeat(64);
			String nonceHash = "2".repeat(64);
			var session = new JourneySessionStore.Session(tokenHash, "journey:v3", instantNow, instantNow.plusSeconds(300));
			assertThat(sessionStore.claimNonceAndSaveSession(nonceHash, instantNow.plusSeconds(60), instantNow, session)).isTrue();
			assertThat(sessionStore.claimNonceAndSaveSession(nonceHash, instantNow.plusSeconds(60), instantNow, session)).isFalse();

			assertThat(trainCache.tryAcquireLease("pg-lease-key", "owner-a", instantNow, Duration.ofSeconds(30))).isTrue();
			assertThat(trainCache.tryAcquireLease("pg-lease-key", "owner-b", instantNow.plusSeconds(1), Duration.ofSeconds(30))).isFalse();
			trainCache.releaseLease("pg-lease-key", "owner-a");
			assertThat(trainCache.tryAcquireLease("pg-lease-key", "owner-b", instantNow.plusSeconds(2), Duration.ofSeconds(30))).isTrue();

			assertThat(status.isRollbackOnly()).isFalse();
		});

		assertThat(mobilityRepo.loadProfile("pg-user-1")).get()
			.extracting(MobilityProfile::mobilityType)
			.isEqualTo(MobilityType.SENIOR);
		assertThat(favoriteStationRepo.loadFavoriteStation("pg-user-1", "station-a")).isPresent();
		assertThat(favoriteFacilityRepo.loadFavoriteFacility("pg-user-1", "facility-1")).isPresent();
		assertThat(codeRepo.findCode("PG_GROUP", "C1")).get()
			.extracting(AdminCommonCode::displayName)
			.isEqualTo("변경명");
		assertThat(pushOutboxRepo.loadPushNotifications("pg-user-1"))
			.extracting(PushNotification::notificationId, PushNotification::status)
			.contains(
				org.assertj.core.groups.Tuple.tuple("pg-push-1", PushNotificationStatus.SENT),
				org.assertj.core.groups.Tuple.tuple("pg-push-absent", PushNotificationStatus.PENDING)
			);
	}

	@Test
	@DisplayName("PostgreSQL jsonb_array_elements LATERAL 대시보드 투영은 ETA 소스·경고 코드·LIMIT을 정확히 반환한다")
	void postgresJsonbDashboardProjectionMatchesExpectedQualitySignalsAndLimit() {
		var jdbcTemplate = new JdbcTemplate(dataSource);
		jdbcTemplate.update("DELETE FROM route_search_results");

		ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
		var repository = new JdbcRouteSearchRepository(dataSource, objectMapper);
		LocalDateTime base = LocalDateTime.of(2026, 7, 2, 10, 0);

		RouteSearchResult planned = routeWithSources(
			"rs-planned", base, List.of(EtaSource.PLANNED), List.of(RouteWarningCode.STAIR_ONLY_ACCESS));
		RouteSearchResult realtime = routeWithSources(
			"rs-realtime", base.plusMinutes(1), List.of(EtaSource.REALTIME), List.of());
		RouteSearchResult mixed = routeWithSources(
			"rs-mixed", base.plusMinutes(2),
			List.of(EtaSource.REALTIME, EtaSource.PLANNED), List.of());
		RouteSearchResult fallback = routeWithSources(
			"rs-fallback", base.plusMinutes(3),
			List.of(EtaSource.REALTIME, EtaSource.PLANNED_WITHOUT_REALTIME),
			List.of(RouteWarningCode.LOW_DATA_CONFIDENCE, RouteWarningCode.STALE_ACCESSIBILITY_DATA));
		RouteSearchResult estimate = routeWithSources(
			"rs-estimate", base.plusMinutes(4), List.of(EtaSource.STATIC_BACKEND_ESTIMATE), List.of());
		RouteSearchResult empty = new RouteSearchResult(
			"rs-empty", "s1", "출발", "s2", "도착", MobilityType.SENIOR, RouteSearchStatus.FOUND,
			"L1", "1호선", 10, List.of(), List.of(), List.of(), base.plusMinutes(5));

		for (RouteSearchResult r : List.of(planned, realtime, mixed, fallback, estimate, empty)) {
			repository.saveRouteSearch(r);
		}

		List<RouteSearchQualitySignals> signals = repository.loadRouteSearchQualitySignalsForDashboard();

		assertThat(signals).containsExactlyInAnyOrder(
			new RouteSearchQualitySignals(RouteSearchStatus.FOUND, planned.etaSource(), List.of(RouteWarningCode.STAIR_ONLY_ACCESS)),
			new RouteSearchQualitySignals(RouteSearchStatus.FOUND, realtime.etaSource(), List.of()),
			new RouteSearchQualitySignals(RouteSearchStatus.FOUND, mixed.etaSource(), List.of()),
			new RouteSearchQualitySignals(RouteSearchStatus.FOUND, fallback.etaSource(),
				List.of(RouteWarningCode.LOW_DATA_CONFIDENCE, RouteWarningCode.STALE_ACCESSIBILITY_DATA)),
			new RouteSearchQualitySignals(RouteSearchStatus.FOUND, estimate.etaSource(), List.of()),
			new RouteSearchQualitySignals(RouteSearchStatus.FOUND, empty.etaSource(), List.of())
		);
		assertThat(mixed.etaSource()).isEqualTo(EtaSource.MIXED);
		assertThat(fallback.etaSource()).isEqualTo(EtaSource.PLANNED_WITHOUT_REALTIME);
		assertThat(empty.etaSource()).isEqualTo(EtaSource.PLANNED);
	}

	private static RouteSearchResult routeWithSources(
		String id,
		LocalDateTime createdAt,
		List<EtaSource> etaSources,
		List<RouteWarningCode> warningCodes
	) {
		List<RouteStep> steps = new java.util.ArrayList<>();
		for (int i = 0; i < etaSources.size(); i++) {
			steps.add(new RouteStep(
				i + 1, "ride", "이동", "설명", "L4", "4호선", "S1", "S2",
				10, 0, false, "VERIFIED_STEP_FREE", false,
				etaSources.get(i).name(), "ESTIMATED_CONSTANT", "보통"
			));
		}
		List<RouteWarning> warnings = warningCodes.stream().map(RouteWarning::new).toList();
		return new RouteSearchResult(
			id, "S1", "상록수", "S2", "사당", MobilityType.WHEELCHAIR,
			RouteSearchStatus.FOUND, "L4", "4호선", 15, steps, warnings, List.of(), createdAt);
	}
}

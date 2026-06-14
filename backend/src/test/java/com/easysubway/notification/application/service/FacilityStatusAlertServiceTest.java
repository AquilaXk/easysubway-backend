package com.easysubway.notification.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.easysubway.favorite.adapter.out.persistence.InMemoryFavoriteFacilityRepository;
import com.easysubway.favorite.adapter.out.persistence.InMemoryFavoriteRouteRepository;
import com.easysubway.favorite.adapter.out.persistence.InMemoryFavoriteStationRepository;
import com.easysubway.favorite.domain.FavoriteFacility;
import com.easysubway.favorite.domain.FavoriteRoute;
import com.easysubway.favorite.domain.FavoriteStation;
import com.easysubway.notification.adapter.out.persistence.InMemoryNotificationPreferenceRepository;
import com.easysubway.notification.adapter.out.persistence.InMemoryPushNotificationOutboxRepository;
import com.easysubway.notification.application.port.in.FacilityStatusChangedAlertCommand;
import com.easysubway.notification.application.port.in.RegisterDeviceCommand;
import com.easysubway.notification.application.port.in.SaveNotificationSettingsCommand;
import com.easysubway.notification.domain.DevicePlatform;
import com.easysubway.notification.domain.PushNotificationType;
import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.domain.RouteSearchResult;
import com.easysubway.route.domain.RouteSearchStatus;
import com.easysubway.transit.application.port.out.LoadTransitMasterPort;
import com.easysubway.transit.adapter.out.persistence.InMemoryTransitMasterRepository;
import com.easysubway.transit.domain.AccessibilityFacility;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import com.easysubway.transit.domain.AccessibilityFacilityType;
import com.easysubway.transit.domain.DataConfidenceLevel;
import com.easysubway.transit.domain.DataQualityLevel;
import com.easysubway.transit.domain.DataSourceType;
import com.easysubway.transit.domain.Station;
import com.easysubway.transit.domain.StationExit;
import com.easysubway.transit.domain.StationLine;
import com.easysubway.transit.domain.SubwayLine;
import com.easysubway.transit.domain.TransitOperator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("시설 상태 변경 알림 서비스")
class FacilityStatusAlertServiceTest {

	private static final Clock CLOCK = Clock.fixed(
		Instant.parse("2026-06-14T02:00:00Z"),
		ZoneId.of("Asia/Seoul")
	);

	private final InMemoryTransitMasterRepository transitRepository = new InMemoryTransitMasterRepository();
	private final InMemoryFavoriteFacilityRepository favoriteFacilityRepository = new InMemoryFavoriteFacilityRepository();
	private final InMemoryFavoriteStationRepository favoriteStationRepository = new InMemoryFavoriteStationRepository();
	private final InMemoryFavoriteRouteRepository favoriteRouteRepository = new InMemoryFavoriteRouteRepository();
	private final InMemoryNotificationPreferenceRepository notificationPreferenceRepository =
		new InMemoryNotificationPreferenceRepository();
	private final InMemoryPushNotificationOutboxRepository outboxRepository =
		new InMemoryPushNotificationOutboxRepository();
	private final NotificationPreferenceService preferenceService = new NotificationPreferenceService(
		notificationPreferenceRepository,
		notificationPreferenceRepository,
		notificationPreferenceRepository,
		CLOCK
	);
	private final PushNotificationDispatchService dispatchService = new PushNotificationDispatchService(
		notificationPreferenceRepository,
		outboxRepository,
		CLOCK
	);
	private final FacilityStatusAlertService service = new FacilityStatusAlertService(
		transitRepository,
		favoriteFacilityRepository,
		favoriteStationRepository,
		favoriteRouteRepository,
		dispatchService
	);

	@Test
	@DisplayName("시설 상태 변경은 시설과 역과 경로 즐겨찾기 사용자에게 알림 후보를 만든다")
	void facilityStatusChangeCreatesFavoriteUserPushCandidates() {
		registerDevice("facility-user", DevicePlatform.ANDROID, "facility-token");
		registerDevice("station-user", DevicePlatform.IOS, "station-token");
		registerDevice("route-user", DevicePlatform.ANDROID, "route-token");

		favoriteFacilityRepository.saveFavoriteFacility(new FavoriteFacility(
			"facility-user",
			"facility-sangnoksu-elevator-1",
			LocalDateTime.of(2026, 6, 14, 9, 0)
		));
		favoriteStationRepository.saveFavoriteStation(new FavoriteStation(
			"station-user",
			"station-sangnoksu",
			LocalDateTime.of(2026, 6, 14, 9, 1)
		));
		favoriteRouteRepository.saveFavoriteRoute(new FavoriteRoute(
			"route-user",
			routeSearchResult(),
			LocalDateTime.of(2026, 6, 14, 9, 2)
		));

		service.alertFacilityStatusChanged(new FacilityStatusChangedAlertCommand(
			"facility-sangnoksu-elevator-1",
			AccessibilityFacilityStatus.BROKEN
		));

		assertThat(outboxRepository.loadPushNotifications("facility-user"))
			.extracting("type")
			.containsExactly(PushNotificationType.FAVORITE_STATION_FACILITY);
		assertThat(outboxRepository.loadPushNotifications("station-user"))
			.extracting("body")
			.containsExactly("상록수역 1번 출구 엘리베이터 상태가 고장으로 바뀌었습니다.");
		assertThat(outboxRepository.loadPushNotifications("route-user"))
			.extracting("type")
			.containsExactly(PushNotificationType.FAVORITE_ROUTE_FACILITY);
	}

	@Test
	@DisplayName("같은 사용자의 시설과 역 중복 즐겨찾기는 알림 후보를 한 번만 만든다")
	void duplicateFacilityAndStationFavoritesCreateOneStationFacilityNotification() {
		registerDevice("duplicate-user", DevicePlatform.ANDROID, "duplicate-token");
		favoriteFacilityRepository.saveFavoriteFacility(new FavoriteFacility(
			"duplicate-user",
			"facility-sangnoksu-elevator-1",
			LocalDateTime.of(2026, 6, 14, 9, 0)
		));
		favoriteStationRepository.saveFavoriteStation(new FavoriteStation(
			"duplicate-user",
			"station-sangnoksu",
			LocalDateTime.of(2026, 6, 14, 9, 1)
		));

		service.alertFacilityStatusChanged(new FacilityStatusChangedAlertCommand(
			"facility-sangnoksu-elevator-1",
			AccessibilityFacilityStatus.UNDER_CONSTRUCTION
		));

		assertThat(outboxRepository.loadPushNotifications("duplicate-user"))
			.extracting("type")
			.containsExactly(PushNotificationType.FAVORITE_STATION_FACILITY);
	}

	@Test
	@DisplayName("사용자가 꺼둔 시설 알림은 outbox 후보를 만들지 않는다")
	void disabledFavoriteStationFacilityAlertDoesNotCreateOutboxCandidate() {
		registerDevice("disabled-user", DevicePlatform.IOS, "disabled-token");
		preferenceService.saveNotificationSettings(new SaveNotificationSettingsCommand(
			"disabled-user",
			false,
			true,
			true,
			false
		));
		favoriteStationRepository.saveFavoriteStation(new FavoriteStation(
			"disabled-user",
			"station-sangnoksu",
			LocalDateTime.of(2026, 6, 14, 9, 0)
		));

		service.alertFacilityStatusChanged(new FacilityStatusChangedAlertCommand(
			"facility-sangnoksu-elevator-1",
			AccessibilityFacilityStatus.CLOSED
		));

		assertThat(outboxRepository.loadPushNotifications("disabled-user")).isEmpty();
	}

	@Test
	@DisplayName("비활성 역에 속한 시설 상태 변경도 알림 경로에서 요청을 실패시키지 않는다")
	void inactiveStationFacilityStatusChangeDoesNotFailAlertPath() {
		var service = new FacilityStatusAlertService(
			new TransitMasterPortWithInactiveStationFacility(),
			favoriteFacilityRepository,
			favoriteStationRepository,
			favoriteRouteRepository,
			dispatchService
		);

		assertThatNoException().isThrownBy(() -> service.alertFacilityStatusChanged(
			new FacilityStatusChangedAlertCommand("facility-inactive-elevator", AccessibilityFacilityStatus.BROKEN)
		));
	}

	private void registerDevice(String userId, DevicePlatform platform, String token) {
		preferenceService.registerDevice(new RegisterDeviceCommand(userId, platform, token));
	}

	private RouteSearchResult routeSearchResult() {
		return new RouteSearchResult(
			"route-search-1",
			"station-sangnoksu",
			"상록수",
			"station-sadang",
			"사당",
			MobilityType.SENIOR,
			RouteSearchStatus.FOUND,
			"seoul-4",
			"수도권 4호선",
			92,
			List.of(),
			List.of(),
			List.of(),
			LocalDateTime.of(2026, 6, 14, 9, 2)
		);
	}

	private static class TransitMasterPortWithInactiveStationFacility implements LoadTransitMasterPort {

		@Override
		public List<TransitOperator> loadOperators() {
			return List.of(new TransitOperator(
				"closed-operator",
				"운영 종료 기관",
				"수도권",
				"https://example.com",
				"https://example.com/help",
				DataSourceType.OFFICIAL_FILE,
				false
			));
		}

		@Override
		public List<SubwayLine> loadLines() {
			return List.of(new SubwayLine("closed-line", "closed-operator", "운영 종료 노선", "#999999", "수도권", "C", false));
		}

		@Override
		public List<Station> loadStations() {
			return List.of(new Station(
				"station-inactive",
				"운영종료역",
				"Inactive",
				"수도권",
				new BigDecimal("37.000000"),
				new BigDecimal("127.000000"),
				DataQualityLevel.LEVEL_1,
				LocalDate.of(2026, 6, 12),
				false
			));
		}

		@Override
		public List<StationLine> loadStationLines() {
			return List.of(new StationLine("station-inactive", "closed-line", "000", 0, "운영 종료"));
		}

		@Override
		public List<StationExit> loadStationExits() {
			return List.of();
		}

		@Override
		public List<AccessibilityFacility> loadAccessibilityFacilities() {
			return List.of(new AccessibilityFacility(
				"facility-inactive-elevator",
				"station-inactive",
				null,
				AccessibilityFacilityType.ELEVATOR,
				"엘리베이터",
				"지상",
				"대합실",
				new BigDecimal("37.000000"),
				new BigDecimal("127.000000"),
				"운영 종료 역에 남아 있는 시설 데이터입니다.",
				AccessibilityFacilityStatus.NORMAL,
				DataConfidenceLevel.HIGH,
				LocalDate.of(2026, 6, 12)
			));
		}
	}
}

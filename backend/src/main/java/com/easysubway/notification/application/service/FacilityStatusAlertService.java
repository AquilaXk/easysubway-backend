package com.easysubway.notification.application.service;

import com.easysubway.favorite.application.port.out.LoadFavoriteFacilityAlertTargetPort;
import com.easysubway.favorite.application.port.out.LoadFavoriteRouteAlertTargetPort;
import com.easysubway.favorite.application.port.out.LoadFavoriteStationAlertTargetPort;
import com.easysubway.notification.application.port.in.DispatchPushNotificationCommand;
import com.easysubway.notification.application.port.in.FacilityStatusAlertUseCase;
import com.easysubway.notification.application.port.in.FacilityStatusChangedAlertCommand;
import com.easysubway.notification.application.port.in.PushNotificationDispatchUseCase;
import com.easysubway.notification.domain.PushNotificationType;
import com.easysubway.transit.application.port.out.LoadTransitMasterPort;
import com.easysubway.transit.domain.AccessibilityFacility;
import com.easysubway.transit.domain.AccessibilityFacilityNotFoundException;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import com.easysubway.transit.domain.Station;
import com.easysubway.transit.domain.StationNotFoundException;
import java.util.LinkedHashSet;
import org.springframework.stereotype.Service;

@Service
public class FacilityStatusAlertService implements FacilityStatusAlertUseCase {

	private final LoadTransitMasterPort loadTransitMasterPort;
	private final LoadFavoriteFacilityAlertTargetPort loadFavoriteFacilityAlertTargetPort;
	private final LoadFavoriteStationAlertTargetPort loadFavoriteStationAlertTargetPort;
	private final LoadFavoriteRouteAlertTargetPort loadFavoriteRouteAlertTargetPort;
	private final PushNotificationDispatchUseCase pushNotificationDispatchUseCase;

	public FacilityStatusAlertService(
		LoadTransitMasterPort loadTransitMasterPort,
		LoadFavoriteFacilityAlertTargetPort loadFavoriteFacilityAlertTargetPort,
		LoadFavoriteStationAlertTargetPort loadFavoriteStationAlertTargetPort,
		LoadFavoriteRouteAlertTargetPort loadFavoriteRouteAlertTargetPort,
		PushNotificationDispatchUseCase pushNotificationDispatchUseCase
	) {
		this.loadTransitMasterPort = loadTransitMasterPort;
		this.loadFavoriteFacilityAlertTargetPort = loadFavoriteFacilityAlertTargetPort;
		this.loadFavoriteStationAlertTargetPort = loadFavoriteStationAlertTargetPort;
		this.loadFavoriteRouteAlertTargetPort = loadFavoriteRouteAlertTargetPort;
		this.pushNotificationDispatchUseCase = pushNotificationDispatchUseCase;
	}

	@Override
	public void alertFacilityStatusChanged(FacilityStatusChangedAlertCommand command) {
		AccessibilityFacility facility = loadFacility(command.facilityId());
		Station station = loadStation(facility.stationId());

		// 시설과 역 즐겨찾기는 같은 알림 유형이므로 사용자별 중복 발송을 먼저 제거한다.
		var stationFacilityUsers = new LinkedHashSet<String>();
		stationFacilityUsers.addAll(loadFavoriteFacilityAlertTargetPort.loadUserIdsByFavoriteFacilityId(facility.id()));
		stationFacilityUsers.addAll(loadFavoriteStationAlertTargetPort.loadUserIdsByFavoriteStationId(facility.stationId()));

		String stationFacilityBody = "%s역 %s 상태가 %s으로 바뀌었습니다."
			.formatted(station.nameKo(), facility.name(), statusLabel(command.status()));
		stationFacilityUsers.forEach(userId -> dispatch(
			userId,
			PushNotificationType.FAVORITE_STATION_FACILITY,
			"시설 상태 변경",
			stationFacilityBody
		));

		// 경로 즐겨찾기는 출발역이나 도착역이 영향을 받을 때 별도 알림 설정을 따른다.
		String routeBody = "%s역 %s 상태가 %s으로 바뀌어 즐겨찾기 경로 확인이 필요합니다."
			.formatted(station.nameKo(), facility.name(), statusLabel(command.status()));
		loadFavoriteRouteAlertTargetPort.loadUserIdsByRouteStationId(facility.stationId())
			.stream()
			.forEach(userId -> dispatch(
				userId,
				PushNotificationType.FAVORITE_ROUTE_FACILITY,
				"즐겨찾기 경로 시설 변경",
				routeBody
			));
	}

	private void dispatch(String userId, PushNotificationType type, String title, String body) {
		pushNotificationDispatchUseCase.dispatch(new DispatchPushNotificationCommand(userId, type, title, body));
	}

	private AccessibilityFacility loadFacility(String facilityId) {
		return loadTransitMasterPort.loadAccessibilityFacility(facilityId)
			.orElseThrow(AccessibilityFacilityNotFoundException::new);
	}

	private Station loadStation(String stationId) {
		return loadTransitMasterPort.loadStation(stationId)
			.orElseThrow(StationNotFoundException::new);
	}

	private String statusLabel(AccessibilityFacilityStatus status) {
		return switch (status) {
			case NORMAL -> "정상";
			case BROKEN -> "고장";
			case UNDER_CONSTRUCTION -> "공사중";
			case CLOSED -> "폐쇄";
			case UNKNOWN -> "확인 필요";
			case USER_REPORTED -> "제보 접수";
			case ADMIN_VERIFIED -> "관리자 확인";
		};
	}
}

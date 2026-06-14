package com.easysubway.favorite.application.service;

import com.easysubway.favorite.application.port.in.FavoriteFacilityUseCase;
import com.easysubway.favorite.application.port.in.ListFavoriteFacilitiesCommand;
import com.easysubway.favorite.application.port.in.RemoveFavoriteFacilityCommand;
import com.easysubway.favorite.application.port.in.SaveFavoriteFacilityCommand;
import com.easysubway.favorite.application.port.out.DeleteFavoriteFacilityPort;
import com.easysubway.favorite.application.port.out.LoadFavoriteFacilityPort;
import com.easysubway.favorite.application.port.out.SaveFavoriteFacilityPort;
import com.easysubway.favorite.domain.FavoriteFacility;
import com.easysubway.favorite.domain.FavoriteFacilityNotFoundException;
import com.easysubway.favorite.domain.FavoriteFacilityWithDetails;
import com.easysubway.favorite.domain.InvalidFavoriteFacilityException;
import com.easysubway.transit.application.port.out.LoadTransitMasterPort;
import com.easysubway.transit.domain.AccessibilityFacility;
import com.easysubway.transit.domain.Station;
import com.easysubway.transit.domain.StationNotFoundException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class FavoriteFacilityService implements FavoriteFacilityUseCase {

	private final LoadFavoriteFacilityPort loadFavoriteFacilityPort;
	private final SaveFavoriteFacilityPort saveFavoriteFacilityPort;
	private final DeleteFavoriteFacilityPort deleteFavoriteFacilityPort;
	private final LoadTransitMasterPort loadTransitMasterPort;
	private final Clock clock;

	@Autowired
	public FavoriteFacilityService(
		LoadFavoriteFacilityPort loadFavoriteFacilityPort,
		SaveFavoriteFacilityPort saveFavoriteFacilityPort,
		DeleteFavoriteFacilityPort deleteFavoriteFacilityPort,
		LoadTransitMasterPort loadTransitMasterPort
	) {
		this(
			loadFavoriteFacilityPort,
			saveFavoriteFacilityPort,
			deleteFavoriteFacilityPort,
			loadTransitMasterPort,
			Clock.systemDefaultZone()
		);
	}

	public FavoriteFacilityService(
		LoadFavoriteFacilityPort loadFavoriteFacilityPort,
		SaveFavoriteFacilityPort saveFavoriteFacilityPort,
		DeleteFavoriteFacilityPort deleteFavoriteFacilityPort,
		LoadTransitMasterPort loadTransitMasterPort,
		Clock clock
	) {
		this.loadFavoriteFacilityPort = loadFavoriteFacilityPort;
		this.saveFavoriteFacilityPort = saveFavoriteFacilityPort;
		this.deleteFavoriteFacilityPort = deleteFavoriteFacilityPort;
		this.loadTransitMasterPort = loadTransitMasterPort;
		this.clock = clock;
	}

	@Override
	public List<FavoriteFacilityWithDetails> listFavoriteFacilities(ListFavoriteFacilitiesCommand command) {
		requireUserId(command.userId());
		return loadFavoriteFacilityPort.loadFavoriteFacilities(command.userId())
			.stream()
			.sorted(Comparator.comparing(FavoriteFacility::addedAt))
			.map(this::withDetails)
			.toList();
	}

	@Override
	public FavoriteFacilityWithDetails saveFavoriteFacility(SaveFavoriteFacilityCommand command) {
		requireUserId(command.userId());
		requireFacilityId(command.facilityId());
		// 고장 또는 확인 필요 시설도 추적 대상이므로 상태값은 저장 가능 여부에 쓰지 않는다.
		AccessibilityFacility facility = loadFacility(command.facilityId());
		Station station = loadActiveStation(facility.stationId());

		FavoriteFacility favoriteFacility = loadFavoriteFacilityPort
			.loadFavoriteFacility(command.userId(), command.facilityId())
			.orElseGet(() -> saveFavoriteFacilityPort.saveFavoriteFacility(new FavoriteFacility(
				command.userId(),
				command.facilityId(),
				LocalDateTime.now(clock)
			)));

		return new FavoriteFacilityWithDetails(favoriteFacility, facility, station);
	}

	@Override
	public void removeFavoriteFacility(RemoveFavoriteFacilityCommand command) {
		requireUserId(command.userId());
		requireFacilityId(command.facilityId());
		deleteFavoriteFacilityPort.deleteFavoriteFacility(command.userId(), command.facilityId());
	}

	private FavoriteFacilityWithDetails withDetails(FavoriteFacility favoriteFacility) {
		AccessibilityFacility facility = loadFacility(favoriteFacility.facilityId());
		return new FavoriteFacilityWithDetails(
			favoriteFacility,
			facility,
			loadActiveStation(facility.stationId())
		);
	}

	private AccessibilityFacility loadFacility(String facilityId) {
		return loadTransitMasterPort.loadAccessibilityFacilities()
			.stream()
			.filter(facility -> facility.id().equals(facilityId))
			.findFirst()
			.orElseThrow(FavoriteFacilityNotFoundException::new);
	}

	private Station loadActiveStation(String stationId) {
		return loadTransitMasterPort.loadStations()
			.stream()
			.filter(station -> station.id().equals(stationId))
			.filter(Station::active)
			.findFirst()
			.orElseThrow(StationNotFoundException::new);
	}

	private void requireUserId(String userId) {
		if (userId == null || userId.isBlank()) {
			throw new InvalidFavoriteFacilityException("사용자 식별자가 필요합니다.");
		}
	}

	private void requireFacilityId(String facilityId) {
		if (facilityId == null || facilityId.isBlank()) {
			throw new InvalidFavoriteFacilityException("시설 식별자가 필요합니다.");
		}
	}
}

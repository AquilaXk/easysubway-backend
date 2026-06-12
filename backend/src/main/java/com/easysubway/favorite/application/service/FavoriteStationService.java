package com.easysubway.favorite.application.service;

import com.easysubway.favorite.application.port.in.FavoriteStationUseCase;
import com.easysubway.favorite.application.port.in.ListFavoriteStationsCommand;
import com.easysubway.favorite.application.port.in.RemoveFavoriteStationCommand;
import com.easysubway.favorite.application.port.in.SaveFavoriteStationCommand;
import com.easysubway.favorite.application.port.out.DeleteFavoriteStationPort;
import com.easysubway.favorite.application.port.out.LoadFavoriteStationPort;
import com.easysubway.favorite.application.port.out.SaveFavoriteStationPort;
import com.easysubway.favorite.domain.FavoriteStation;
import com.easysubway.favorite.domain.FavoriteStationWithDetails;
import com.easysubway.favorite.domain.InvalidFavoriteStationException;
import com.easysubway.transit.application.port.out.LoadTransitMasterPort;
import com.easysubway.transit.domain.Station;
import com.easysubway.transit.domain.StationLine;
import com.easysubway.transit.domain.StationLineSummary;
import com.easysubway.transit.domain.StationNotFoundException;
import com.easysubway.transit.domain.StationWithLines;
import com.easysubway.transit.domain.SubwayLine;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class FavoriteStationService implements FavoriteStationUseCase {

	private final LoadFavoriteStationPort loadFavoriteStationPort;
	private final SaveFavoriteStationPort saveFavoriteStationPort;
	private final DeleteFavoriteStationPort deleteFavoriteStationPort;
	private final LoadTransitMasterPort loadTransitMasterPort;
	private final Clock clock;

	@Autowired
	public FavoriteStationService(
		LoadFavoriteStationPort loadFavoriteStationPort,
		SaveFavoriteStationPort saveFavoriteStationPort,
		DeleteFavoriteStationPort deleteFavoriteStationPort,
		LoadTransitMasterPort loadTransitMasterPort
	) {
		this(
			loadFavoriteStationPort,
			saveFavoriteStationPort,
			deleteFavoriteStationPort,
			loadTransitMasterPort,
			Clock.systemDefaultZone()
		);
	}

	public FavoriteStationService(
		LoadFavoriteStationPort loadFavoriteStationPort,
		SaveFavoriteStationPort saveFavoriteStationPort,
		DeleteFavoriteStationPort deleteFavoriteStationPort,
		LoadTransitMasterPort loadTransitMasterPort,
		Clock clock
	) {
		this.loadFavoriteStationPort = loadFavoriteStationPort;
		this.saveFavoriteStationPort = saveFavoriteStationPort;
		this.deleteFavoriteStationPort = deleteFavoriteStationPort;
		this.loadTransitMasterPort = loadTransitMasterPort;
		this.clock = clock;
	}

	@Override
	public List<FavoriteStationWithDetails> listFavoriteStations(ListFavoriteStationsCommand command) {
		requireUserId(command.userId());
		return loadFavoriteStationPort.loadFavoriteStations(command.userId())
			.stream()
			.sorted(Comparator.comparing(FavoriteStation::addedAt))
			.map(this::withDetails)
			.toList();
	}

	@Override
	public FavoriteStationWithDetails saveFavoriteStation(SaveFavoriteStationCommand command) {
		requireUserId(command.userId());
		requireStationId(command.stationId());
		loadActiveStation(command.stationId());

		FavoriteStation favoriteStation = loadFavoriteStationPort
			.loadFavoriteStation(command.userId(), command.stationId())
			.orElseGet(() -> saveFavoriteStationPort.saveFavoriteStation(new FavoriteStation(
				command.userId(),
				command.stationId(),
				LocalDateTime.now(clock)
			)));

		return withDetails(favoriteStation);
	}

	@Override
	public void removeFavoriteStation(RemoveFavoriteStationCommand command) {
		requireUserId(command.userId());
		requireStationId(command.stationId());
		deleteFavoriteStationPort.deleteFavoriteStation(command.userId(), command.stationId());
	}

	private FavoriteStationWithDetails withDetails(FavoriteStation favoriteStation) {
		return new FavoriteStationWithDetails(
			favoriteStation,
			withLines(loadActiveStation(favoriteStation.stationId()))
		);
	}

	private Station loadActiveStation(String stationId) {
		return loadTransitMasterPort.loadStations()
			.stream()
			.filter(station -> station.id().equals(stationId))
			.filter(Station::active)
			.findFirst()
			.orElseThrow(StationNotFoundException::new);
	}

	private StationWithLines withLines(Station station) {
		Map<String, SubwayLine> linesById = loadTransitMasterPort.loadLines()
			.stream()
			.filter(SubwayLine::active)
			.collect(Collectors.toMap(SubwayLine::id, Function.identity()));

		List<StationLineSummary> lines = loadTransitMasterPort.loadStationLines()
			.stream()
			.filter(stationLine -> stationLine.stationId().equals(station.id()))
			.filter(stationLine -> linesById.containsKey(stationLine.lineId()))
			.map(stationLine -> toSummary(stationLine, linesById))
			.toList();

		return new StationWithLines(station, lines);
	}

	private StationLineSummary toSummary(StationLine stationLine, Map<String, SubwayLine> linesById) {
		return StationLineSummary.of(linesById.get(stationLine.lineId()), stationLine);
	}

	private void requireUserId(String userId) {
		if (userId == null || userId.isBlank()) {
			throw new InvalidFavoriteStationException("사용자 식별자가 필요합니다.");
		}
	}

	private void requireStationId(String stationId) {
		if (stationId == null || stationId.isBlank()) {
			throw new InvalidFavoriteStationException("역 식별자가 필요합니다.");
		}
	}
}

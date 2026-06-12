package com.easysubway.favorite.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.favorite.adapter.out.persistence.InMemoryFavoriteStationRepository;
import com.easysubway.favorite.application.port.in.ListFavoriteStationsCommand;
import com.easysubway.favorite.application.port.in.RemoveFavoriteStationCommand;
import com.easysubway.favorite.application.port.in.SaveFavoriteStationCommand;
import com.easysubway.favorite.domain.FavoriteStation;
import com.easysubway.favorite.domain.InvalidFavoriteStationException;
import com.easysubway.transit.adapter.out.persistence.InMemoryTransitMasterRepository;
import com.easysubway.transit.domain.StationNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("즐겨찾기 역 서비스")
class FavoriteStationServiceTest {

	private final InMemoryFavoriteStationRepository favoriteStationRepository =
		new InMemoryFavoriteStationRepository();
	private final FavoriteStationService service = new FavoriteStationService(
		favoriteStationRepository,
		favoriteStationRepository,
		favoriteStationRepository,
		new InMemoryTransitMasterRepository(),
		Clock.fixed(Instant.parse("2026-06-12T00:00:00Z"), ZoneId.of("Asia/Seoul"))
	);

	@Test
	@DisplayName("활성 역은 사용자별 즐겨찾기에 한 번만 저장된다")
	void saveFavoriteStationStoresActiveStationOnce() {
		var favorite = service.saveFavoriteStation(new SaveFavoriteStationCommand(
			"anonymous-user-1",
			"station-sangnoksu"
		));
		var duplicated = service.saveFavoriteStation(new SaveFavoriteStationCommand(
			"anonymous-user-1",
			"station-sangnoksu"
		));

		assertThat(favorite.favoriteStation().userId()).isEqualTo("anonymous-user-1");
		assertThat(favorite.favoriteStation().stationId()).isEqualTo("station-sangnoksu");
		assertThat(favorite.favoriteStation().addedAt()).isEqualTo(LocalDateTime.of(2026, 6, 12, 9, 0));
		assertThat(favorite.station().station().nameKo()).isEqualTo("상록수");
		assertThat(duplicated).isEqualTo(favorite);
		assertThat(service.listFavoriteStations(new ListFavoriteStationsCommand("anonymous-user-1")))
			.containsExactly(favorite);
	}

	@Test
	@DisplayName("삭제 요청은 같은 사용자와 같은 역의 즐겨찾기만 제거한다")
	void removeFavoriteStationDeletesOnlyRequestedStation() {
		service.saveFavoriteStation(new SaveFavoriteStationCommand("anonymous-user-1", "station-sangnoksu"));
		service.saveFavoriteStation(new SaveFavoriteStationCommand("anonymous-user-1", "station-sadang"));

		service.removeFavoriteStation(new RemoveFavoriteStationCommand("anonymous-user-1", "station-sangnoksu"));

		assertThat(service.listFavoriteStations(new ListFavoriteStationsCommand("anonymous-user-1")))
			.extracting(favorite -> favorite.favoriteStation().stationId())
			.containsExactly("station-sadang");
	}

	@Test
	@DisplayName("즐겨찾기 저장은 존재하는 활성 역을 요구한다")
	void saveFavoriteStationRequiresExistingActiveStation() {
		assertThatThrownBy(() -> service.saveFavoriteStation(new SaveFavoriteStationCommand(
			"anonymous-user-1",
			"missing-station"
		)))
			.isInstanceOf(StationNotFoundException.class)
			.hasMessage("역 정보를 찾을 수 없습니다.");
	}

	@Test
	@DisplayName("즐겨찾기 명령은 사용자와 역 식별자를 요구한다")
	void favoriteStationCommandsRequireUserAndStation() {
		assertThatThrownBy(() -> service.listFavoriteStations(new ListFavoriteStationsCommand("")))
			.isInstanceOf(InvalidFavoriteStationException.class)
			.hasMessage("사용자 식별자가 필요합니다.");

		assertThatThrownBy(() -> service.saveFavoriteStation(new SaveFavoriteStationCommand(
			"anonymous-user-1",
			""
		)))
			.isInstanceOf(InvalidFavoriteStationException.class)
			.hasMessage("역 식별자가 필요합니다.");
	}

	@Test
	@DisplayName("즐겨찾기 도메인은 비어 있는 사용자와 역 정보를 허용하지 않는다")
	void favoriteStationDomainRejectsInvalidState() {
		assertThatThrownBy(() -> new FavoriteStation("", "station-sangnoksu", LocalDateTime.now()))
			.isInstanceOf(InvalidFavoriteStationException.class)
			.hasMessage("사용자 식별자가 필요합니다.");
		assertThatThrownBy(() -> new FavoriteStation("anonymous-user-1", "", LocalDateTime.now()))
			.isInstanceOf(InvalidFavoriteStationException.class)
			.hasMessage("역 식별자가 필요합니다.");
		assertThatThrownBy(() -> new FavoriteStation("anonymous-user-1", "station-sangnoksu", null))
			.isInstanceOf(InvalidFavoriteStationException.class)
			.hasMessage("추가 시각이 필요합니다.");
	}
}

package com.easysubway.favorite.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.favorite.adapter.out.persistence.InMemoryFavoriteFacilityRepository;
import com.easysubway.favorite.application.port.in.ListFavoriteFacilitiesCommand;
import com.easysubway.favorite.application.port.in.RemoveFavoriteFacilityCommand;
import com.easysubway.favorite.application.port.in.SaveFavoriteFacilityCommand;
import com.easysubway.favorite.domain.FavoriteFacility;
import com.easysubway.favorite.domain.FavoriteFacilityNotFoundException;
import com.easysubway.favorite.domain.InvalidFavoriteFacilityException;
import com.easysubway.transit.application.port.out.LoadTransitMasterPort;
import com.easysubway.transit.adapter.out.persistence.InMemoryTransitMasterRepository;
import com.easysubway.transit.domain.AccessibilityFacility;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import com.easysubway.transit.domain.AccessibilityFacilityType;
import com.easysubway.transit.domain.DataConfidenceLevel;
import com.easysubway.transit.domain.Station;
import com.easysubway.transit.domain.StationExit;
import com.easysubway.transit.domain.StationLine;
import com.easysubway.transit.domain.StationNotFoundException;
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

@DisplayName("즐겨찾기 시설 서비스")
class FavoriteFacilityServiceTest {

	private final InMemoryFavoriteFacilityRepository favoriteFacilityRepository =
		new InMemoryFavoriteFacilityRepository();
	private final FavoriteFacilityService service = new FavoriteFacilityService(
		favoriteFacilityRepository,
		favoriteFacilityRepository,
		favoriteFacilityRepository,
		new InMemoryTransitMasterRepository(),
		Clock.fixed(Instant.parse("2026-06-12T00:00:00Z"), ZoneId.of("Asia/Seoul"))
	);

	@Test
	@DisplayName("시설 즐겨찾기는 사용자별로 한 번만 저장되고 역 정보와 함께 조회된다")
	void saveFavoriteFacilityStoresFacilityOnceWithStationDetails() {
		var favorite = service.saveFavoriteFacility(new SaveFavoriteFacilityCommand(
			"anonymous-user-1",
			"facility-sangnoksu-elevator-1"
		));
		var duplicated = service.saveFavoriteFacility(new SaveFavoriteFacilityCommand(
			"anonymous-user-1",
			"facility-sangnoksu-elevator-1"
		));

		assertThat(favorite.favoriteFacility().userId()).isEqualTo("anonymous-user-1");
		assertThat(favorite.favoriteFacility().facilityId()).isEqualTo("facility-sangnoksu-elevator-1");
		assertThat(favorite.favoriteFacility().addedAt()).isEqualTo(LocalDateTime.of(2026, 6, 12, 9, 0));
		assertThat(favorite.facility().name()).isEqualTo("1번 출구 엘리베이터");
		assertThat(favorite.station().nameKo()).isEqualTo("상록수");
		assertThat(duplicated).isEqualTo(favorite);
		assertThat(service.listFavoriteFacilities(new ListFavoriteFacilitiesCommand("anonymous-user-1")))
			.containsExactly(favorite);
	}

	@Test
	@DisplayName("시설 즐겨찾기 삭제는 같은 사용자와 같은 시설만 제거한다")
	void removeFavoriteFacilityDeletesOnlyRequestedFacility() {
		service.saveFavoriteFacility(new SaveFavoriteFacilityCommand(
			"anonymous-user-1",
			"facility-sangnoksu-elevator-1"
		));
		service.saveFavoriteFacility(new SaveFavoriteFacilityCommand(
			"anonymous-user-1",
			"facility-sangnoksu-escalator-1"
		));

		service.removeFavoriteFacility(new RemoveFavoriteFacilityCommand(
			"anonymous-user-1",
			"facility-sangnoksu-elevator-1"
		));

		assertThat(service.listFavoriteFacilities(new ListFavoriteFacilitiesCommand("anonymous-user-1")))
			.extracting(favorite -> favorite.favoriteFacility().facilityId())
			.containsExactly("facility-sangnoksu-escalator-1");
	}

	@Test
	@DisplayName("시설 즐겨찾기 저장은 존재하는 시설을 요구한다")
	void saveFavoriteFacilityRequiresExistingFacility() {
		assertThatThrownBy(() -> service.saveFavoriteFacility(new SaveFavoriteFacilityCommand(
			"anonymous-user-1",
			"missing-facility"
		)))
			.isInstanceOf(FavoriteFacilityNotFoundException.class)
			.hasMessage("시설 정보를 찾을 수 없습니다.");
	}

	@Test
	@DisplayName("시설의 연결 역이 없으면 즐겨찾기를 저장하지 않는다")
	void saveFavoriteFacilityRejectsFacilityWithoutActiveStationBeforePersisting() {
		var repository = new InMemoryFavoriteFacilityRepository();
		var serviceWithBrokenTransitData = new FavoriteFacilityService(
			repository,
			repository,
			repository,
			new BrokenFacilityTransitMasterPort(),
			Clock.fixed(Instant.parse("2026-06-12T00:00:00Z"), ZoneId.of("Asia/Seoul"))
		);

		assertThatThrownBy(() -> serviceWithBrokenTransitData.saveFavoriteFacility(new SaveFavoriteFacilityCommand(
			"anonymous-user-1",
			"facility-broken-station"
		)))
			.isInstanceOf(StationNotFoundException.class)
			.hasMessage("역 정보를 찾을 수 없습니다.");

		assertThat(repository.loadFavoriteFacilities("anonymous-user-1")).isEmpty();
	}

	@Test
	@DisplayName("시설 즐겨찾기 명령은 사용자와 시설 식별자를 요구한다")
	void favoriteFacilityCommandsRequireUserAndFacility() {
		assertThatThrownBy(() -> service.listFavoriteFacilities(new ListFavoriteFacilitiesCommand("")))
			.isInstanceOf(InvalidFavoriteFacilityException.class)
			.hasMessage("사용자 식별자가 필요합니다.");

		assertThatThrownBy(() -> service.saveFavoriteFacility(new SaveFavoriteFacilityCommand(
			"anonymous-user-1",
			""
		)))
			.isInstanceOf(InvalidFavoriteFacilityException.class)
			.hasMessage("시설 식별자가 필요합니다.");
	}

	@Test
	@DisplayName("시설 즐겨찾기 도메인은 비어 있는 사용자와 시설 정보를 허용하지 않는다")
	void favoriteFacilityDomainRejectsInvalidState() {
		assertThatThrownBy(() -> new FavoriteFacility("", "facility-sangnoksu-elevator-1", LocalDateTime.now()))
			.isInstanceOf(InvalidFavoriteFacilityException.class)
			.hasMessage("사용자 식별자가 필요합니다.");
		assertThatThrownBy(() -> new FavoriteFacility("anonymous-user-1", "", LocalDateTime.now()))
			.isInstanceOf(InvalidFavoriteFacilityException.class)
			.hasMessage("시설 식별자가 필요합니다.");
		assertThatThrownBy(() -> new FavoriteFacility(
			"anonymous-user-1",
			"facility-sangnoksu-elevator-1",
			null
		))
			.isInstanceOf(InvalidFavoriteFacilityException.class)
			.hasMessage("추가 시각이 필요합니다.");
	}

	private static final class BrokenFacilityTransitMasterPort implements LoadTransitMasterPort {

		@Override
		public List<TransitOperator> loadOperators() {
			return List.of();
		}

		@Override
		public List<SubwayLine> loadLines() {
			return List.of();
		}

		@Override
		public List<Station> loadStations() {
			return List.of();
		}

		@Override
		public List<StationLine> loadStationLines() {
			return List.of();
		}

		@Override
		public List<StationExit> loadStationExits() {
			return List.of();
		}

		@Override
		public List<AccessibilityFacility> loadAccessibilityFacilities() {
			return List.of(new AccessibilityFacility(
				"facility-broken-station",
				"station-missing",
				null,
				AccessibilityFacilityType.ELEVATOR,
				"연결 역 누락 엘리베이터",
				"지상",
				"대합실",
				new BigDecimal("37.300000"),
				new BigDecimal("126.800000"),
				"연결 역 데이터가 없는 테스트 시설입니다.",
				AccessibilityFacilityStatus.NORMAL,
				DataConfidenceLevel.HIGH,
				LocalDate.of(2026, 6, 12)
			));
		}
	}
}

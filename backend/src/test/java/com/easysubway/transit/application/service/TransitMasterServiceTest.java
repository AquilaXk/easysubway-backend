package com.easysubway.transit.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.transit.adapter.out.persistence.InMemoryTransitMasterRepository;
import com.easysubway.transit.application.port.in.StationSearchCommand;
import com.easysubway.transit.application.port.in.UpdateAccessibilityFacilityStatusCommand;
import com.easysubway.transit.application.port.out.LoadTransitMasterPort;
import com.easysubway.notification.application.port.in.FacilityStatusAlertUseCase;
import com.easysubway.notification.application.port.in.FacilityStatusChangedAlertCommand;
import com.easysubway.transit.domain.AccessibilityFacility;
import com.easysubway.transit.domain.AccessibilityFacilityNotFoundException;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import com.easysubway.transit.domain.AccessibilityFacilityType;
import com.easysubway.transit.domain.DataConfidenceLevel;
import com.easysubway.transit.domain.DataQualityLevel;
import com.easysubway.transit.domain.DataSourceType;
import com.easysubway.transit.domain.InvalidAccessibilityFacilityException;
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
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("도시철도 마스터데이터 서비스")
class TransitMasterServiceTest {

	private final InMemoryTransitMasterRepository transitRepository = new InMemoryTransitMasterRepository();
	private final TransitMasterService service = new TransitMasterService(transitRepository, transitRepository);

	@Test
	@DisplayName("활성 운영기관 마스터데이터를 반환한다")
	void listOperatorsReturnsActiveMasterData() {
		var operators = service.listOperators();

		assertThat(operators)
			.extracting("id")
			.contains("seoul-metro", "korail");
	}

	@Test
	@DisplayName("운영기관 식별자로 노선을 필터링한다")
	void listLinesCanFilterByOperatorId() {
		var lines = service.listLines("korail");

		assertThat(lines)
			.extracting("id")
			.containsExactly("suin-bundang");
	}

	@Test
	@DisplayName("역 검색은 한글 이름과 영문 이름을 모두 찾는다")
	void searchStationsMatchesKoreanAndEnglishNames() {
		var koreanMatches = service.searchStations(new StationSearchCommand("상록수", null));
		var englishMatches = service.searchStations(new StationSearchCommand("sang", null));

		assertThat(koreanMatches).hasSize(1);
		assertThat(englishMatches).hasSize(1);
		assertThat(koreanMatches.getFirst().station().dataQualityLevel()).isEqualTo(DataQualityLevel.LEVEL_1);
	}

	@Test
	@DisplayName("역 검색 응답에서 비활성 노선은 제외한다")
	void searchStationsExcludesInactiveLinesFromStationResponses() {
		var serviceWithInactiveLine = new TransitMasterService(
			new TransitMasterPortWithInactiveLine(),
			(facilityId, status, updatedAt) -> {
			}
		);

		var stations = serviceWithInactiveLine.searchStations(new StationSearchCommand("상록수", null));
		var inactiveLineMatches = serviceWithInactiveLine.searchStations(new StationSearchCommand("상록수", "closed-line"));

		assertThat(stations).hasSize(1);
		assertThat(stations.getFirst().lines())
			.extracting("id")
			.containsExactly("seoul-4");
		assertThat(inactiveLineMatches).isEmpty();
	}

	@Test
	@DisplayName("존재하지 않는 역 상세 조회는 도메인 예외를 던진다")
	void getStationThrowsDomainExceptionForUnknownStation() {
		assertThatThrownBy(() -> service.getStation("missing"))
			.isInstanceOf(StationNotFoundException.class)
			.hasMessage("역 정보를 찾을 수 없습니다.");
	}

	@Test
	@DisplayName("역 출구 목록은 접근성 신호를 함께 반환한다")
	void listStationExitsReturnsExitAccessibilitySignals() {
		var exits = service.listStationExits("station-sangnoksu");

		assertThat(exits)
			.extracting("id")
			.containsExactly("exit-sangnoksu-1", "exit-sangnoksu-2");
		assertThat(exits.getFirst().exitNumber()).isEqualTo("1");
		assertThat(exits.getFirst().hasElevatorConnection()).isTrue();
		assertThat(exits.getFirst().hasStairOnlyPath()).isFalse();
		assertThat(exits.getFirst().dataConfidence()).isEqualTo(DataConfidenceLevel.HIGH);
	}

	@Test
	@DisplayName("역 시설 목록은 상태와 데이터 신뢰도를 함께 반환한다")
	void listStationFacilitiesReturnsStatusAndConfidence() {
		var facilities = service.listStationFacilities("station-sangnoksu");

		assertThat(facilities)
			.extracting("id")
			.containsExactly("facility-sangnoksu-elevator-1", "facility-sangnoksu-escalator-1", "facility-sangnoksu-accessible-toilet");
		assertThat(facilities.getFirst().type()).isEqualTo(AccessibilityFacilityType.ELEVATOR);
		assertThat(facilities.getFirst().status()).isEqualTo(AccessibilityFacilityStatus.NORMAL);
		assertThat(facilities.getFirst().exitId()).isEqualTo("exit-sangnoksu-1");
		assertThat(facilities.getFirst().dataConfidence()).isEqualTo(DataConfidenceLevel.HIGH);
	}

	@Test
	@DisplayName("역 출구와 시설 목록은 존재하는 역을 요구한다")
	void stationExitsAndFacilitiesRequireExistingStation() {
		assertThatThrownBy(() -> service.listStationExits("missing"))
			.isInstanceOf(StationNotFoundException.class)
			.hasMessage("역 정보를 찾을 수 없습니다.");
		assertThatThrownBy(() -> service.listStationFacilities("missing"))
			.isInstanceOf(StationNotFoundException.class)
			.hasMessage("역 정보를 찾을 수 없습니다.");
	}

	@Test
	@DisplayName("관리자는 시설 상태를 수정하고 갱신일을 기록한다")
	void updateFacilityStatusStoresStatusAndUpdatedDate() {
		var repository = new InMemoryTransitMasterRepository();
		var service = new TransitMasterService(
			repository,
			repository,
			Clock.fixed(Instant.parse("2026-06-14T00:00:00Z"), ZoneId.of("Asia/Seoul"))
		);

		var updated = service.updateFacilityStatus(new UpdateAccessibilityFacilityStatusCommand(
			"facility-sangnoksu-elevator-1",
			AccessibilityFacilityStatus.BROKEN,
			"admin-user"
		));

		assertThat(updated.status()).isEqualTo(AccessibilityFacilityStatus.BROKEN);
		assertThat(updated.lastUpdatedAt()).isEqualTo(LocalDate.of(2026, 6, 14));
		assertThat(service.listStationFacilities("station-sangnoksu").getFirst().status())
			.isEqualTo(AccessibilityFacilityStatus.BROKEN);
	}

	@Test
	@DisplayName("관리자 시설 상태 수정은 즐겨찾기 알림을 요청한다")
	void updateFacilityStatusRequestsFavoriteAlert() {
		var repository = new InMemoryTransitMasterRepository();
		var alertUseCase = new RecordingFacilityStatusAlertUseCase();
		var service = new TransitMasterService(
			repository,
			repository,
			alertUseCase,
			Clock.fixed(Instant.parse("2026-06-14T00:00:00Z"), ZoneId.of("Asia/Seoul"))
		);

		service.updateFacilityStatus(new UpdateAccessibilityFacilityStatusCommand(
			"facility-sangnoksu-elevator-1",
			AccessibilityFacilityStatus.BROKEN,
			"admin-user"
		));

		assertThat(alertUseCase.commands)
			.extracting(FacilityStatusChangedAlertCommand::facilityId)
			.containsExactly("facility-sangnoksu-elevator-1");
		assertThat(alertUseCase.commands)
			.extracting(FacilityStatusChangedAlertCommand::status)
			.containsExactly(AccessibilityFacilityStatus.BROKEN);
	}

	@Test
	@DisplayName("관리자 시설 상태 수정은 값이 같으면 즐겨찾기 알림을 요청하지 않는다")
	void updateFacilityStatusDoesNotRequestFavoriteAlertWhenStatusIsSame() {
		var repository = new InMemoryTransitMasterRepository();
		var alertUseCase = new RecordingFacilityStatusAlertUseCase();
		var service = new TransitMasterService(
			repository,
			repository,
			alertUseCase,
			Clock.fixed(Instant.parse("2026-06-14T00:00:00Z"), ZoneId.of("Asia/Seoul"))
		);

		service.updateFacilityStatus(new UpdateAccessibilityFacilityStatusCommand(
			"facility-sangnoksu-elevator-1",
			AccessibilityFacilityStatus.NORMAL,
			"admin-user"
		));

		assertThat(alertUseCase.commands).isEmpty();
	}

	@Test
	@DisplayName("시설 상태 수정은 상태값과 관리자 식별자를 요구한다")
	void updateFacilityStatusRequiresStatusAndReviewer() {
		var repository = new InMemoryTransitMasterRepository();
		var service = new TransitMasterService(
			repository,
			repository,
			Clock.fixed(Instant.parse("2026-06-14T00:00:00Z"), ZoneId.of("Asia/Seoul"))
		);

		assertThatThrownBy(() -> service.updateFacilityStatus(new UpdateAccessibilityFacilityStatusCommand(
			"facility-sangnoksu-elevator-1",
			null,
			"admin-user"
		)))
			.isInstanceOf(InvalidAccessibilityFacilityException.class)
			.hasMessage("시설 상태를 선택해야 합니다.");

		assertThatThrownBy(() -> service.updateFacilityStatus(new UpdateAccessibilityFacilityStatusCommand(
			"facility-sangnoksu-elevator-1",
			AccessibilityFacilityStatus.BROKEN,
			""
		)))
			.isInstanceOf(InvalidAccessibilityFacilityException.class)
			.hasMessage("수정자 식별자가 필요합니다.");
	}

	@Test
	@DisplayName("존재하지 않는 시설 상태는 수정할 수 없다")
	void updateFacilityStatusRequiresExistingFacility() {
		var repository = new InMemoryTransitMasterRepository();
		var service = new TransitMasterService(
			repository,
			repository,
			Clock.fixed(Instant.parse("2026-06-14T00:00:00Z"), ZoneId.of("Asia/Seoul"))
		);

		assertThatThrownBy(() -> service.updateFacilityStatus(new UpdateAccessibilityFacilityStatusCommand(
			"missing-facility",
			AccessibilityFacilityStatus.BROKEN,
			"admin-user"
		)))
			.isInstanceOf(AccessibilityFacilityNotFoundException.class)
			.hasMessage("시설 정보를 찾을 수 없습니다.");
	}

	private static class TransitMasterPortWithInactiveLine implements LoadTransitMasterPort {

		@Override
		public List<TransitOperator> loadOperators() {
			return List.of(
				new TransitOperator(
					"seoul-metro",
					"서울교통공사",
					"수도권",
					"https://www.seoulmetro.co.kr",
					"https://www.seoulmetro.co.kr/kr/customerMain.do",
					DataSourceType.OFFICIAL_FILE,
					true
				)
			);
		}

		@Override
		public List<SubwayLine> loadLines() {
			return List.of(
				new SubwayLine("seoul-4", "seoul-metro", "수도권 4호선", "#00A5DE", "수도권", "4", true),
				new SubwayLine("closed-line", "seoul-metro", "운영 종료 노선", "#999999", "수도권", "C", false)
			);
		}

		@Override
		public List<Station> loadStations() {
			return List.of(
				new Station(
					"station-sangnoksu",
					"상록수",
					"Sangnoksu",
					"수도권",
					new BigDecimal("37.302795"),
					new BigDecimal("126.866489"),
					DataQualityLevel.LEVEL_1,
					LocalDate.of(2026, 6, 12),
					true
				)
			);
		}

		@Override
		public List<StationLine> loadStationLines() {
			return List.of(
				new StationLine("station-sangnoksu", "seoul-4", "448", 48, "당고개 방면 / 오이도 방면"),
				new StationLine("station-sangnoksu", "closed-line", "999", 99, "운영 종료")
			);
		}

		@Override
		public List<StationExit> loadStationExits() {
			return List.of();
		}

		@Override
		public List<AccessibilityFacility> loadAccessibilityFacilities() {
			return List.of();
		}
	}

	private static class RecordingFacilityStatusAlertUseCase implements FacilityStatusAlertUseCase {

		private final java.util.List<FacilityStatusChangedAlertCommand> commands = new java.util.ArrayList<>();

		@Override
		public void alertFacilityStatusChanged(FacilityStatusChangedAlertCommand command) {
			commands.add(command);
		}
	}
}

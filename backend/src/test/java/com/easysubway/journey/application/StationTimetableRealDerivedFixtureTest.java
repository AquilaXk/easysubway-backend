package com.easysubway.journey.application;

import static com.easysubway.journey.application.StationTimetableRealDerivedFixture.GANGNAM;
import static com.easysubway.journey.application.StationTimetableRealDerivedFixture.GURO;
import static com.easysubway.journey.application.StationTimetableRealDerivedFixture.GWANGMYEONG;
import static com.easysubway.journey.application.StationTimetableRealDerivedFixture.GYODAE;
import static com.easysubway.journey.application.StationTimetableRealDerivedFixture.INCHEON;
import static com.easysubway.journey.application.StationTimetableRealDerivedFixture.LINE_1;
import static com.easysubway.journey.application.StationTimetableRealDerivedFixture.LINE_2;
import static com.easysubway.journey.application.StationTimetableRealDerivedFixture.SEONGSU;
import static com.easysubway.journey.application.StationTimetableRealDerivedFixture.SINDORIM;
import static com.easysubway.journey.application.StationTimetableRealDerivedFixture.YEOKSAM;
import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.journey.application.StationTimetableSearchService.DayType;
import com.easysubway.journey.application.StationTimetableSearchService.Departure;
import com.easysubway.journey.application.StationTimetableSearchService.DirectionGroup;
import com.easysubway.journey.application.StationTimetableSearchService.SearchRequest;
import com.easysubway.journey.application.StationTimetableSearchService.Selector;
import com.easysubway.journey.application.StationTimetableSearchService.SourceIdentity;
import com.easysubway.journey.application.StationTimetableSnapshotPort.StationTimetableSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** #476 F5: 실제 seq126 번들 행으로 다음 정차역 묶음과 중복 판정을 CI에서 고정한다. */
class StationTimetableRealDerivedFixtureTest {

	private static final LocalDate SERVICE_DATE = LocalDate.parse("2026-10-06");
	private static StationTimetableSearchService service;

	@BeforeAll
	static void loadFixture() {
		var fixture = StationTimetableRealDerivedFixture.load();
		var snapshot = new StationTimetableSnapshot(new SourceIdentity(StationTimetableRealDerivedFixture.SOURCE_BUNDLE_ID,
			StationTimetableRealDerivedFixture.SOURCE_TIMETABLE_SHA256, "sha256:" + "0".repeat(64), "0".repeat(64), "1".repeat(64),
			"2".repeat(64), Instant.parse("2026-10-10T00:05:31.571Z")), fixture.timetable(), fixture.canonicalStationLines());
		service = new StationTimetableSearchService(() -> snapshot, Clock.fixed(Instant.parse("2026-10-05T15:00:00Z"), ZoneOffset.UTC));
	}

	@Test
	void gangnamLineTwoSplitsTheLoopIntoTwoNextStationGroupsWhoseTerminalsAreTheSame() {
		var result = service.search(new SearchRequest(GANGNAM, LINE_2, new Selector.ServiceDateSelector(SERVICE_DATE)));

		assertThat(result.resolvedDayType()).isEqualTo(DayType.WEEKDAY);
		assertThat(result.directionGroups()).extracting(DirectionGroup::nextStationId).containsExactly(YEOKSAM, GYODAE);
		assertThat(result.directionGroups()).extracting(group -> group.departures().size()).containsExactly(240, 239);
		assertThat(result.directionGroups()).allSatisfy(group -> {
			assertThat(group.directionName()).isNull();
			// 양방향 모두 성수행이 있다. headsign으로 묶었다면 두 방향이 한 그룹으로 섞였다.
			assertThat(group.departures()).extracting(Departure::terminalStationId).contains(SEONGSU);
		});
	}

	@Test
	void sindorimLineOneListsSameSecondSameNextStationTrainsToDifferentTerminals() {
		var result = service.search(new SearchRequest(SINDORIM, LINE_1, new Selector.ServiceDateSelector(SERVICE_DATE)));

		var towardGuro = result.directionGroups().stream().filter(group -> GURO.equals(group.nextStationId())).findFirst().orElseThrow();
		// 07:14(26040초)에 광명행과 인천행이 같은 다음 역(구로)으로 출발한다. 종착역이 달라 서로 다른 열차다.
		assertThat(towardGuro.departures()).filteredOn(departure -> departure.secondsFromServiceDayStart() == 26_040)
			.extracting(Departure::terminalStationId).containsExactlyInAnyOrder(GWANGMYEONG, INCHEON);
	}
}

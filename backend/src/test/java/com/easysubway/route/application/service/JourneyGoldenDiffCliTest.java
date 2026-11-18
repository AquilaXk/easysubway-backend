package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.journey.bundle.JourneyGoldenBundleLoader;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("번들 diff 도구는 같은 질의로 번들을 돌려 golden과 비교한다")
class JourneyGoldenDiffCliTest {

	private static final String BUNDLE_PAYLOAD_SHA256 = "f".repeat(64);

	@Test
	@DisplayName("golden과 같은 입력이면 입력 지문만 다르고 모든 행이 같다")
	void sameInputDiffersOnlyInTheFixturePin() {
		String report = JourneyGoldenDiffCli.compare(bundle(CapitalRealDerivedFixture.load()), null);

		assertThat(report).contains("번들 test-bundle release 7 (" + capitalQueryCount() + "개 수도권 질의, 서비스일 2026-10-06)")
			.contains("변경 0행, 추가 0행, 삭제 0행")
			.contains("[입력 변경] capital: ").contains("pin=" + BUNDLE_PAYLOAD_SHA256)
			.doesNotContain("[변경]");
	}

	@Test
	@DisplayName("번들의 열차가 빠지면 영향받은 질의가 차이로 나온다")
	void removedTripsShowUpAsChangedRows() {
		RouteTimetable source = CapitalRealDerivedFixture.load();
		Set<String> removed = source.transitTrips().stream().limit(source.transitTrips().size() / 2)
			.map(trip -> trip.id()).collect(Collectors.toSet());

		String report = JourneyGoldenDiffCli.compare(bundle(JourneyEngineSyntheticBundles.without(source, removed, Set.of())), null);

		assertThat(report).contains("[변경] ").doesNotContain("변경 0행");
	}

	@Test
	@DisplayName("서비스일을 바꾸면 그 날짜로 돌린다")
	void serviceDateOverrideIsUsed() {
		String report = JourneyGoldenDiffCli.compare(bundle(CapitalRealDerivedFixture.load()), LocalDate.of(2026, 10, 7));

		assertThat(report).contains("서비스일 2026-10-07");
	}

	@Test
	@DisplayName("인자가 맞지 않으면 사용법과 함께 실패한다")
	void rejectsUnexpectedArguments() {
		assertThatThrownBy(() -> JourneyGoldenDiffCli.main(new String[0]))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("usage");
		assertThatThrownBy(() -> JourneyGoldenDiffCli.main(new String[] {"bundle", "--unknown", "x"}))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("usage");
	}

	private static long capitalQueryCount() {
		return JourneyGoldenCorpus.load().queries().stream()
			.filter(query -> JourneyGoldenFixtures.CAPITAL.equals(query.fixture())).count();
	}

	private static JourneyGoldenBundleLoader.Loaded bundle(RouteTimetable timetable) {
		return new JourneyGoldenBundleLoader.Loaded(timetable, "test-bundle", 7, BUNDLE_PAYLOAD_SHA256);
	}
}

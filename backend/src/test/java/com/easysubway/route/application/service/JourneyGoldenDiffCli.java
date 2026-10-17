package com.easysubway.route.application.service;

import com.easysubway.journey.bundle.JourneyGoldenBundleLoader;
import com.easysubway.route.application.service.JourneyGoldenReport.FixtureHeader;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * #514: 임의 번들 디렉터리를 대표 질의 golden과 비교해 차이 보고를 출력한다. 후보 데이터팩이 같은 질의의 경로를 어떻게
 * 바꾸는지 보는 도구다. Gradle 태스크 {@code journeyGoldenDiff}로 실행한다.
 *
 * <p>비교 대상은 수도권 실데이터 질의({@code capital})뿐이다. 합성 fixture 질의는 번들과 무관하므로 제외한다. 서비스일은
 * golden의 기준일을 쓰고, 후보 번들이 그 날짜를 덮지 않으면 {@code --service-date}로 바꾼다. 바꾸면 그 날짜의 시간표
 * 차이도 함께 보인다. 차이가 있어도 종료 코드는 0이다. 이 출력은 판정이 아니라 PR에 붙이는 증거다.</p>
 */
public final class JourneyGoldenDiffCli {

	private static final String SERVICE_DATE_OPTION = "--service-date";

	private JourneyGoldenDiffCli() {
	}

	public static void main(String[] arguments) {
		if (arguments.length != 1 && !(arguments.length == 3 && SERVICE_DATE_OPTION.equals(arguments[1]))) {
			throw new IllegalArgumentException("usage: <server-route-bundle directory> [" + SERVICE_DATE_OPTION + " YYYY-MM-DD]");
		}
		System.out.print(compare(JourneyGoldenBundleLoader.load(Path.of(arguments[0])),
			arguments.length == 3 ? LocalDate.parse(arguments[2]) : null));
	}

	static String compare(JourneyGoldenBundleLoader.Loaded bundle, LocalDate serviceDateOrNull) {
		var capitalQueries = JourneyGoldenCorpus.load().queries().stream()
			.filter(query -> JourneyGoldenFixtures.CAPITAL.equals(query.fixture())).toList();
		JourneyGoldenReport golden = capitalOnly(JourneyGoldenReport.readResource(), capitalQueries);
		LocalDate serviceDate = serviceDateOrNull != null ? serviceDateOrNull : serviceDate(golden);

		var fixture = new JourneyGoldenFixtures.Fixture(JourneyGoldenFixtures.CAPITAL, bundle.timetable(), serviceDate,
			bundle.payloadSha256());
		JourneyGoldenReport actual = JourneyGoldenSuite.run(capitalQueries, id -> fixture).report();

		String difference = JourneyGoldenReport.diff(golden, actual);
		return "번들 %s release %d (%d개 수도권 질의, 서비스일 %s)\n%s".formatted(bundle.bundleId(), bundle.releaseSequence(),
			capitalQueries.size(), serviceDate, difference.isEmpty() ? "golden과 차이 없음\n" : difference);
	}

	private static JourneyGoldenReport capitalOnly(JourneyGoldenReport golden, List<JourneyGoldenCorpus.Query> capitalQueries) {
		Set<String> ids = capitalQueries.stream().map(JourneyGoldenCorpus.Query::id).collect(Collectors.toSet());
		return new JourneyGoldenReport(
			golden.fixtures().stream().filter(header -> JourneyGoldenFixtures.CAPITAL.equals(header.id())).toList(),
			golden.rows().stream().filter(row -> ids.contains(row.queryId())).toList());
	}

	private static LocalDate serviceDate(JourneyGoldenReport golden) {
		FixtureHeader capital = golden.fixtures().getFirst();
		return LocalDate.parse(capital.serviceDate());
	}
}

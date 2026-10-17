package com.easysubway.route.application.service;

import com.easysubway.route.application.service.JourneyGoldenReport.FixtureHeader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 코퍼스의 모든 질의를 프리셋별로 돌려 golden 보고서를 만든다. 입력(fixture)을 고르는 방법만 바꿔 같은 질의로 다른
 * 번들을 돌릴 수 있다(후보 번들 diff).
 */
final class JourneyGoldenSuite {

	private JourneyGoldenSuite() {
	}

	/** 질의별 결과(프리셋 순서)와 입력 머리를 담은 실행 결과. */
	record Run(JourneyGoldenReport report, Map<String, List<JourneyGoldenRow>> rowsByQuery) {
	}

	static Run run(List<JourneyGoldenCorpus.Query> queries, Function<String, JourneyGoldenFixtures.Fixture> fixtures) {
		Map<String, JourneyGoldenRunner> runners = new HashMap<>();
		List<FixtureHeader> headers = new ArrayList<>();
		List<JourneyGoldenRow> rows = new ArrayList<>();
		Map<String, List<JourneyGoldenRow>> rowsByQuery = new HashMap<>();
		for (JourneyGoldenCorpus.Query query : queries) {
			JourneyGoldenRunner runner = runners.computeIfAbsent(query.fixture(), id -> {
				JourneyGoldenFixtures.Fixture fixture = fixtures.apply(id);
				headers.add(new FixtureHeader(id, fixture.serviceDate().toString(), fixture.pin()));
				return new JourneyGoldenRunner(fixture);
			});
			List<JourneyGoldenRow> queryRows = runner.run(query);
			rows.addAll(queryRows);
			rowsByQuery.put(query.id(), queryRows);
		}
		return new Run(new JourneyGoldenReport(headers, rows), rowsByQuery);
	}
}

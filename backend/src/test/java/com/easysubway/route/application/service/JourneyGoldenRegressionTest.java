package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.easysubway.route.application.service.JourneyGoldenCorpus.Tag;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * 대표 경로 질의 golden 회귀. 고정 fixture 위에서 대표 질의를 보행 프로필 4종으로 돌린 응답 요약이 golden과 같아야
 * 한다. 라우팅 로직이나 입력이 바뀌어 결과가 달라지면 질의별로 무엇이 어떻게 달라졌는지 보이며 실패한다.
 *
 * <p>의도한 변경이면 {@code ./gradlew test --tests '*JourneyGoldenRegressionTest' -PupdateGolden}으로 golden을 다시
 * 만든다. 이때 바뀐 내용을 같은 차이 보고로 출력하므로 PR에 그대로 붙인다.</p>
 */
@DisplayName("Journey V3 대표 질의 golden 회귀")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JourneyGoldenRegressionTest {

	static final Path GOLDEN = Path.of("src/test/resources", JourneyGoldenReport.RESOURCE);
	private static final Path DIFF_REPORT = Path.of("build/reports/journey-golden/diff.txt");
	private static final int FAILURE_MESSAGE_LINES = 120;
	static final String UPDATE_PROPERTY = "easysubway.updateGolden";
	/** 첫 슬라이스의 최소 규모. 목표는 수백 쌍이다. */
	private static final int MINIMUM_PAIRS = 100;
	/** 태그마다 최소 이 수의 질의가 있어야 그 유형이 대표로 살아 있다. */
	private static final int MINIMUM_QUERIES_PER_TAG = 3;

	private JourneyGoldenCorpus corpus;
	private JourneyGoldenSuite.Run actual;

	@BeforeAll
	void runCorpus() {
		corpus = JourneyGoldenCorpus.load();
		actual = JourneyGoldenSuite.run(corpus.queries(), JourneyGoldenFixtures::load);
	}

	@Test
	@DisplayName("대표 질의의 응답 요약이 golden과 같다")
	void summaryMatchesGolden() throws IOException {
		if (Boolean.getBoolean(UPDATE_PROPERTY)) {
			updateGolden();
			return;
		}
		String difference = JourneyGoldenReport.diff(readGolden(), actual.report());
		if (!difference.isEmpty()) {
			Files.createDirectories(DIFF_REPORT.getParent());
			Files.writeString(DIFF_REPORT, difference, StandardCharsets.UTF_8);
			fail("대표 질의 결과가 golden과 다르다. 의도한 변경이면 -PupdateGolden으로 golden을 다시 만든다.\n"
				+ head(difference) + "전체 차이: " + DIFF_REPORT);
		}
	}

	@Test
	@DisplayName("golden 파일은 보고서가 쓰는 형식 그대로다")
	void goldenFileIsCanonical() throws IOException {
		assertThat(JourneyGoldenReport.parse(Files.readString(GOLDEN, StandardCharsets.UTF_8)).render())
			.isEqualTo(Files.readString(GOLDEN, StandardCharsets.UTF_8));
	}

	@Test
	@DisplayName("코퍼스가 첫 슬라이스 규모와 유형을 갖춘다")
	void corpusIsLargeAndCoversEveryRepresentativeType() {
		assertThat(corpus.pairCount()).as("출발·도착 쌍").isGreaterThanOrEqualTo(MINIMUM_PAIRS);
		for (Tag tag : Tag.values()) {
			long queries = corpus.queries().stream().filter(query -> query.tags().contains(tag)).count();
			assertThat(queries).as("태그 %s의 질의 수", tag).isGreaterThanOrEqualTo(MINIMUM_QUERIES_PER_TAG);
		}
	}

	@Test
	@DisplayName("모든 질의의 출발·도착 역이 fixture에 있다")
	void everyQueryStationExistsInItsFixture() {
		List<String> missing = new ArrayList<>();
		for (JourneyGoldenCorpus.Query query : corpus.queries()) {
			Set<String> stations = JourneyGoldenFixtures.load(query.fixture()).stationIds();
			if (!stations.contains(query.origin()) || !stations.contains(query.destination())) missing.add(query.id());
		}
		assertThat(missing).as("fixture에 없는 역을 쓰는 질의").isEmpty();
	}

	@Test
	@DisplayName("질의에 붙은 대표성 태그가 실제 결과로 사실이다")
	void tagsAreTrueForTheActualResults() {
		List<String> violations = new ArrayList<>();
		for (JourneyGoldenCorpus.Query query : corpus.queries()) {
			List<Tag> violated = JourneyGoldenTags.violated(query, actual.rowsByQuery().get(query.id()));
			if (!violated.isEmpty()) violations.add(query.id() + " " + violated);
		}
		assertThat(violations).as("결과로 확인되지 않는 태그").isEmpty();
	}

	@Test
	@DisplayName("프리셋 4종이 질의마다 한 행씩 있다")
	void everyQueryHasOneRowPerPreset() {
		for (JourneyGoldenCorpus.Query query : corpus.queries()) {
			assertThat(actual.rowsByQuery().get(query.id()))
				.extracting(JourneyGoldenRow::preset)
				.containsExactly(JourneyGoldenPreset.values());
		}
		assertThat(actual.report().rows()).hasSize(corpus.queries().size() * JourneyGoldenPreset.values().length);
	}

	/** 실패 메시지에 싣는 차이의 앞부분. 전체는 파일로 남긴다. */
	private static String head(String difference) {
		List<String> lines = difference.lines().toList();
		if (lines.size() <= FAILURE_MESSAGE_LINES) return difference;
		return String.join("\n", lines.subList(0, FAILURE_MESSAGE_LINES)) + "\n... (" + (lines.size() - FAILURE_MESSAGE_LINES)
			+ "줄 생략)\n";
	}

	private static JourneyGoldenReport readGolden() throws IOException {
		return JourneyGoldenReport.parse(Files.readString(GOLDEN, StandardCharsets.UTF_8));
	}

	private void updateGolden() throws IOException {
		JourneyGoldenReport previous = Files.exists(GOLDEN) ? readGolden() : new JourneyGoldenReport(List.of(), List.of());
		Files.writeString(GOLDEN, actual.report().render(), StandardCharsets.UTF_8);
		String difference = JourneyGoldenReport.diff(previous, actual.report());
		System.out.println("golden을 다시 만들었다: " + GOLDEN + "\n"
			+ (difference.isEmpty() ? "이전 golden과 같다." : difference));
	}
}

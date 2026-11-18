package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.journey.application.JourneyCandidate;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 역 밖 환승 재승차 운임은 공식 운임표의 승차 구간 합으로만 여정 fare에 반영한다(#444).
 * 원천 없는 재승차 상수(1,400원)와 환승 구간 금액 필드(additionalFareWon)가 다시 들어오지 않도록 막는다.
 */
@DisplayName("Out-of-station re-boarding fare guard")
class OutOfStationReboardingFareGuardTest {

	private static final Path MAIN_SOURCES = Path.of("src", "main", "java");
	private static final Path OPENAPI = Path.of("..", "contracts", "api", "journey-v3.openapi.yaml");
	// 이슈 번호 참조(#1400)는 제외하고 숫자 리터럴 1400 / 1_400만 찾는다.
	private static final Pattern FARE_CONSTANT = Pattern.compile("(?<![#\\w])1_?400(?!\\w)");

	@Test
	@DisplayName("Production sources contain no 1,400 won re-boarding constant and no additionalFareWon")
	void productionSourcesContainNoReboardingConstant() {
		List<String> constantHits = javaSources().flatMap(path -> lines(path).stream()
				.filter(line -> FARE_CONSTANT.matcher(line).find())
				.map(line -> path + ": " + line.strip()))
			.toList();
		List<String> fieldHits = javaSources().filter(path -> lines(path).stream()
				.anyMatch(line -> line.contains("additionalFareWon")))
			.map(Path::toString)
			.toList();

		assertThat(constantHits).isEmpty();
		assertThat(fieldHits).isEmpty();
	}

	@Test
	@DisplayName("Journey V3 OpenAPI no longer publishes a per-transfer additional fare amount")
	void openApiHasNoPerTransferAdditionalFare() throws IOException {
		assertThat(Files.readString(OPENAPI)).doesNotContain("additionalFareWon");
	}

	@Test
	@DisplayName("Transfer projection, candidate and wire records keep the penalty flag and limit but no amount")
	void transferRecordsKeepPenaltyFlagWithoutAmount() throws ClassNotFoundException {
		List<String> expected = List.of("transferType", "farePenaltyApplies", "transferLimitMinutes");

		assertThat(componentNames(RouteTimetableRaptorPlanner.JourneyAccessProjection.class))
			.containsAll(expected).doesNotContain("additionalFareWon");
		assertThat(componentNames(JourneyCandidate.Transfer.class))
			.containsAll(expected).doesNotContain("additionalFareWon");
		assertThat(componentNames(Class.forName(
			"com.easysubway.journey.adapter.in.web.JourneySearchResponseMapper$TransferLegResponse")))
			.containsAll(expected).doesNotContain("additionalFareWon");
	}

	private static List<String> componentNames(Class<?> recordType) {
		return Arrays.stream(recordType.getRecordComponents()).map(component -> component.getName()).toList();
	}

	private static Stream<Path> javaSources() {
		try (Stream<Path> walk = Files.walk(MAIN_SOURCES)) {
			List<Path> sources = walk.filter(path -> path.toString().endsWith(".java")).sorted().toList();
			assertThat(sources).isNotEmpty();
			return sources.stream();
		} catch (IOException exception) {
			throw new UncheckedIOException(exception);
		}
	}

	private static List<String> lines(Path path) {
		try {
			return Files.readAllLines(path);
		} catch (IOException exception) {
			throw new UncheckedIOException(exception);
		}
	}
}

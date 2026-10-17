package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.route.application.service.JourneyGoldenReport.FixtureHeader;
import com.easysubway.route.application.service.JourneyGoldenRow.Alternative;
import com.easysubway.route.application.service.JourneyGoldenRow.Status;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

@DisplayName("golden 보고서의 읽기·쓰기와 차이 보고")
class JourneyGoldenReportTest {

	private static final FixtureHeader FIXTURE = new FixtureHeader("capital", "2026-10-06", "a".repeat(64));

	@Test
	@DisplayName("쓴 보고서를 그대로 다시 읽는다")
	void rendersAndParsesTheSameReport() {
		var report = new JourneyGoldenReport(List.of(FIXTURE), List.of(
			found("q1", JourneyGoldenPreset.STANDARD, alternative(600, "line-a>line-b", "UNDETERMINED"),
				alternative(660, "line-a", "VERIFIED")),
			failure("q1", JourneyGoldenPreset.NO_STAIRS, Status.NO_ROUTE, "ROUTE_NOT_FOUND"),
			failure("q1", JourneyGoldenPreset.STEP_FREE, Status.ERROR, "FACILITY_STATUS_UNAVAILABLE")));

		assertThat(JourneyGoldenReport.parse(report.render())).isEqualTo(report);
		assertThat(JourneyGoldenReport.parse(report.render()).render()).isEqualTo(report.render());
	}

	@Test
	@DisplayName("같은 보고서끼리는 차이가 없다")
	void identicalReportsHaveNoDifference() {
		var report = new JourneyGoldenReport(List.of(FIXTURE), List.of(
			found("q1", JourneyGoldenPreset.STANDARD, alternative(600, "line-a", "VERIFIED"))));

		assertThat(JourneyGoldenReport.diff(report, report)).isEmpty();
	}

	@Test
	@DisplayName("소요시간 변화는 증감 초와 함께, 상태 변화와 노선 변화는 앞뒤 값과 함께 보인다")
	void reportsFieldLevelChanges() {
		var golden = new JourneyGoldenReport(List.of(FIXTURE), List.of(
			found("q1", JourneyGoldenPreset.STANDARD, alternative(600, "line-a", "VERIFIED")),
			found("q1", JourneyGoldenPreset.NO_STAIRS, alternative(700, "line-a>line-b", "VERIFIED"))));
		var actual = new JourneyGoldenReport(List.of(FIXTURE), List.of(
			found("q1", JourneyGoldenPreset.STANDARD, alternative(690, "line-a", "VERIFIED")),
			failure("q1", JourneyGoldenPreset.NO_STAIRS, Status.NO_ROUTE, "ROUTE_NOT_FOUND")));

		String difference = JourneyGoldenReport.diff(golden, actual);

		assertThat(difference).contains("변경 2행, 추가 0행, 삭제 0행")
			.contains("[변경] q1 STANDARD").contains("소요시간: 600s -> 690s (+90s)")
			.contains("[변경] q1 NO_STAIRS").contains("상태: FOUND -> NO_ROUTE").contains("대안 #1 삭제: ");
	}

	@Test
	@DisplayName("추가·삭제된 행과 입력 변경을 구분해 보인다")
	void reportsAddedRemovedRowsAndFixtureChanges() {
		var golden = new JourneyGoldenReport(List.of(FIXTURE), List.of(
			failure("old", JourneyGoldenPreset.STANDARD, Status.NO_ROUTE, "ROUTE_NOT_FOUND")));
		var actual = new JourneyGoldenReport(List.of(new FixtureHeader("capital", "2026-10-06", "b".repeat(64))), List.of(
			failure("new", JourneyGoldenPreset.STANDARD, Status.ERROR, "FACILITY_STATUS_UNAVAILABLE")));

		assertThat(JourneyGoldenReport.diff(golden, actual))
			.contains("변경 0행, 추가 1행, 삭제 1행")
			.contains("[입력 변경] capital: serviceDate=2026-10-06 pin=" + "a".repeat(64) + " -> serviceDate=2026-10-06 pin=" + "b".repeat(64))
			.contains("[추가] new STANDARD ERROR FACILITY_STATUS_UNAVAILABLE")
			.contains("[삭제] old STANDARD NO_ROUTE ROUTE_NOT_FOUND");
	}

	@Test
	@DisplayName("숫자를 ASCII로 쓰지 않는 로캘에서도 행과 diff의 숫자가 ASCII다")
	@ResourceLock(Resources.LOCALE)
	void rendersAsciiDigitsUnderANonAsciiLocale() {
		Locale locale = Locale.getDefault();
		try {
			Locale.setDefault(Locale.forLanguageTag("ar-SA-u-nu-arab"));
			assertThat(String.format("%d", 8)).as("이 로캘은 숫자를 ASCII로 쓰지 않는다").isNotEqualTo("8");
			var before = new JourneyGoldenReport(List.of(FIXTURE), List.of(
				found("q1", JourneyGoldenPreset.STANDARD, alternative(600, "line-a", "VERIFIED"))));
			var after = new JourneyGoldenReport(List.of(FIXTURE), List.of(
				found("q1", JourneyGoldenPreset.STANDARD, alternative(690, "line-a", "VERIFIED"))));

			assertThat(after.render()).contains("690s transfers=0 walk=42m");
			assertThat(JourneyGoldenReport.parse(after.render())).isEqualTo(after);
			assertThat(JourneyGoldenReport.diff(before, after)).contains("변경 1행, 추가 0행, 삭제 0행 (실제 1행, golden 1행)")
				.contains("소요시간: 600s -> 690s (+90s)");
		} finally {
			Locale.setDefault(locale);
		}
	}

	@Test
	@DisplayName("깨진 행과 중복 행은 읽지 않는다")
	void rejectsMalformedAndDuplicateRows() {
		assertThatThrownBy(() -> JourneyGoldenReport.parse("q1 STANDARD\n"))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("malformed");
		assertThatThrownBy(() -> JourneyGoldenReport.parse("q1 STANDARD FOUND\n  #1 broken\n"))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("malformed");
		var duplicate = new JourneyGoldenReport(List.of(), List.of(
			failure("q1", JourneyGoldenPreset.STANDARD, Status.NO_ROUTE, "ROUTE_NOT_FOUND"),
			failure("q1", JourneyGoldenPreset.STANDARD, Status.NO_ROUTE, "ROUTE_NOT_FOUND")));
		assertThatThrownBy(() -> JourneyGoldenReport.diff(duplicate, duplicate))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicate");
	}

	@Test
	@DisplayName("FOUND 행은 대안이 있어야 하고 그 밖의 상태는 대안이 없어야 한다")
	void foundRowsNeedAlternativesOthersMustNotHaveAny() {
		assertThatThrownBy(() -> failure("q1", JourneyGoldenPreset.STANDARD, Status.FOUND, ""))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new JourneyGoldenRow("q1", JourneyGoldenPreset.STANDARD, Status.NO_ROUTE, "ROUTE_NOT_FOUND",
			List.of(alternative(600, "line-a", "VERIFIED")))).isInstanceOf(IllegalArgumentException.class);
	}

	private static JourneyGoldenRow found(String queryId, JourneyGoldenPreset preset, Alternative... alternatives) {
		return new JourneyGoldenRow(queryId, preset, Status.FOUND, "stairFree=INCLUDED facility=APPLIED", List.of(alternatives));
	}

	private static JourneyGoldenRow failure(String queryId, JourneyGoldenPreset preset, Status status, String detail) {
		return new JourneyGoldenRow(queryId, preset, status, detail, List.of());
	}

	private static Alternative alternative(long durationSeconds, String lines, String access) {
		return new Alternative("08:00:00", "08:10:00", durationSeconds, lines.split(">").length - 1,
			42, List.of(lines.split(">")), access);
	}
}

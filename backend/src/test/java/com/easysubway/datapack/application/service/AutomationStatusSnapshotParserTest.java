package com.easysubway.datapack.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.datapack.domain.AutomationStatusSnapshot;
import com.easysubway.datapack.domain.InvalidAutomationStatusException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("자동화 상태 snapshot 검증")
class AutomationStatusSnapshotParserTest {

	private final AutomationStatusSnapshotParser parser = new AutomationStatusSnapshotParser();

	@Test
	@DisplayName("data 레포가 만드는 v1 snapshot을 그대로 읽는다")
	void parsesAValidSnapshot() {
		AutomationStatusSnapshot snapshot = parser.parse(AutomationStatusFixtures.validJson());

		assertThat(snapshot.generatedAt()).isEqualTo(Instant.parse("2026-10-10T03:00:00Z"));
		assertThat(snapshot.activeDatapack().releaseSequence()).isEqualTo(129);
		assertThat(snapshot.activeDatapack().expiresAt()).isEqualTo(Instant.parse("2026-10-11T15:00:00Z"));
		assertThat(snapshot.stages()).extracting(AutomationStatusSnapshot.Stage::id).containsExactly("refresh", "registration", "reverification", "candidate", "rc", "compat", "promotion", "publish", "deploy");
		assertThat(snapshot.stages().get(0).latest().conclusion()).isEqualTo("success");
		assertThat(snapshot.stages().get(7).latest()).isNull();
		assertThat(snapshot.stages().get(8).latest().conclusion()).isNull();
		assertThat(snapshot.stages().get(8).inFlight()).isTrue();
		assertThat(snapshot.failureIssues()).isEmpty();
		assertThat(snapshot.candidateInFlight()).isFalse();
	}

	@Test
	@DisplayName("실패 이슈와 막힌 PR 항목을 읽는다")
	void parsesIssuesAndStuckPulls() {
		AutomationStatusSnapshot snapshot = parser.parse(AutomationStatusFixtures.validJson(
			"2026-10-10T03:00:00Z", "2026-10-11T15:00:00.000Z",
			AutomationStatusFixtures.failureIssueJson(55), AutomationStatusFixtures.stuckPullJson(), "true"));

		assertThat(snapshot.failureIssues()).extracting(AutomationStatusSnapshot.FailureIssue::number).containsExactly(55L);
		assertThat(snapshot.stuck().pulls()).extracting(AutomationStatusSnapshot.StuckPull::reason).containsExactly("BEHIND");
		assertThat(snapshot.candidateInFlight()).isTrue();
	}

	@Test
	@DisplayName("모르는 필드·스키마 버전·종류는 거부한다")
	void rejectsUnknownShape() {
		String valid = AutomationStatusFixtures.validJson();
		for (String broken : List.of(
			valid.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"),
			valid.replace("automation-status-snapshot", "other-snapshot"),
			valid.replace("\"candidateInFlight\": false", "\"candidateInFlight\": false, \"extra\": 1"),
			valid.replace("\"inFlight\": false}", "\"inFlight\": false, \"extra\": 1}"),
			valid.replace("\"candidateInFlight\": false", "\"candidateInFlight\": \"no\""),
			valid.replace("\"releaseSequence\": 129", "\"releaseSequence\": 0"),
			valid.replace("\"releaseSequence\": 129", "\"releaseSequence\": \"129\""),
			valid.replace("2026-10-10T03:00:00Z", "not-a-time"),
			valid.replace("\"status\": \"completed\"", "\"status\": \"weird\""),
			valid.replace("\"conclusion\": \"success\"", "\"conclusion\": \"fine\""),
			"[]", "not json", "", "{}")) {
			assertThatThrownBy(() -> parser.parse(broken)).isInstanceOf(InvalidAutomationStatusException.class);
		}
	}

	@Test
	@DisplayName("github.com 밖의 링크와 http 링크는 거부한다")
	void rejectsForeignLinks() {
		String valid = AutomationStatusFixtures.validJson();
		for (String url : List.of("https://evil.example/actions/runs/101", "http://github.com/AquilaXk/x/actions/runs/101", "javascript:alert(1)", "https://github.com.evil.example/x")) {
			String broken = valid.replace("https://github.com/AquilaXk/easysubway-data/actions/runs/101", url);
			assertThatThrownBy(() -> parser.parse(broken)).isInstanceOf(InvalidAutomationStatusException.class);
		}
	}

	@Test
	@DisplayName("중복 키와 제어 문자, 과도한 길이·개수는 거부한다")
	void rejectsDuplicateKeysControlCharactersAndOversizedLists() {
		String valid = AutomationStatusFixtures.validJson();
		assertThatThrownBy(() -> parser.parse(valid.replace("\"candidateInFlight\": false", "\"candidateInFlight\": false, \"candidateInFlight\": true")))
			.isInstanceOf(InvalidAutomationStatusException.class);
		assertThatThrownBy(() -> parser.parse(valid.replace("원천 갱신", "원천\\u0007갱신")))
			.isInstanceOf(InvalidAutomationStatusException.class);
		assertThatThrownBy(() -> parser.parse(valid.replace("원천 갱신", "가".repeat(201))))
			.isInstanceOf(InvalidAutomationStatusException.class);
		String manyIssues = "[" + String.join(",", java.util.Collections.nCopies(51,
			"{\"number\": 1, \"title\": \"t\", \"url\": \"https://github.com/AquilaXk/easysubway-data/issues/1\", \"createdAt\": \"2026-10-10T00:30:00Z\"}")) + "]";
		assertThatThrownBy(() -> parser.parse(AutomationStatusFixtures.validJson(
			"2026-10-10T03:00:00Z", "2026-10-11T15:00:00.000Z", manyIssues, "[]", "false")))
			.isInstanceOf(InvalidAutomationStatusException.class);
	}

	@Test
	@DisplayName("양방향 제어·서식 문자와 줄·문단 구분 문자가 든 문구는 화면 라벨을 속일 수 있어 거부한다")
	void rejectsBidirectionalAndFormatCharacters() {
		String valid = AutomationStatusFixtures.validJson();
		for (String hidden : new String[] {"\\u202E", "\\u202A", "\\u2066", "\\u2069", "\\u200F", "\\u200B", "\\u2028", "\\u2029"}) {
			assertThatThrownBy(() -> parser.parse(valid.replace("원천 갱신", "원천" + hidden + "갱신")), hidden)
				.isInstanceOf(InvalidAutomationStatusException.class);
		}
		assertThat(parser.parse(valid.replace("원천 갱신", "원천 갱신 (a-b) 2026")).stages().get(0).label()).isEqualTo("원천 갱신 (a-b) 2026");
	}

	private static final String INCHEON = AutomationStatusFixtures.expiringSourceJson(
		"incheon-line1-train-timetable", "인천 1호선 열차 시간표", "2026-10-10T07:22:23.648Z", "capital-topology-refresh", "BLOCKED");
	private static final String BUSAN = AutomationStatusFixtures.expiringSourceJson(
		"busan-transportation-timetable", "부산 도시철도 시간표", "2026-10-11T06:09:43.513Z", null, "NONE");

	@Test
	@DisplayName("expiringSources가 없는 옛 snapshot도 그대로 받고 보고되지 않음으로 둔다(정상으로 채우지 않는다)")
	void snapshotWithoutExpiringSourcesIsStillAccepted() {
		AutomationStatusSnapshot snapshot = parser.parse(AutomationStatusFixtures.validJson());

		assertThat(snapshot.reportsExpiringSources()).isFalse();
		assertThat(snapshot.expiringSources()).isNull();
	}

	@Test
	@DisplayName("expiringSources가 있으면 항목을 그대로 읽고, 빈 배열은 보고되었지만 없음으로 읽는다")
	void parsesExpiringSources() {
		AutomationStatusSnapshot snapshot = parser.parse(AutomationStatusFixtures.validJsonWithExpiringSources("[" + INCHEON + "," + BUSAN + "]"));

		assertThat(snapshot.reportsExpiringSources()).isTrue();
		assertThat(snapshot.expiringSources()).hasSize(2);
		AutomationStatusSnapshot.ExpiringSource first = snapshot.expiringSources().get(0);
		assertThat(first.sourceId()).isEqualTo("incheon-line1-train-timetable");
		assertThat(first.name()).isEqualTo("인천 1호선 열차 시간표");
		assertThat(first.evidence()).isEqualTo("scheduleAdmissionEvidence");
		assertThat(first.freshUntil()).isEqualTo(Instant.parse("2026-10-10T07:22:23.648Z"));
		assertThat(first.refreshStage()).isEqualTo("capital-topology-refresh");
		assertThat(first.refreshState()).isEqualTo(AutomationStatusSnapshot.RefreshState.BLOCKED);
		assertThat(snapshot.expiringSources().get(1).refreshStage()).isNull();
		assertThat(snapshot.expiringSources().get(1).refreshState()).isEqualTo(AutomationStatusSnapshot.RefreshState.NONE);

		AutomationStatusSnapshot none = parser.parse(AutomationStatusFixtures.validJsonWithExpiringSources("[]"));
		assertThat(none.reportsExpiringSources()).isTrue();
		assertThat(none.expiringSources()).isEmpty();
	}

	@Test
	@DisplayName("expiringSources의 모르는 키·빠진 키·잘못된 값·짝이 맞지 않는 갱신 단계와 상태·중복은 거부한다")
	void rejectsMalformedExpiringSources() {
		for (String broken : List.of(
			INCHEON.replace("\"evidence\"", "\"extra\": 1, \"evidence\""),
			INCHEON.replace("\"name\": \"인천 1호선 열차 시간표\", ", ""),
			INCHEON.replace("\"BLOCKED\"", "\"STUCK\""),
			INCHEON.replace("\"BLOCKED\"", "\"NONE\""),
			BUSAN.replace("\"refreshState\": \"NONE\"", "\"refreshState\": \"OK\""),
			INCHEON.replace("incheon-line1-train-timetable", "Incheon Line1"),
			INCHEON.replace("incheon-line1-train-timetable", "x".repeat(101)),
			INCHEON.replace("scheduleAdmissionEvidence", "schedule-admission"),
			INCHEON.replace("2026-10-10T07:22:23.648Z", "2026-10-10"),
			INCHEON.replace("인천 1호선 열차 시간표", "인천\\u202e시간표"),
			INCHEON.replace("인천 1호선 열차 시간표", ""),
			INCHEON.replace("인천 1호선 열차 시간표", "가".repeat(201)),
			INCHEON.replace("\"capital-topology-refresh\"", "\"Capital Refresh\""))) {
			assertThatThrownBy(() -> parser.parse(AutomationStatusFixtures.validJsonWithExpiringSources("[" + broken + "]")))
				.as(broken).isInstanceOf(InvalidAutomationStatusException.class);
		}
		assertThatThrownBy(() -> parser.parse(AutomationStatusFixtures.validJsonWithExpiringSources("[" + INCHEON + "," + INCHEON + "]")))
			.isInstanceOf(InvalidAutomationStatusException.class).hasMessageContaining("중복");
		assertThatThrownBy(() -> parser.parse(AutomationStatusFixtures.validJsonWithExpiringSources("{}")))
			.isInstanceOf(InvalidAutomationStatusException.class);
		assertThatThrownBy(() -> parser.parse(AutomationStatusFixtures.validJsonWithExpiringSources("null")))
			.isInstanceOf(InvalidAutomationStatusException.class);
	}

	@Test
	@DisplayName("expiringSources는 50개까지만 받는다")
	void limitsExpiringSourcesCount() {
		java.util.function.IntFunction<String> sources = (count) -> "[" + java.util.stream.IntStream.range(0, count)
			.mapToObj((index) -> AutomationStatusFixtures.expiringSourceJson("source-" + index, "자료 " + index, "2026-10-12T00:00:00Z", null, "NONE"))
			.collect(java.util.stream.Collectors.joining(",")) + "]";

		assertThat(parser.parse(AutomationStatusFixtures.validJsonWithExpiringSources(sources.apply(50))).expiringSources()).hasSize(50);
		assertThatThrownBy(() -> parser.parse(AutomationStatusFixtures.validJsonWithExpiringSources(sources.apply(51))))
			.isInstanceOf(InvalidAutomationStatusException.class);
	}
}

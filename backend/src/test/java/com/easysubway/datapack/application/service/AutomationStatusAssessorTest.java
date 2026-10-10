package com.easysubway.datapack.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.datapack.domain.AutomationAssessment;
import com.easysubway.datapack.domain.AutomationAssessment.Level;
import com.easysubway.datapack.domain.AutomationStatusSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("자동화 상태 판정")
class AutomationStatusAssessorTest {

	private static final Instant NOW = Instant.parse("2026-10-10T03:10:00Z");
	private final AutomationStatusSnapshotParser parser = new AutomationStatusSnapshotParser();
	private final AutomationStatusAssessor assessor = new AutomationStatusAssessor();

	private AutomationAssessment assess(String json, Instant receivedAt) {
		AutomationStatusSnapshot snapshot = parser.parse(json);
		return assessor.assess(Optional.of(new StoredAutomationStatus(snapshot, receivedAt)), NOW, NOW.minus(Duration.ofDays(3)));
	}

	private AutomationAssessment assessStages(String stagesJson) {
		AutomationStatusSnapshot snapshot = parser.parse(AutomationStatusFixtures.validJsonWithStages(
			"2026-10-10T03:00:00Z", "2026-10-12T15:00:00.000Z", stagesJson, "[]", "[]", "false"));
		return assessor.assess(Optional.of(new StoredAutomationStatus(snapshot, Instant.parse("2026-10-10T03:05:00Z"))), NOW, NOW.minus(Duration.ofDays(3)));
	}

	private String json(String expiresAt, String issues, String pulls, String inFlight) {
		return AutomationStatusFixtures.validJson("2026-10-10T03:00:00Z", expiresAt, issues, pulls, inFlight);
	}

	@Test
	@DisplayName("아직 받은 snapshot이 없으면 정상으로 채우지 않고 수신 전으로 판정한다")
	void nothingReceivedIsUnknownNotOk() {
		AutomationAssessment assessment = assessor.assess(Optional.empty(), NOW, NOW.minus(Duration.ofMinutes(5)));

		assertThat(assessment.level()).isEqualTo(Level.UNKNOWN);
		assertThat(assessment.received()).isFalse();
		assertThat(assessment.findings()).extracting(AutomationAssessment.Finding::code).containsExactly("NOT_RECEIVED");
		assertThat(assessment.headline()).contains("수신 전");
		assertThat(assessment.remaining()).isNull();
	}

	@Test
	@DisplayName("모든 것이 정상이고 만료까지 36시간 이상 남았으면 정상이다")
	void healthyIsOk() {
		AutomationAssessment assessment = assess(json("2026-10-11T16:00:00.000Z", "[]", "[]", "false"), Instant.parse("2026-10-10T03:00:00Z"));

		assertThat(assessment.level()).isEqualTo(Level.OK);
		assertThat(assessment.findings()).isEmpty();
		assertThat(assessment.remaining()).isEqualTo(Duration.ofHours(36).plusMinutes(50));
		assertThat(assessment.headline()).contains("정상");
	}

	@Test
	@DisplayName("만료가 지났으면 가장 심한 이상이다")
	void expiredIsTheWorstFinding() {
		AutomationAssessment assessment = assess(json("2026-10-10T03:00:00.000Z", AutomationStatusFixtures.failureIssueJson(5), "[]", "false"), Instant.parse("2026-10-10T03:00:00Z"));

		assertThat(assessment.level()).isEqualTo(Level.FAILURE);
		assertThat(assessment.findings()).extracting(AutomationAssessment.Finding::code).startsWith("DATAPACK_EXPIRED", "FAILURE_ISSUES");
		assertThat(assessment.headline()).contains("만료");
	}

	@Test
	@DisplayName("만료 36시간 미만이고 진행 중인 후보가 없으면 만료 임박 경고, 후보가 진행 중이면 경고하지 않는다")
	void expiryWarningNeedsNoCandidateInFlight() {
		String soon = "2026-10-11T14:00:00.000Z"; // 34시간 50분 남음
		AutomationAssessment idle = assess(json(soon, "[]", "[]", "false"), Instant.parse("2026-10-10T03:00:00Z"));
		assertThat(idle.level()).isEqualTo(Level.WARNING);
		assertThat(idle.findings()).extracting(AutomationAssessment.Finding::code).containsExactly("EXPIRY_SOON");
		assertThat(idle.headline()).contains("만료가 가까워지고 있습니다");

		AutomationAssessment inFlight = assess(json(soon, "[]", "[]", "true"), Instant.parse("2026-10-10T03:00:00Z"));
		assertThat(inFlight.level()).isEqualTo(Level.OK);

		// 정확히 36시간이면 경고하지 않는다(미만일 때만).
		AutomationAssessment boundary = assess(json("2026-10-11T15:10:00.000Z", "[]", "[]", "false"), Instant.parse("2026-10-10T03:00:00Z"));
		assertThat(boundary.level()).isEqualTo(Level.OK);
	}

	@Test
	@DisplayName("마지막 수신이 45분보다 오래되면 게시가 멈춘 것으로 본다")
	void staleSnapshotMeansPublishingStopped() {
		AutomationAssessment fresh = assess(json("2026-10-12T15:00:00.000Z", "[]", "[]", "false"), NOW.minus(Duration.ofMinutes(45)));
		assertThat(fresh.level()).isEqualTo(Level.OK);

		AutomationAssessment stale = assess(json("2026-10-12T15:00:00.000Z", "[]", "[]", "false"), NOW.minus(Duration.ofMinutes(46)));
		assertThat(stale.level()).isEqualTo(Level.FAILURE);
		assertThat(stale.findings()).extracting(AutomationAssessment.Finding::code).containsExactly("STATUS_STALE");
		assertThat(stale.headline()).contains("게시가 멈췄습니다").contains("46분");
	}

	@Test
	@DisplayName("열린 실패 이슈와 막힌 자동화를 각각 드러낸다")
	void failureIssuesAndStuckAutomation() {
		AutomationAssessment assessment = assess(
			json("2026-10-12T15:00:00.000Z", AutomationStatusFixtures.failureIssueJson(5), AutomationStatusFixtures.stuckPullJson(), "false"),
			Instant.parse("2026-10-10T03:05:00Z"));

		assertThat(assessment.level()).isEqualTo(Level.FAILURE);
		assertThat(assessment.findings()).extracting(AutomationAssessment.Finding::code).containsExactly("FAILURE_ISSUES", "STUCK_AUTOMATION");
		assertThat(assessment.findings().get(0).message()).contains("1건");
		assertThat(assessment.findings().get(1).level()).isEqualTo(Level.WARNING);
	}

	@Test
	@DisplayName("수신 전 상태가 서버 기동 뒤 1시간을 넘기면 조용히 두지 않고 이상으로 판정한다")
	void notReceivedForTooLongIsFailure() {
		AutomationAssessment justAtLimit = assessor.assess(Optional.empty(), NOW, NOW.minus(Duration.ofHours(1)));
		assertThat(justAtLimit.level()).isEqualTo(Level.UNKNOWN);

		AutomationAssessment tooLong = assessor.assess(Optional.empty(), NOW, NOW.minus(Duration.ofHours(1)).minusSeconds(1));
		assertThat(tooLong.level()).isEqualTo(Level.FAILURE);
		assertThat(tooLong.received()).isFalse();
		assertThat(tooLong.findings()).extracting(AutomationAssessment.Finding::code).containsExactly("NOT_RECEIVED_LONG");
		assertThat(tooLong.headline()).contains("1시간").contains("받지 못했습니다");
	}

	@Test
	@DisplayName("단계의 최근 run이 실패했으면 정상이 아니다: 실패·시간 초과는 이상, 취소는 경고, 성공·건너뜀은 정상")
	void failedLatestRunOfAStageIsAnAnomaly() {
		AutomationAssessment failed = assessStages(AutomationStatusFixtures.stagesJson(Map.of("publish", "failure")));
		assertThat(failed.level()).isEqualTo(Level.FAILURE);
		assertThat(failed.findings()).extracting(AutomationAssessment.Finding::code).containsExactly("STAGE_FAILED");
		assertThat(failed.headline()).contains("발행").contains("실패");

		AutomationAssessment timedOut = assessStages(AutomationStatusFixtures.stagesJson(Map.of("deploy", "timed_out", "compat", "startup_failure")));
		assertThat(timedOut.level()).isEqualTo(Level.FAILURE);
		assertThat(timedOut.findings()).hasSize(2).allSatisfy((finding) -> assertThat(finding.level()).isEqualTo(Level.FAILURE));

		AutomationAssessment noConclusion = assessStages(AutomationStatusFixtures.stagesJson(Map.of("candidate", "completed-without-conclusion")));
		assertThat(noConclusion.level()).isEqualTo(Level.FAILURE);
		assertThat(noConclusion.findings().get(0).message()).contains("후보 갱신").contains("결과 없음");

		AutomationAssessment cancelled = assessStages(AutomationStatusFixtures.stagesJson(Map.of("promotion", "cancelled")));
		assertThat(cancelled.level()).isEqualTo(Level.WARNING);
		assertThat(cancelled.findings()).extracting(AutomationAssessment.Finding::code).containsExactly("STAGE_FAILED");

		AutomationAssessment skipped = assessStages(AutomationStatusFixtures.stagesJson(Map.of("registration", "skipped")));
		assertThat(skipped.level()).isEqualTo(Level.OK);
	}

	@Test
	@DisplayName("단계 목록에서 빠진 단계나 빈 목록은 정상으로 보지 않고 누락된 단계를 이름으로 드러낸다")
	void missingStagesAreAnAnomaly() {
		AutomationAssessment missing = assessStages(AutomationStatusFixtures.stagesJsonOnly("refresh", "registration", "reverification", "candidate", "rc", "compat", "promotion", "publish"));
		assertThat(missing.level()).isEqualTo(Level.FAILURE);
		assertThat(missing.findings()).extracting(AutomationAssessment.Finding::code).containsExactly("STAGES_MISSING");
		assertThat(missing.headline()).contains("배포");

		AutomationAssessment empty = assessStages("[]");
		assertThat(empty.level()).isEqualTo(Level.FAILURE);
		assertThat(empty.findings()).extracting(AutomationAssessment.Finding::code).containsExactly("STAGES_MISSING");
		assertThat(empty.findings().get(0).message()).contains("원천 갱신").contains("배포");
	}

	@Test
	@DisplayName("기록이 없는 단계는 이상은 아니지만 정상 문구가 모든 단계를 확인한 것처럼 읽히지 않는다")
	void stageWithoutRecordIsNotClaimedAsChecked() {
		AutomationAssessment healthy = assessStages(AutomationStatusFixtures.healthyStagesJson());
		assertThat(healthy.level()).isEqualTo(Level.OK);
		assertThat(healthy.headline()).contains("정상").contains("실행 기록이 없는 단계").contains("발행");
		assertThat(healthy.headline()).doesNotContain("모든 자동화 단계가 정상");

		AutomationAssessment allRecorded = assessStages(AutomationStatusFixtures.stagesJson(Map.of("publish", "success")));
		assertThat(allRecorded.level()).isEqualTo(Level.OK);
		assertThat(allRecorded.headline()).isEqualTo("정상: 모든 자동화 단계의 최근 실행이 정상입니다");
	}

	private static String source(String id, String name, Instant freshUntil, String stage, String state) {
		return AutomationStatusFixtures.expiringSourceJson(id, name, freshUntil.toString(), stage, state);
	}

	private AutomationAssessment assessSources(String... items) {
		return assess(AutomationStatusFixtures.validJsonWithExpiringSources("[" + String.join(",", items) + "]"), Instant.parse("2026-10-10T03:05:00Z"));
	}

	private List<String> codes(AutomationAssessment assessment) {
		return assessment.findings().stream().map(AutomationAssessment.Finding::code).toList();
	}

	@Test
	@DisplayName("원천 근거 만료 정보를 보내지 않는 snapshot은 지금처럼 판정하고 근거 판정은 하지 않는다")
	void withoutExpiringSourcesNothingIsAssessedAboutSources() {
		AutomationAssessment assessment = assess(json("2026-10-14T15:00:00.000Z", "[]", "[]", "false"), Instant.parse("2026-10-10T03:05:00Z"));

		assertThat(assessment.level()).isEqualTo(Level.OK);
		assertThat(assessment.findings()).isEmpty();
	}

	@Test
	@DisplayName("12시간보다 많이 남은 근거는 판정에 영향이 없다")
	void sourcesFarFromExpiryAreIgnored() {
		AutomationAssessment assessment = assessSources(
			source("busan-a", "부산 시간표", NOW.plus(Duration.ofHours(12)).plusSeconds(60), "capital-topology-refresh", "BLOCKED"),
			source("daegu-a", "대구 시간표", NOW.plus(Duration.ofDays(5)), null, "NONE"));

		assertThat(assessment.level()).isEqualTo(Level.OK);
		assertThat(assessment.findings()).isEmpty();
	}

	@Test
	@DisplayName("6시간 안에 만료되는데 갱신 작업이 실패했거나 막혀 있거나 자동 갱신 경로가 없으면 이상이다(2026-10-10 인천 시간표 사례)")
	void soonExpiringSourceWithBrokenRefreshIsFailure() {
		AutomationAssessment blocked = assessSources(source("incheon-line1-train-timetable", "인천 1호선 열차 시간표",
			Instant.parse("2026-10-10T07:22:23.648Z"), "capital-topology-refresh", "BLOCKED"));

		assertThat(blocked.level()).isEqualTo(Level.FAILURE);
		assertThat(codes(blocked)).containsExactly("SOURCE_EXPIRY_BLOCKED");
		assertThat(blocked.headline()).isEqualTo("원천 자료 1건이 6시간 안에 만료되는데 갱신 작업이 실패했거나 막혀 있거나 자동 갱신 경로가 없습니다: 인천 1호선 열차 시간표(4시간 12분 남음)");

		for (String state : List.of("FAILED", "BLOCKED")) {
			AutomationAssessment broken = assessSources(source("incheon-line1-train-timetable", "인천 1호선 열차 시간표", NOW.plus(Duration.ofHours(1)), "capital-topology-refresh", state));
			assertThat(broken.level()).as(state).isEqualTo(Level.FAILURE);
			assertThat(codes(broken)).containsExactly("SOURCE_EXPIRY_BLOCKED");
		}
		// NONE은 사람 없이는 복구되지 않으므로 6시간 안이면 이상이고 그렇다고 적는다.
		AutomationAssessment none = assessSources(source("b", "부산 시간표", NOW.plus(Duration.ofHours(2)), null, "NONE"));
		assertThat(none.level()).isEqualTo(Level.FAILURE);
		assertThat(none.headline()).isEqualTo("원천 자료 1건이 6시간 안에 만료되는데 갱신 작업이 실패했거나 막혀 있거나 자동 갱신 경로가 없습니다: 부산 시간표(2시간 0분 남음, 자동 갱신 없음)");
	}

	@Test
	@DisplayName("경계: 정확히 6시간 남으면 이상, 6시간 1분이면 주의, 정확히 12시간이면 주의, 12시간 1분이면 영향 없음 (NONE·FAILED·BLOCKED 모두)")
	void thresholdsAreInclusive() {
		for (String state : List.of("NONE", "FAILED", "BLOCKED")) {
			String stage = state.equals("NONE") ? null : "s";
			assertThat(assessSources(source("a", "자료", NOW.plus(Duration.ofHours(6)), stage, state)).level()).as(state).isEqualTo(Level.FAILURE);
			AutomationAssessment justOver = assessSources(source("a", "자료", NOW.plus(Duration.ofHours(6)).plusSeconds(60), stage, state));
			assertThat(justOver.level()).as(state).isEqualTo(Level.WARNING);
			assertThat(codes(justOver)).containsExactly("SOURCE_EXPIRY_SOON");
			assertThat(assessSources(source("a", "자료", NOW.plus(Duration.ofHours(12)), stage, state)).level()).as(state).isEqualTo(Level.WARNING);
			assertThat(assessSources(source("a", "자료", NOW.plus(Duration.ofHours(12)).plusSeconds(60), stage, state)).level()).as(state).isEqualTo(Level.OK);
		}
		assertThat(assessSources(source("a", "자료", NOW.plus(Duration.ofHours(12)), "s", "OK")).level()).isEqualTo(Level.WARNING);
		assertThat(assessSources(source("a", "자료", NOW.plus(Duration.ofHours(12)).plusSeconds(60), "s", "OK")).level()).isEqualTo(Level.OK);
	}

	@Test
	@DisplayName("갱신 작업이 정상(OK)이면 6시간 안이어도 주의일 뿐이다")
	void healthyRefreshIsOnlyAWarning() {
		AutomationAssessment healthy = assessSources(source("a", "대구 1호선 시간표", NOW.plus(Duration.ofHours(1)), "source-reverification", "OK"));
		assertThat(healthy.level()).isEqualTo(Level.WARNING);
		assertThat(codes(healthy)).containsExactly("SOURCE_EXPIRY_SOON");
		assertThat(healthy.headline()).isEqualTo("원천 자료 1건이 12시간 안에 만료됩니다: 대구 1호선 시간표(1시간 0분 남음)");
	}

	@Test
	@DisplayName("이미 만료된 근거는 갱신 상태와 관계없이 이상이다: OK·NONE·FAILED·BLOCKED 모두, 얼마나 오래 지났든")
	void expiredSourcesAreAlwaysFailure() {
		for (String state : List.of("OK", "NONE", "FAILED", "BLOCKED")) {
			String stage = state.equals("NONE") ? null : "capital-topology-refresh";
			AutomationAssessment expired = assessSources(source("a", "인천 시간표", NOW.minus(Duration.ofMinutes(45)), stage, state));
			assertThat(expired.level()).as(state).isEqualTo(Level.FAILURE);
			assertThat(codes(expired)).as(state).containsExactly("SOURCE_EXPIRED");
			assertThat(expired.headline()).as(state).startsWith("원천 자료 1건이 이미 만료되었습니다: 인천 시간표(만료됨, 45분 지남");
		}
		assertThat(assessSources(source("a", "부산 시간표", NOW, null, "NONE")).level()).as("정확히 만료 시각").isEqualTo(Level.FAILURE);
		AutomationAssessment longAgo = assessSources(source("a", "부산 시간표", Instant.parse("2026-10-03T06:09:43Z"), null, "NONE"));
		assertThat(longAgo.level()).isEqualTo(Level.FAILURE);
		assertThat(longAgo.headline()).isEqualTo("원천 자료 1건이 이미 만료되었습니다: 부산 시간표(만료됨, 6일 21시간 0분 지남, 자동 갱신 없음)");
	}

	@Test
	@DisplayName("만료, 6시간 안의 이상, 12시간 안의 주의를 각각 하나씩 요약하고 이상이 먼저 온다. 이름은 만료가 이른 순서로 세 개까지 적고 나머지는 건수로 적는다")
	void expiredFailureAndWarningAreSummarizedSeparately() {
		AutomationAssessment assessment = assessSources(
			source("w1", "주의 자료 1", NOW.plus(Duration.ofHours(10)), "s", "OK"),
			source("e1", "만료 자료 1", NOW.minus(Duration.ofHours(2)), "s", "OK"),
			source("f2", "이상 자료 2", NOW.plus(Duration.ofHours(2)), "s", "FAILED"),
			source("f1", "이상 자료 1", NOW.plus(Duration.ofHours(1)), "s", "BLOCKED"),
			source("f4", "이상 자료 4", NOW.plus(Duration.ofHours(4)), null, "NONE"),
			source("f3", "이상 자료 3", NOW.plus(Duration.ofHours(3)), "s", "BLOCKED"),
			source("w2", "주의 자료 2", NOW.plus(Duration.ofHours(8)), "s", "OK"));

		assertThat(assessment.level()).isEqualTo(Level.FAILURE);
		assertThat(codes(assessment)).containsExactly("SOURCE_EXPIRED", "SOURCE_EXPIRY_BLOCKED", "SOURCE_EXPIRY_SOON");
		assertThat(assessment.findings().get(0).message()).isEqualTo("원천 자료 1건이 이미 만료되었습니다: 만료 자료 1(만료됨, 2시간 0분 지남)");
		assertThat(assessment.findings().get(1).message())
			.isEqualTo("원천 자료 4건이 6시간 안에 만료되는데 갱신 작업이 실패했거나 막혀 있거나 자동 갱신 경로가 없습니다: 이상 자료 1(1시간 0분 남음), 이상 자료 2(2시간 0분 남음), 이상 자료 3(3시간 0분 남음) 외 1건");
		assertThat(assessment.findings().get(2).level()).isEqualTo(Level.WARNING);
		assertThat(assessment.findings().get(2).message()).isEqualTo("원천 자료 2건이 12시간 안에 만료됩니다: 주의 자료 2(8시간 0분 남음), 주의 자료 1(10시간 0분 남음)");
	}

	@Test
	@DisplayName("근거 판정은 렌더 시각으로 계산한다: 같은 snapshot이 12시간 밖에서는 정상, 안에서는 주의, 6시간 안에서는 이상이다")
	void assessmentFollowsTheRenderClock() {
		AutomationStatusSnapshot snapshot = parser.parse(AutomationStatusFixtures.validJsonWithExpiringSources("[" + source(
			"a", "인천 시간표", Instant.parse("2026-10-10T15:00:00Z"), "capital-topology-refresh", "BLOCKED") + "]"));

		for (String[] row : new String[][] {{"2026-10-10T02:55:00Z", "OK"}, {"2026-10-10T03:00:00Z", "WARNING"}, {"2026-10-10T08:59:00Z", "WARNING"}, {"2026-10-10T09:00:00Z", "FAILURE"}}) {
			Instant renderedAt = Instant.parse(row[0]);
			StoredAutomationStatus stored = new StoredAutomationStatus(snapshot, renderedAt.minus(Duration.ofMinutes(1)));
			AutomationAssessment assessment = assessor.assess(Optional.of(stored), renderedAt, renderedAt.minus(Duration.ofDays(3)));
			assertThat(assessment.level()).as(row[0]).isEqualTo(Level.valueOf(row[1]));
			if (!row[1].equals("OK")) {
				assertThat(codes(assessment)).as(row[0]).hasSize(1).first().asString().startsWith("SOURCE_EXPIRY");
			}
		}
	}
}

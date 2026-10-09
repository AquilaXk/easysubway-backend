package com.easysubway.datapack.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.datapack.domain.AutomationAssessment;
import com.easysubway.datapack.domain.AutomationAssessment.Level;
import com.easysubway.datapack.domain.AutomationStatusSnapshot;
import java.time.Duration;
import java.time.Instant;
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
}

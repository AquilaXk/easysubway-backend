package com.easysubway.datapack.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.datapack.domain.AutomationAssessment;
import com.easysubway.datapack.domain.AutomationAssessment.Level;
import com.easysubway.datapack.domain.AutomationStatusSnapshot;
import java.time.Duration;
import java.time.Instant;
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
		return assessor.assess(Optional.of(new StoredAutomationStatus(snapshot, receivedAt)), NOW);
	}

	private String json(String expiresAt, String issues, String pulls, String inFlight) {
		return AutomationStatusFixtures.validJson("2026-10-10T03:00:00Z", expiresAt, issues, pulls, inFlight);
	}

	@Test
	@DisplayName("아직 받은 snapshot이 없으면 정상으로 채우지 않고 수신 전으로 판정한다")
	void nothingReceivedIsUnknownNotOk() {
		AutomationAssessment assessment = assessor.assess(Optional.empty(), NOW);

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
}

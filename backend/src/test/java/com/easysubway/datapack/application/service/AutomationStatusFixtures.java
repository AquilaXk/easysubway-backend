package com.easysubway.datapack.application.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** data 레포 build-automation-status.mjs가 만드는 snapshot(v1)의 시험용 원문. */
public final class AutomationStatusFixtures {

	public static final Instant GENERATED_AT = Instant.parse("2026-10-10T03:00:00Z");

	private AutomationStatusFixtures() {
	}

	public static String validJson() {
		return validJson(GENERATED_AT.toString(), "2026-10-11T15:00:00.000Z", "[]", "[]", "false");
	}

	public static String validJson(String generatedAt, String expiresAt, String failureIssues, String stuckPulls, String candidateInFlight) {
		return validJsonWithStages(generatedAt, expiresAt, healthyStagesJson(), failureIssues, stuckPulls, candidateInFlight);
	}

	public static String validJsonWithStages(String generatedAt, String expiresAt, String stages, String failureIssues, String stuckPulls, String candidateInFlight) {
		return """
			{
			  "schemaVersion": 1,
			  "artifactKind": "automation-status-snapshot",
			  "generatedAt": "%s",
			  "activeDatapack": {"releaseSequence": 129, "publishedAt": "2026-10-09T10:29:36.200Z", "expiresAt": "%s"},
			  "stages": %s,
			  "failureIssues": %s,
			  "stuck": {"pulls": %s, "claims": [], "behindCap": []},
			  "candidateInFlight": %s
			}
			""".formatted(generatedAt, expiresAt, stages, failureIssues, stuckPulls, candidateInFlight);
	}

	private static final String[][] STAGES = {
		{"refresh", "원천 갱신"}, {"registration", "원천 등록"}, {"reverification", "원천 재확인"}, {"candidate", "후보 갱신"},
		{"rc", "후보 검증(RC)"}, {"compat", "앱 호환성 검증"}, {"promotion", "승격"}, {"publish", "발행"}, {"deploy", "배포"},
	};

	/** 아홉 단계 모두의 최근 run이 성공했다. 발행은 기록 없음, 배포는 진행 중이다(실제 정상 상태의 한 모습). */
	public static String healthyStagesJson() {
		return stagesJson(Map.of());
	}

	/** conclusionByStage: 단계 id -> 최근 run의 conclusion. "none"이면 기록 없음, "running"이면 진행 중이다. 빠진 단계는 기본 상태다. */
	public static String stagesJson(Map<String, String> conclusionByStage) {
		List<String> items = new ArrayList<>();
		for (String[] stage : STAGES) {
			String fallback = stage[0].equals("publish") ? "none" : stage[0].equals("deploy") ? "running" : "success";
			items.add(stageJson(stage[0], stage[1], conclusionByStage.getOrDefault(stage[0], fallback)));
		}
		return "[" + String.join(",", items) + "]";
	}

	/** 지정한 단계 id만 담는다(누락·빈 목록 시험용). */
	public static String stagesJsonOnly(String... ids) {
		List<String> wanted = List.of(ids);
		List<String> items = new ArrayList<>();
		for (String[] stage : STAGES) {
			if (wanted.contains(stage[0])) {
				items.add(stageJson(stage[0], stage[1], "success"));
			}
		}
		return "[" + String.join(",", items) + "]";
	}

	private static String stageJson(String id, String label, String state) {
		if (state.equals("none")) {
			return "{\"id\": \"%s\", \"label\": \"%s\", \"latest\": null, \"lastSuccessAt\": null, \"inFlight\": false}".formatted(id, label);
		}
		boolean running = state.equals("running");
		boolean noConclusion = running || state.equals("completed-without-conclusion");
		String conclusion = noConclusion ? "null" : "\"" + state + "\"";
		String status = running ? "in_progress" : "completed";
		String lastSuccess = state.equals("success") ? "\"2026-10-10T01:05:00Z\"" : "null";
		boolean deploy = id.equals("deploy");
		String runId = deploy ? "303" : "101";
		String repository = deploy ? "easysubway-platform" : "easysubway-data";
		return ("{\"id\": \"%s\", \"label\": \"%s\", \"latest\": {\"runId\": " + runId + ", \"url\": \"https://github.com/AquilaXk/" + repository + "/actions/runs/" + runId + "\", "
			+ "\"status\": \"%s\", \"conclusion\": %s, \"createdAt\": \"2026-10-10T01:00:00Z\", \"updatedAt\": \"2026-10-10T01:05:00Z\"}, "
			+ "\"lastSuccessAt\": %s, \"inFlight\": %s}").formatted(id, label, status, conclusion, lastSuccess, running);
	}

	public static String failureIssueJson(long number) {
		return """
			[{"number": %d, "title": "[Fix] 원천 자동 갱신 실패: 후보 갱신 (nationwide-candidate-refresh.yml)", "url": "https://github.com/AquilaXk/easysubway-data/issues/%d", "createdAt": "2026-10-10T00:30:00Z"}]
			""".formatted(number, number);
	}

	public static String stuckPullJson() {
		return """
			[{"number": 7, "title": "자동화 PR", "url": "https://github.com/AquilaXk/easysubway-data/pull/7", "branch": "automation/636-current-topology-refresh-100", "stage": "capital-topology-refresh", "createdAt": "2026-10-09T20:00:00Z", "reason": "BEHIND"}]
			""";
	}
}

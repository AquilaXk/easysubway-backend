package com.easysubway.datapack.application.service;

import java.time.Instant;

/** data 레포 build-automation-status.mjs가 만드는 snapshot(v1)의 시험용 원문. */
public final class AutomationStatusFixtures {

	public static final Instant GENERATED_AT = Instant.parse("2026-10-10T03:00:00Z");

	private AutomationStatusFixtures() {
	}

	public static String validJson() {
		return validJson(GENERATED_AT.toString(), "2026-10-11T15:00:00.000Z", "[]", "[]", "false");
	}

	public static String validJson(String generatedAt, String expiresAt, String failureIssues, String stuckPulls, String candidateInFlight) {
		return """
			{
			  "schemaVersion": 1,
			  "artifactKind": "automation-status-snapshot",
			  "generatedAt": "%s",
			  "activeDatapack": {"releaseSequence": 129, "publishedAt": "2026-10-09T10:29:36.200Z", "expiresAt": "%s"},
			  "stages": [
			    {"id": "refresh", "label": "원천 갱신", "latest": {"runId": 101, "url": "https://github.com/AquilaXk/easysubway-data/actions/runs/101", "status": "completed", "conclusion": "success", "createdAt": "2026-10-10T01:00:00Z", "updatedAt": "2026-10-10T01:05:00Z"}, "lastSuccessAt": "2026-10-10T01:05:00Z", "inFlight": false},
			    {"id": "publish", "label": "발행", "latest": null, "lastSuccessAt": null, "inFlight": false},
			    {"id": "deploy", "label": "배포", "latest": {"runId": 303, "url": "https://github.com/AquilaXk/easysubway-platform/actions/runs/303", "status": "in_progress", "conclusion": null, "createdAt": "2026-10-10T02:50:00Z", "updatedAt": "2026-10-10T02:55:00Z"}, "lastSuccessAt": null, "inFlight": true}
			  ],
			  "failureIssues": %s,
			  "stuck": {"pulls": %s, "claims": [], "behindCap": []},
			  "candidateInFlight": %s
			}
			""".formatted(generatedAt, expiresAt, failureIssues, stuckPulls, candidateInFlight);
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

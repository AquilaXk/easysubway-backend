package com.easysubway.datapack.application.service;

import com.easysubway.datapack.domain.AutomationAssessment;
import com.easysubway.datapack.domain.AutomationAssessment.Finding;
import com.easysubway.datapack.domain.AutomationAssessment.Level;
import com.easysubway.datapack.domain.AutomationStatusSnapshot;
import com.easysubway.datapack.domain.AutomationStatusSnapshot.ExpiringSource;
import com.easysubway.datapack.domain.AutomationStatusSnapshot.RefreshState;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 자동화 상태 판정(순수 함수). 가장 심한 항목이 앞에 오고, 값이 없으면 정상으로 채우지 않는다.
 * 만료 임박(36시간 미만 + 진행 중 후보 없음)과 게시 중단(마지막 수신 45분 초과)은 시간이 지나며 변하므로 렌더 시각으로 계산한다.
 * 원천 근거 만료(backend#507)도 같다: snapshot은 만료 시각만 싣고, 남은 시간과 이상·주의는 렌더 시각으로 계산한다.
 * 6시간 이하(이미 만료 포함)이고 근거를 갱신하는 작업이 실패했거나 막혀 있으면 이상, 그 밖에 12시간 이하이면 주의다.
 */
@Component
public class AutomationStatusAssessor {

	public static final Duration EXPIRY_WARNING = Duration.ofHours(36);
	public static final Duration PUBLISH_STALE_AFTER = Duration.ofMinutes(45);

	/** 원천 근거가 이 시간 안에 만료되는데 갱신 작업이 실패했거나 막혀 있으면 이상이다. */
	public static final Duration SOURCE_EXPIRY_FAILURE = Duration.ofHours(6);

	/** 원천 근거가 이 시간 안에 만료되면 주의다. */
	public static final Duration SOURCE_EXPIRY_WARNING = Duration.ofHours(12);

	private static final int SOURCE_NAMES_SHOWN = 3;

	/** 수신 전 상태가 이 시간을 넘기면 이상으로 본다: 게시 변수·토큰 오류나 저장 행 유실이 조용히 계속되지 않게 한다. */
	public static final Duration NOT_RECEIVED_ALERT_AFTER = Duration.ofHours(1);

	/** snapshot이 담아야 하는 단계(data build-automation-status.mjs STATUS_STAGES와 같은 순서). */
	static final List<StageName> REQUIRED_STAGES = List.of(
		new StageName("refresh", "원천 갱신"), new StageName("registration", "원천 등록"), new StageName("reverification", "원천 재확인"),
		new StageName("candidate", "후보 갱신"), new StageName("rc", "후보 검증(RC)"), new StageName("compat", "앱 호환성 검증"),
		new StageName("promotion", "승격"), new StageName("publish", "발행"), new StageName("deploy", "배포"));

	private static final Set<String> HEALTHY_CONCLUSIONS = Set.of("success", "skipped");
	private static final Set<String> SOFT_CONCLUSIONS = Set.of("cancelled", "neutral", "action_required", "stale");

	record StageName(String id, String label) {
	}

	/**
	 * @param observingSince 이 서버가 수신을 기다리기 시작한 시각(기동 시각). snapshot이 하나도 없을 때 "수신 전"이 얼마나 오래됐는지 가늠한다.
	 */
	public AutomationAssessment assess(Optional<StoredAutomationStatus> stored, Instant now, Instant observingSince) {
		if (stored.isEmpty()) {
			if (Duration.between(observingSince, now).compareTo(NOT_RECEIVED_ALERT_AFTER) > 0) {
				Finding tooLong = new Finding(Level.FAILURE, "NOT_RECEIVED_LONG",
					"자동화 상태를 1시간 넘게 받지 못했습니다(게시 설정, 서비스 토큰 또는 저장된 값을 확인해야 합니다)");
				return new AutomationAssessment(Level.FAILURE, tooLong.message(), List.of(tooLong), false, null, null);
			}
			Finding notReceived = new Finding(Level.UNKNOWN, "NOT_RECEIVED", "자동화 상태를 아직 받지 못했습니다(수신 전)");
			return new AutomationAssessment(Level.UNKNOWN, notReceived.message(), List.of(notReceived), false, null, null);
		}
		AutomationStatusSnapshot snapshot = stored.get().snapshot();
		Duration remaining = Duration.between(now, snapshot.activeDatapack().expiresAt());
		Duration receivedAge = Duration.between(stored.get().receivedAt(), now);
		List<Finding> findings = new ArrayList<>();
		if (remaining.compareTo(Duration.ZERO) <= 0) {
			findings.add(new Finding(Level.FAILURE, "DATAPACK_EXPIRED", "활성 데이터팩이 만료되었습니다(순번 "
				+ snapshot.activeDatapack().releaseSequence() + ")"));
		}
		if (receivedAge.compareTo(PUBLISH_STALE_AFTER) > 0) {
			findings.add(new Finding(Level.FAILURE, "STATUS_STALE", "자동화 상태 게시가 멈췄습니다(마지막 수신 "
				+ receivedAge.toMinutes() + "분 전)"));
		}
		if (!snapshot.failureIssues().isEmpty()) {
			findings.add(new Finding(Level.FAILURE, "FAILURE_ISSUES", "열린 자동화 실패 이슈 " + snapshot.failureIssues().size() + "건"));
		}
		List<ExpiringSource> expiring = snapshot.reportsExpiringSources() ? sortedByExpiry(snapshot.expiringSources()) : List.of();
		addSourceFailure(expiring, now, findings);
		if (snapshot.stuck().total() > 0) {
			Level level = snapshot.stuck().behindCap().isEmpty() ? Level.WARNING : Level.FAILURE;
			findings.add(new Finding(level, "STUCK_AUTOMATION", "막힌 자동화 " + snapshot.stuck().total() + "건"));
		}
		addStageFindings(snapshot, findings);
		if (remaining.compareTo(Duration.ZERO) > 0 && remaining.compareTo(EXPIRY_WARNING) < 0 && !snapshot.candidateInFlight()) {
			findings.add(new Finding(Level.WARNING, "EXPIRY_SOON", "활성 데이터팩 만료가 가까워지고 있습니다(남은 시간 "
				+ describe(remaining) + "), 진행 중인 후보가 없습니다"));
		}
		addSourceWarning(expiring, now, findings);
		Level level = findings.stream().anyMatch((finding) -> finding.level() == Level.FAILURE) ? Level.FAILURE
			: findings.isEmpty() ? Level.OK : Level.WARNING;
		String headline = findings.isEmpty() ? healthyHeadline(snapshot) : findings.get(0).message();
		return new AutomationAssessment(level, headline, List.copyOf(findings), true, remaining, receivedAge);
	}

	/** 원천 근거 하나의 판정. 12시간 밖이면 OK다(표에는 보이지만 판정에는 영향이 없다). */
	public static Level sourceLevel(ExpiringSource source, Instant now) {
		Duration remaining = Duration.between(now, source.freshUntil());
		boolean refreshBroken = source.refreshState() == RefreshState.FAILED || source.refreshState() == RefreshState.BLOCKED;
		if (remaining.compareTo(SOURCE_EXPIRY_FAILURE) <= 0 && refreshBroken) {
			return Level.FAILURE;
		}
		return remaining.compareTo(SOURCE_EXPIRY_WARNING) <= 0 ? Level.WARNING : Level.OK;
	}

	private static List<ExpiringSource> sortedByExpiry(List<ExpiringSource> sources) {
		return sources.stream().sorted(Comparator.comparing(ExpiringSource::freshUntil).thenComparing(ExpiringSource::sourceId)).toList();
	}

	private static void addSourceFailure(List<ExpiringSource> sources, Instant now, List<Finding> findings) {
		List<ExpiringSource> failing = sources.stream().filter((source) -> sourceLevel(source, now) == Level.FAILURE).toList();
		if (!failing.isEmpty()) {
			findings.add(new Finding(Level.FAILURE, "SOURCE_EXPIRY_BLOCKED", "원천 자료 " + failing.size()
				+ "건이 6시간 안에 만료되는데 갱신 작업이 실패했거나 막혀 있습니다: " + sourceList(failing, now)));
		}
	}

	private static void addSourceWarning(List<ExpiringSource> sources, Instant now, List<Finding> findings) {
		List<ExpiringSource> soon = sources.stream().filter((source) -> sourceLevel(source, now) == Level.WARNING).toList();
		if (!soon.isEmpty()) {
			findings.add(new Finding(Level.WARNING, "SOURCE_EXPIRY_SOON", "원천 자료 " + soon.size() + "건이 12시간 안에 만료됩니다: " + sourceList(soon, now)));
		}
	}

	private static String sourceList(List<ExpiringSource> sources, Instant now) {
		String names = sources.stream().limit(SOURCE_NAMES_SHOWN).map((source) -> source.name() + "(" + sourceWhen(source, now) + ")")
			.collect(java.util.stream.Collectors.joining(", "));
		return sources.size() > SOURCE_NAMES_SHOWN ? names + " 외 " + (sources.size() - SOURCE_NAMES_SHOWN) + "건" : names;
	}

	private static String sourceWhen(ExpiringSource source, Instant now) {
		Duration remaining = Duration.between(now, source.freshUntil());
		String when = remaining.compareTo(Duration.ZERO) > 0 ? describe(remaining) + " 남음" : "만료됨, " + describe(remaining) + " 지남";
		return source.refreshState() == RefreshState.NONE ? when + ", 자동 갱신 없음" : when;
	}

	/** 단계 목록을 실제로 본다: 빠진 단계는 이상이고, 최근 run이 실패한 단계는 이상이다. */
	private static void addStageFindings(AutomationStatusSnapshot snapshot, List<Finding> findings) {
		Map<String, AutomationStatusSnapshot.Stage> byId = new HashMap<>();
		for (AutomationStatusSnapshot.Stage stage : snapshot.stages()) {
			byId.put(stage.id(), stage);
		}
		List<String> missing = REQUIRED_STAGES.stream().filter((required) -> !byId.containsKey(required.id())).map(StageName::label).toList();
		if (!missing.isEmpty()) {
			findings.add(new Finding(Level.FAILURE, "STAGES_MISSING", "자동화 단계 정보가 빠져 있습니다(" + String.join(", ", missing) + ")"));
		}
		for (StageName required : REQUIRED_STAGES) {
			AutomationStatusSnapshot.Stage stage = byId.get(required.id());
			if (stage == null || stage.latest() == null || !"completed".equals(stage.latest().status())) {
				continue;
			}
			String conclusion = stage.latest().conclusion();
			if (conclusion != null && HEALTHY_CONCLUSIONS.contains(conclusion)) {
				continue;
			}
			Level level = conclusion != null && SOFT_CONCLUSIONS.contains(conclusion) ? Level.WARNING : Level.FAILURE;
			String what = level == Level.WARNING ? "마지막 실행이 완료되지 않았습니다" : "마지막 실행이 실패했습니다";
			findings.add(new Finding(level, "STAGE_FAILED", required.label() + " 단계의 " + what + "(" + (conclusion == null ? "결과 없음" : conclusion) + ")"));
		}
	}

	private static String healthyHeadline(AutomationStatusSnapshot snapshot) {
		List<String> unrecorded = snapshot.stages().stream().filter((stage) -> stage.latest() == null).map(AutomationStatusSnapshot.Stage::label).toList();
		if (unrecorded.isEmpty()) {
			return "정상: 모든 자동화 단계의 최근 실행이 정상입니다";
		}
		return "정상: 실행 기록이 있는 단계에 이상이 없습니다(실행 기록이 없는 단계: " + String.join(", ", unrecorded) + ")";
	}

	/** 저장된 값이 검증을 통과하지 못할 때. 정상이나 수신 전으로 숨기지 않고 이상으로 드러낸다. */
	public AutomationAssessment storedPayloadInvalid() {
		Finding invalid = new Finding(Level.FAILURE, "STORED_INVALID", "저장된 자동화 상태를 읽을 수 없습니다");
		return new AutomationAssessment(Level.FAILURE, invalid.message(), List.of(invalid), true, null, null);
	}

	public static String describe(Duration duration) {
		long totalMinutes = Math.abs(duration.toMinutes());
		long days = totalMinutes / (24 * 60);
		long hours = (totalMinutes / 60) % 24;
		long minutes = totalMinutes % 60;
		StringBuilder text = new StringBuilder();
		if (days > 0) {
			text.append(days).append("일 ");
		}
		if (days > 0 || hours > 0) {
			text.append(hours).append("시간 ");
		}
		text.append(minutes).append("분");
		return text.toString();
	}
}

package com.easysubway.datapack.application.service;

import com.easysubway.datapack.domain.AutomationAssessment;
import com.easysubway.datapack.domain.AutomationAssessment.Finding;
import com.easysubway.datapack.domain.AutomationAssessment.Level;
import com.easysubway.datapack.domain.AutomationStatusSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 자동화 상태 판정(순수 함수). 가장 심한 항목이 앞에 오고, 값이 없으면 정상으로 채우지 않는다.
 * 만료 임박(36시간 미만 + 진행 중 후보 없음)과 게시 중단(마지막 수신 45분 초과)은 시간이 지나며 변하므로 렌더 시각으로 계산한다.
 */
@Component
public class AutomationStatusAssessor {

	public static final Duration EXPIRY_WARNING = Duration.ofHours(36);
	public static final Duration PUBLISH_STALE_AFTER = Duration.ofMinutes(45);

	public AutomationAssessment assess(Optional<StoredAutomationStatus> stored, Instant now) {
		if (stored.isEmpty()) {
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
		if (snapshot.stuck().total() > 0) {
			Level level = snapshot.stuck().behindCap().isEmpty() ? Level.WARNING : Level.FAILURE;
			findings.add(new Finding(level, "STUCK_AUTOMATION", "막힌 자동화 " + snapshot.stuck().total() + "건"));
		}
		if (remaining.compareTo(Duration.ZERO) > 0 && remaining.compareTo(EXPIRY_WARNING) < 0 && !snapshot.candidateInFlight()) {
			findings.add(new Finding(Level.WARNING, "EXPIRY_SOON", "활성 데이터팩 만료가 가까워지고 있습니다(남은 시간 "
				+ describe(remaining) + "), 진행 중인 후보가 없습니다"));
		}
		Level level = findings.stream().anyMatch((finding) -> finding.level() == Level.FAILURE) ? Level.FAILURE
			: findings.isEmpty() ? Level.OK : Level.WARNING;
		String headline = findings.isEmpty() ? "정상: 모든 자동화 단계가 정상입니다" : findings.get(0).message();
		return new AutomationAssessment(level, headline, List.copyOf(findings), true, remaining, receivedAge);
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

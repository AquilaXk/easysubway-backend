package com.easysubway.datapack.adapter.in.web;

import com.easysubway.datapack.application.port.in.AutomationStatusUseCase;
import com.easysubway.datapack.application.port.in.AutomationStatusUseCase.Reading;
import com.easysubway.datapack.application.service.AutomationStatusAssessor;
import com.easysubway.datapack.domain.AutomationAssessment;
import com.easysubway.datapack.domain.AutomationAssessment.Level;
import com.easysubway.datapack.domain.AutomationStatusSnapshot;
import com.easysubway.datapack.domain.AutomationStatusSnapshot.RunSummary;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 데이터팩 자동화 상태(backend#500, data#1084). 사람은 이상이 날 때만 이 화면을 보고 개입한다: 상단은 가장 심한 이상 하나,
 * 아래는 활성 데이터팩, 단계별 최근 실행, 열린 실패 이슈, 막힌 자동화다. 값이 없으면 정상으로 채우지 않고 수신 전으로 보인다.
 */
@Controller
class AutomationStatusAdminPageController {

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

	private final AutomationStatusUseCase useCase;

	AutomationStatusAdminPageController(AutomationStatusUseCase useCase) {
		this.useCase = useCase;
	}

	@GetMapping("/admin/datapack/automation/page")
	@PreAuthorize("hasAuthority('admin.datapack.read')")
	String page(Model model) {
		model.addAttribute("automation", AutomationView.from(useCase.read()));
		return "admin/datapack/automation/page";
	}

	record AutomationView(
		String levelLabel,
		String tone,
		String headline,
		List<FindingView> findings,
		boolean received,
		String receivedText,
		DatapackView datapack,
		List<StageView> stages,
		List<IssueView> issues,
		List<StuckView> stuck
	) {

		static AutomationView from(Reading reading) {
			AutomationAssessment assessment = reading.assessment();
			List<FindingView> findings = assessment.findings().stream()
				.map((finding) -> new FindingView(tone(finding.level()), finding.message())).toList();
			AutomationStatusSnapshot snapshot = reading.snapshot().orElse(null);
			String receivedText = reading.receivedAt() == null ? "수신 전"
				: TIME.format(reading.receivedAt()) + " (" + AutomationStatusAssessor.describe(assessment.receivedAge() == null
					? Duration.ZERO : assessment.receivedAge()) + " 전)";
			if (snapshot == null) {
				return new AutomationView(levelLabel(assessment.level()), tone(assessment.level()), assessment.headline(), findings,
					assessment.received(), receivedText, null, List.of(), List.of(), List.of());
			}
			return new AutomationView(levelLabel(assessment.level()), tone(assessment.level()), assessment.headline(), findings,
				true, receivedText, DatapackView.of(snapshot, assessment),
				snapshot.stages().stream().map(StageView::of).toList(),
				snapshot.failureIssues().stream().map((issue) -> new IssueView(issue.number(), issue.title(), issue.url(), TIME.format(issue.createdAt()))).toList(),
				stuck(snapshot));
		}

		private static List<StuckView> stuck(AutomationStatusSnapshot snapshot) {
			List<StuckView> rows = new java.util.ArrayList<>();
			snapshot.stuck().pulls().forEach((pull) -> rows.add(new StuckView(
				"BEHIND".equals(pull.reason()) ? "뒤처짐" : "오래 열려 있음",
				"#" + pull.number() + " " + pull.title(), pull.url(), "열림 " + TIME.format(pull.createdAt()))));
			snapshot.stuck().claims().forEach((claim) -> rows.add(new StuckView(
				"주인 없는 claim", claim.branch(), null, "마지막 커밋 " + TIME.format(claim.committedAt()))));
			snapshot.stuck().behindCap().forEach((cap) -> rows.add(new StuckView(
				"재생성 상한 도달", cap.stage() + " 단계 #" + cap.number(), null, "24시간 안에 " + cap.closures() + "회 닫힘")));
			return List.copyOf(rows);
		}

		private static String levelLabel(Level level) {
			return switch (level) {
				case OK -> "정상";
				case WARNING -> "주의";
				case FAILURE -> "이상";
				case UNKNOWN -> "수신 전";
			};
		}

		private static String tone(Level level) {
			return switch (level) {
				case OK -> "good";
				case WARNING -> "warn";
				case FAILURE -> "bad";
				case UNKNOWN -> "info";
			};
		}
	}

	record FindingView(String tone, String message) {
	}

	record DatapackView(long releaseSequence, String publishedText, String expiresText, String remainingText, String remainingTone) {

		static DatapackView of(AutomationStatusSnapshot snapshot, AutomationAssessment assessment) {
			Duration remaining = assessment.remaining();
			boolean expired = remaining.isNegative() || remaining.isZero();
			String remainingText = expired ? "만료됨 (" + AutomationStatusAssessor.describe(remaining) + " 지남)"
				: AutomationStatusAssessor.describe(remaining) + " 남음";
			String tone = expired ? "bad" : remaining.compareTo(AutomationStatusAssessor.EXPIRY_WARNING) < 0 ? "warn" : "good";
			return new DatapackView(snapshot.activeDatapack().releaseSequence(),
				TIME.format(snapshot.activeDatapack().publishedAt()), TIME.format(snapshot.activeDatapack().expiresAt()), remainingText, tone);
		}
	}

	record StageView(String label, String resultText, String tone, String startedText, String lastSuccessText, String url) {

		static StageView of(AutomationStatusSnapshot.Stage stage) {
			RunSummary latest = stage.latest();
			String lastSuccess = stage.lastSuccessAt() == null ? "없음" : TIME.format(stage.lastSuccessAt());
			if (latest == null) {
				return new StageView(stage.label(), "기록 없음", "info", "—", lastSuccess, null);
			}
			String result;
			String tone;
			if (!"completed".equals(latest.status())) {
				result = "진행 중";
				tone = "info";
			} else {
				String conclusion = latest.conclusion() == null ? "" : latest.conclusion();
				switch (conclusion) {
					case "success" -> {
						result = "성공";
						tone = "good";
					}
					case "failure", "timed_out", "startup_failure" -> {
						result = "실패";
						tone = "bad";
					}
					case "cancelled" -> {
						result = "취소";
						tone = "warn";
					}
					case "skipped" -> {
						result = "건너뜀";
						tone = "info";
					}
					default -> {
						result = conclusion.isEmpty() ? "결과 없음" : conclusion;
						tone = "warn";
					}
				}
			}
			return new StageView(stage.label(), result, tone, TIME.format(latest.createdAt()), lastSuccess, latest.url());
		}
	}

	record IssueView(long number, String title, String url, String createdText) {
	}

	/** link가 null이면 링크 없이 이름만 보인다. */
	record StuckView(String kind, String title, String link, String detail) {
	}
}

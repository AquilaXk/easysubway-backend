package com.easysubway.collection.adapter.in.web;

import com.easysubway.collection.application.port.in.DataCollectionUseCase;
import com.easysubway.collection.application.port.in.RunDataCollectionCommand;
import com.easysubway.collection.domain.DataCollectionRun;
import com.easysubway.collection.domain.DataCollectionSource;
import com.easysubway.collection.domain.DataCollectionStatus;
import java.security.Principal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
class DataCollectionAdminPageController {

	private static final int DEFAULT_RECENT_RUN_LIMIT = 20;

	private final DataCollectionUseCase dataCollectionUseCase;

	DataCollectionAdminPageController(DataCollectionUseCase dataCollectionUseCase) {
		this.dataCollectionUseCase = dataCollectionUseCase;
	}

	@GetMapping("/admin/data-collections/page")
	String dataCollectionPage(Model model) {
		model.addAttribute("sourceOptions", sourceOptions());
		model.addAttribute("runs", recentRunRows());
		return "admin/collections/list";
	}

	@PostMapping("/admin/data-collections/page/run")
	String runCollectionFromPage(
		@RequestParam DataCollectionSource source,
		Principal principal
	) {
		dataCollectionUseCase.runCollection(new RunDataCollectionCommand(source, principal.getName()));
		return "redirect:/admin/data-collections/page";
	}

	private List<DataCollectionRunRow> recentRunRows() {
		return dataCollectionUseCase.listRecentRuns(DEFAULT_RECENT_RUN_LIMIT)
			.stream()
			.map(DataCollectionRunRow::from)
			.toList();
	}

	private static List<DataCollectionSourceOption> sourceOptions() {
		return Arrays.stream(DataCollectionSource.values())
			.map(source -> new DataCollectionSourceOption(source, sourceLabel(source)))
			.toList();
	}

	private static String sourceLabel(DataCollectionSource source) {
		return switch (source) {
			case TRANSIT_MASTER -> "도시철도 마스터";
		};
	}

	private static String statusLabel(DataCollectionStatus status) {
		return switch (status) {
			case RUNNING -> "실행 중";
			case COMPLETED -> "완료";
			case FAILED -> "실패";
		};
	}

	record DataCollectionRunRow(
		String runId,
		String sourceLabel,
		String statusLabel,
		String requestedBy,
		LocalDateTime startedAt,
		LocalDateTime completedAt,
		int collectedCount,
		String failureMessage,
		boolean retryable,
		String operatorAction
	) {

		static DataCollectionRunRow from(DataCollectionRun run) {
			return new DataCollectionRunRow(
				run.runId(),
				DataCollectionAdminPageController.sourceLabel(run.source()),
				DataCollectionAdminPageController.statusLabel(run.status()),
				run.requestedBy(),
				run.startedAt(),
				run.completedAt(),
				run.collectedCount(),
				run.failureMessage(),
				run.retryable(),
				run.operatorAction()
			);
		}

		public String failureLabel() {
			if (failureMessage == null || failureMessage.isBlank()) {
				return "-";
			}
			return failureMessage;
		}

		public String completedAtLabel() {
			if (completedAt == null) {
				return "-";
			}
			return completedAt.toString();
		}

		public String retryableLabel() {
			return retryable ? "가능" : "불필요";
		}
	}

	record DataCollectionSourceOption(DataCollectionSource value, String label) {
	}
}

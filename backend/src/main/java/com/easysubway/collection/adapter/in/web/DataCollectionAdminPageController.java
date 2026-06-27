package com.easysubway.collection.adapter.in.web;

import com.easysubway.common.web.pagination.AdminPageRequest;
import com.easysubway.common.web.pagination.EgovPaginationView;
import com.easysubway.collection.application.port.in.DataCollectionUseCase;
import com.easysubway.collection.application.port.in.RunDataCollectionCommand;
import com.easysubway.collection.domain.DataCollectionRun;
import com.easysubway.collection.domain.DataCollectionRunStep;
import com.easysubway.collection.domain.DataCollectionSource;
import com.easysubway.collection.domain.DataCollectionStepStatus;
import com.easysubway.collection.domain.DataCollectionStatus;
import java.security.Principal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
class DataCollectionAdminPageController {

	private final DataCollectionUseCase dataCollectionUseCase;

	DataCollectionAdminPageController(DataCollectionUseCase dataCollectionUseCase) {
		this.dataCollectionUseCase = dataCollectionUseCase;
	}

	@GetMapping("/admin/data-collections/page")
	String dataCollectionPage(
		@RequestParam(required = false) Integer page,
		@RequestParam(required = false) Integer size,
		Model model
	) {
		AdminPageRequest pageRequest = AdminPageRequest.of(page, size);
		List<DataCollectionRunRow> runs = recentRunRows(pageRequest);
		EgovPaginationView pageView = EgovPaginationView.fromSlice(pageRequest.page(), pageRequest.size(), runs.size());
		model.addAttribute("sourceOptions", sourceOptions());
		model.addAttribute("runs", pageView.visibleItems(runs));
		model.addAttribute("page", pageView);
		model.addAttribute("paginationLinks", pageView.links("/admin/data-collections/page", Collections.emptyMap()));
		return "admin/collections/list";
	}

	@PostMapping("/admin/data-collections/page/run")
	@PreAuthorize("hasAuthority('admin.data.operate')")
	String runCollectionFromPage(
		@RequestParam DataCollectionSource source,
		Principal principal
	) {
		dataCollectionUseCase.runCollection(new RunDataCollectionCommand(source, principal.getName()));
		return "redirect:/admin/data-collections/page";
	}

	private List<DataCollectionRunRow> recentRunRows(AdminPageRequest pageRequest) {
		return dataCollectionUseCase.listRecentRuns(pageRequest.limitForHasNext(), pageRequest.offset())
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
		String operatorAction,
		List<DataCollectionRunStepRow> steps
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
				run.operatorAction(),
				run.steps().stream()
					.map(DataCollectionRunStepRow::from)
					.toList()
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

	record DataCollectionRunStepRow(
		String name,
		String statusLabel,
		String inputSource,
		String artifactReference,
		String checksum,
		int recordCount,
		String failureMessage
	) {

		static DataCollectionRunStepRow from(DataCollectionRunStep step) {
			return new DataCollectionRunStepRow(
				step.name(),
				stepStatusLabel(step.status()),
				valueOrDash(step.inputSource()),
				valueOrDash(step.artifactReference()),
				valueOrDash(step.checksum()),
				step.recordCount(),
				valueOrDash(step.failureMessage())
			);
		}

		private static String stepStatusLabel(DataCollectionStepStatus status) {
			return switch (status) {
				case COMPLETED -> "완료";
				case FAILED -> "실패";
				case SKIPPED -> "건너뜀";
				case MANUAL_REQUIRED -> "수동 필요";
			};
		}

		private static String valueOrDash(String value) {
			if (value == null || value.isBlank()) {
				return "-";
			}
			return value;
		}
	}

	record DataCollectionSourceOption(DataCollectionSource value, String label) {
	}
}

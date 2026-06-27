package com.easysubway.admin.batch.adapter.in.web;

import com.easysubway.admin.web.AdminFormErrorView;
import com.easysubway.admin.audit.application.service.AdminAuditWriter;
import com.easysubway.admin.audit.domain.AdminAuditOutcome;
import com.easysubway.admin.batch.application.service.AdminBatchOperationService;
import com.easysubway.admin.batch.domain.AdminBatchJob;
import com.easysubway.collection.domain.DataCollectionRun;
import com.easysubway.collection.domain.DataCollectionRunStep;
import com.easysubway.collection.domain.DataCollectionStepStatus;
import com.easysubway.collection.domain.DataCollectionStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import java.security.Principal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
class AdminBatchPageController {

	private static final int DEFAULT_RECENT_RUN_LIMIT = 20;

	private final AdminBatchOperationService batchOperationService;
	private final AdminAuditWriter auditWriter;

	AdminBatchPageController(AdminBatchOperationService batchOperationService, AdminAuditWriter auditWriter) {
		this.batchOperationService = batchOperationService;
		this.auditWriter = auditWriter;
	}

	@GetMapping("/admin/batches/page")
	String batchPage(Model model) {
		model.addAttribute("jobs", batchOperationService.listJobs().stream().map(BatchJobRow::from).toList());
		model.addAttribute("runs", batchOperationService.listExecutions(DEFAULT_RECENT_RUN_LIMIT)
			.stream()
			.flatMap(run -> BatchRunRow.from(run).stream())
			.toList());
		return "admin/batches/list";
	}

	@PostMapping("/admin/batches/{jobId}/runs/{runId}/retry")
	@PreAuthorize("hasAuthority('admin.batch.retry')")
	String retry(
		@PathVariable String jobId,
		@PathVariable String runId,
		@Valid @ModelAttribute("retryBatchRunForm") RetryBatchRunForm form,
		BindingResult bindingResult,
		Principal principal,
		Authentication authentication,
		HttpServletRequest request,
		Model model,
		HttpServletResponse response
	) {
		if (bindingResult.hasErrors()) {
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			batchPage(model);
			AdminFormErrorView.expose(model, bindingResult);
			return "admin/batches/list";
		}
		try {
			DataCollectionRun retried = batchOperationService.retry(jobId, runId, principal.getName());
			auditWriter.batchOperation(
				authentication,
				request,
				"BATCH_JOB",
				jobId,
				"RETRY_BATCH_RUN",
				AdminAuditOutcome.SUCCESS,
				"runId=%s retriedRunId=%s".formatted(runId, retried.runId())
			);
		} catch (RuntimeException exception) {
			auditWriter.batchOperation(
				authentication,
				request,
				"BATCH_JOB",
				jobId,
				"RETRY_BATCH_RUN",
				AdminAuditOutcome.FAILURE,
				"runId=%s error=%s".formatted(runId, exception.getMessage())
			);
			throw exception;
		}
		return "redirect:/admin/batches/page";
	}

	record BatchJobRow(String id, String jobName, String label, boolean retryEnabled) {

		static BatchJobRow from(AdminBatchJob job) {
			return new BatchJobRow(job.id(), job.jobName(), job.label(), job.retryEnabled());
		}
	}

	record RetryBatchRunForm(
		@AssertTrue(message = "배치 재처리 요청을 확인해야 합니다.")
		boolean retryRequested
	) {
	}

	record BatchRunRow(
		String runId,
		String jobId,
		String sourceLabel,
		String statusLabel,
		String requestedBy,
		LocalDateTime startedAt,
		LocalDateTime completedAt,
		int collectedCount,
		String failureMessage,
		boolean retryable,
		String operatorAction,
		List<BatchStepRow> steps
	) {

		static Optional<BatchRunRow> from(DataCollectionRun run) {
			return AdminBatchJob.all()
				.stream()
				.filter(candidate -> candidate.source() == run.source())
				.findFirst()
				.map(job -> new BatchRunRow(
					run.runId(),
					job.id(),
					job.label(),
					statusLabel(run.status()),
					run.requestedBy(),
					run.startedAt(),
					run.completedAt(),
					run.collectedCount(),
					valueOrDash(run.failureMessage()),
					run.retryable(),
					run.operatorAction(),
					run.steps().stream().map(BatchStepRow::from).toList()
				));
		}

		public String completedAtLabel() {
			return completedAt == null ? "-" : completedAt.toString();
		}

		public String retryableLabel() {
			return retryable ? "가능" : "불가";
		}

		private static String statusLabel(DataCollectionStatus status) {
			return switch (status) {
				case RUNNING -> "실행 중";
				case COMPLETED -> "완료";
				case FAILED -> "실패";
			};
		}
	}

	record BatchStepRow(String name, String statusLabel, int recordCount, String failureMessage) {

		static BatchStepRow from(DataCollectionRunStep step) {
			return new BatchStepRow(step.name(), stepStatusLabel(step.status()), step.recordCount(), valueOrDash(step.failureMessage()));
		}

		private static String stepStatusLabel(DataCollectionStepStatus status) {
			return switch (status) {
				case COMPLETED -> "완료";
				case FAILED -> "실패";
				case SKIPPED -> "건너뜀";
				case MANUAL_REQUIRED -> "수동 필요";
			};
		}
	}

	private static String valueOrDash(String value) {
		return value == null || value.isBlank() ? "-" : value;
	}
}

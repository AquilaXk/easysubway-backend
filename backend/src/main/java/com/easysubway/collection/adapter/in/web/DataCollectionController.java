package com.easysubway.collection.adapter.in.web;

import com.easysubway.collection.application.port.in.DataCollectionUseCase;
import com.easysubway.collection.application.port.in.RunDataCollectionCommand;
import com.easysubway.collection.domain.DataCollectionRun;
import com.easysubway.collection.domain.DataCollectionSource;
import com.easysubway.collection.domain.DataCollectionStatus;
import com.easysubway.common.web.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.security.Principal;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class DataCollectionController {

	private static final int DEFAULT_RECENT_RUN_LIMIT = 20;

	private final DataCollectionUseCase dataCollectionUseCase;

	DataCollectionController(DataCollectionUseCase dataCollectionUseCase) {
		this.dataCollectionUseCase = dataCollectionUseCase;
	}

	@PostMapping("/admin/data-collections/runs")
	ApiResponse<DataCollectionRunResponse> runCollection(
		@Valid @RequestBody RunDataCollectionRequest request,
		Principal principal
	) {
		DataCollectionRun run = dataCollectionUseCase.runCollection(
			new RunDataCollectionCommand(request.source(), principal.getName())
		);
		return ApiResponse.ok(DataCollectionRunResponse.from(run));
	}

	@GetMapping("/admin/data-collections/runs")
	ApiResponse<List<DataCollectionRunResponse>> listRecentRuns() {
		return ApiResponse.ok(dataCollectionUseCase.listRecentRuns(DEFAULT_RECENT_RUN_LIMIT).stream()
			.map(DataCollectionRunResponse::from)
			.toList());
	}

	record RunDataCollectionRequest(
		@NotNull(message = "수집 대상을 선택해야 합니다.")
		DataCollectionSource source
	) {
	}

	record DataCollectionRunResponse(
		String runId,
		DataCollectionSource source,
		DataCollectionStatus status,
		String requestedBy,
		LocalDateTime startedAt,
		LocalDateTime completedAt,
		int collectedCount,
		String failureMessage
	) {

		static DataCollectionRunResponse from(DataCollectionRun run) {
			return new DataCollectionRunResponse(
				run.runId(),
				run.source(),
				run.status(),
				run.requestedBy(),
				run.startedAt(),
				run.completedAt(),
				run.collectedCount(),
				run.failureMessage()
			);
		}
	}
}

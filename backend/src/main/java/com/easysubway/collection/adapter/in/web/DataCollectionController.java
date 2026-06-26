package com.easysubway.collection.adapter.in.web;

import com.easysubway.collection.application.port.in.DataCollectionUseCase;
import com.easysubway.collection.application.port.in.RunDataCollectionCommand;
import com.easysubway.collection.domain.DataCollectionRun;
import com.easysubway.collection.domain.DataCollectionRunStep;
import com.easysubway.collection.domain.DataCollectionSource;
import com.easysubway.collection.domain.DataCollectionStepStatus;
import com.easysubway.collection.domain.DataCollectionStatus;
import com.easysubway.collection.domain.InvalidDataCollectionException;
import com.easysubway.common.web.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.security.Principal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

	@GetMapping("/admin/data-sources")
	ApiResponse<List<DataCollectionSourceResponse>> dataSources() {
		return ApiResponse.ok(Arrays.stream(DataCollectionSource.values())
			.map(DataCollectionSourceResponse::from)
			.toList());
	}

	@PostMapping("/admin/data-sources/{dataSourceId}/sync")
	ApiResponse<DataCollectionRunResponse> syncDataSource(
		@PathVariable String dataSourceId,
		Principal principal
	) {
		DataCollectionRun run = dataCollectionUseCase.runCollection(
			new RunDataCollectionCommand(dataCollectionSource(dataSourceId), principal.getName())
		);
		return ApiResponse.ok(DataCollectionRunResponse.from(run));
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

	private static DataCollectionSource dataCollectionSource(String dataSourceId) {
		if (dataSourceId == null || dataSourceId.isBlank()) {
			throw new InvalidDataCollectionException("알 수 없는 데이터 소스입니다.");
		}
		String normalized = dataSourceId.trim()
			.replace('-', '_')
			.toUpperCase(Locale.ROOT);
		try {
			return DataCollectionSource.valueOf(normalized);
		} catch (IllegalArgumentException exception) {
			throw new InvalidDataCollectionException("알 수 없는 데이터 소스입니다.", exception);
		}
	}

	record RunDataCollectionRequest(
		@NotNull(message = "{validation.collection.source.required}")
		DataCollectionSource source
	) {
	}

	record DataCollectionSourceResponse(
		DataCollectionSource id,
		String label,
		String description,
		String syncPath
	) {

		static DataCollectionSourceResponse from(DataCollectionSource source) {
			return new DataCollectionSourceResponse(
				source,
				sourceLabel(source),
				sourceDescription(source),
				"/admin/data-sources/%s/sync".formatted(source.name())
			);
		}

		private static String sourceLabel(DataCollectionSource source) {
			return switch (source) {
				case TRANSIT_MASTER -> "도시철도 마스터";
			};
		}

		private static String sourceDescription(DataCollectionSource source) {
			return switch (source) {
				case TRANSIT_MASTER -> "운영기관, 노선, 역, 출구, 접근성 시설 기준 데이터";
			};
		}
	}

	record DataCollectionRunResponse(
		String runId,
		DataCollectionSource source,
		DataCollectionStatus status,
		String requestedBy,
		LocalDateTime startedAt,
		LocalDateTime completedAt,
		int collectedCount,
		String failureMessage,
		boolean retryable,
		String operatorAction,
		List<DataCollectionRunStepResponse> steps
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
				run.failureMessage(),
				run.retryable(),
				run.operatorAction(),
				run.steps().stream()
					.map(DataCollectionRunStepResponse::from)
					.toList()
			);
		}
	}

	record DataCollectionRunStepResponse(
		String name,
		DataCollectionStepStatus status,
		String inputSource,
		String artifactReference,
		String checksum,
		int recordCount,
		String failureMessage
	) {

		static DataCollectionRunStepResponse from(DataCollectionRunStep step) {
			return new DataCollectionRunStepResponse(
				step.name(),
				step.status(),
				step.inputSource(),
				step.artifactReference(),
				step.checksum(),
				step.recordCount(),
				step.failureMessage()
			);
		}
	}
}

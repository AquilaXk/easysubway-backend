package com.easysubway.train.adapter.in.web;

import com.easysubway.common.web.ApiResponse;
import com.easysubway.train.domain.TrainSearchScopePolicy;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class TrainSearchContractController {

	private static final String UNSUPPORTED_CODE = "TRAIN_SEARCH_UNSUPPORTED_TRAIN_TYPE";
	private static final String UNAVAILABLE_CODE = "TRAIN_SEARCH_UNAVAILABLE";

	@GetMapping("/api/v1/trains/stations")
	ResponseEntity<ApiResponse<TrainSearchError>> stations(
		@RequestParam(required = false) String trainType
	) {
		return validateOrFailClosed(trainType);
	}

	@GetMapping("/api/v1/trains/search")
	ResponseEntity<ApiResponse<TrainSearchError>> search(
		@RequestParam(required = false) String trainType
	) {
		return validateOrFailClosed(trainType);
	}

	private ResponseEntity<ApiResponse<TrainSearchError>> validateOrFailClosed(String trainType) {
		if (trainType != null) {
			try {
				TrainSearchScopePolicy.requireSupported(trainType);
			} catch (IllegalArgumentException exception) {
				return ResponseEntity.badRequest().body(new ApiResponse<>(
					false,
					new TrainSearchError(UNSUPPORTED_CODE),
					"지원하지 않는 열차종입니다."
				));
			}
		}

		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ApiResponse<>(
			false,
			new TrainSearchError(UNAVAILABLE_CODE),
			"기차검색은 아직 사용할 수 없습니다."
		));
	}

	record TrainSearchError(String code) {}
}

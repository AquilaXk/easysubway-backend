package com.easysubway.quality.adapter.in.web;

import com.easysubway.common.web.ApiResponse;
import com.easysubway.quality.application.port.in.DataQualityUseCase;
import com.easysubway.quality.domain.DataQualitySummary;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import com.easysubway.transit.domain.DataConfidenceLevel;
import com.easysubway.transit.domain.DataQualityLevel;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class DataQualityController {

	private final DataQualityUseCase dataQualityUseCase;

	DataQualityController(DataQualityUseCase dataQualityUseCase) {
		this.dataQualityUseCase = dataQualityUseCase;
	}

	@GetMapping("/admin/data-quality/summary")
	ApiResponse<DataQualitySummaryResponse> summarizeDataQuality() {
		return ApiResponse.ok(DataQualitySummaryResponse.from(dataQualityUseCase.summarizeDataQuality()));
	}

	record DataQualitySummaryResponse(
		int totalStations,
		int totalExits,
		int totalFacilities,
		Map<DataQualityLevel, Long> stationQualityCounts,
		Map<DataConfidenceLevel, Long> exitConfidenceCounts,
		Map<DataConfidenceLevel, Long> facilityConfidenceCounts,
		long needsVerificationFacilityCount,
		long delayedFacilityStatusCount,
		Map<AccessibilityFacilityStatus, Long> delayedFacilityStatusCounts,
		long missingStationVerificationDateCount
	) {

		static DataQualitySummaryResponse from(DataQualitySummary summary) {
			return new DataQualitySummaryResponse(
				summary.totalStations(),
				summary.totalExits(),
				summary.totalFacilities(),
				summary.stationQualityCounts(),
				summary.exitConfidenceCounts(),
				summary.facilityConfidenceCounts(),
				summary.needsVerificationFacilityCount(),
				summary.delayedFacilityStatusCount(),
				summary.delayedFacilityStatusCounts(),
				summary.missingStationVerificationDateCount()
			);
		}
	}
}

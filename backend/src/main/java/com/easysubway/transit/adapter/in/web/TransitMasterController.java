package com.easysubway.transit.adapter.in.web;

import com.easysubway.common.web.ApiResponse;
import com.easysubway.transit.application.port.in.StationSearchCommand;
import com.easysubway.transit.application.port.in.TransitMasterAdminUseCase;
import com.easysubway.transit.application.port.in.TransitMasterQueryUseCase;
import com.easysubway.transit.application.port.in.UpdateAccessibilityFacilityStatusCommand;
import com.easysubway.transit.domain.AccessibilityFacility;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import com.easysubway.transit.domain.AccessibilityFacilityType;
import com.easysubway.transit.domain.DataConfidenceLevel;
import com.easysubway.transit.domain.DataQualityLevel;
import com.easysubway.transit.domain.DataSourceType;
import com.easysubway.transit.domain.Station;
import com.easysubway.transit.domain.StationExit;
import com.easysubway.transit.domain.StationLineSummary;
import com.easysubway.transit.domain.StationWithLines;
import com.easysubway.transit.domain.SubwayLine;
import com.easysubway.transit.domain.TransitOperator;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class TransitMasterController {

	private final TransitMasterQueryUseCase transitMasterQueryUseCase;
	private final TransitMasterAdminUseCase transitMasterAdminUseCase;

	TransitMasterController(
		TransitMasterQueryUseCase transitMasterQueryUseCase,
		TransitMasterAdminUseCase transitMasterAdminUseCase
	) {
		this.transitMasterQueryUseCase = transitMasterQueryUseCase;
		this.transitMasterAdminUseCase = transitMasterAdminUseCase;
	}

	@GetMapping("/api/v1/operators")
	ApiResponse<List<TransitOperatorResponse>> operators() {
		List<TransitOperatorResponse> response = transitMasterQueryUseCase.listOperators()
			.stream()
			.map(TransitOperatorResponse::from)
			.toList();

		return ApiResponse.ok(response);
	}

	@GetMapping("/api/v1/lines")
	ApiResponse<List<SubwayLineResponse>> lines(
		@RequestParam(required = false) String operatorId
	) {
		List<SubwayLineResponse> response = transitMasterQueryUseCase.listLines(operatorId)
			.stream()
			.map(SubwayLineResponse::from)
			.toList();

		return ApiResponse.ok(response);
	}

	@GetMapping("/api/v1/stations")
	ApiResponse<List<StationSummaryResponse>> stations(
		@RequestParam(required = false) String query,
		@RequestParam(required = false) String lineId
	) {
		List<StationSummaryResponse> response = transitMasterQueryUseCase
			.searchStations(new StationSearchCommand(query, lineId))
			.stream()
			.map(StationSummaryResponse::from)
			.toList();

		return ApiResponse.ok(response);
	}

	@GetMapping("/api/v1/stations/{stationId}")
	ApiResponse<StationDetailResponse> station(@PathVariable String stationId) {
		return ApiResponse.ok(StationDetailResponse.from(transitMasterQueryUseCase.getStation(stationId)));
	}

	@GetMapping("/api/v1/stations/{stationId}/exits")
	ApiResponse<List<StationExitResponse>> stationExits(@PathVariable String stationId) {
		List<StationExitResponse> response = transitMasterQueryUseCase.listStationExits(stationId)
			.stream()
			.map(StationExitResponse::from)
			.toList();

		return ApiResponse.ok(response);
	}

	@GetMapping("/api/v1/stations/{stationId}/facilities")
	ApiResponse<List<AccessibilityFacilityResponse>> stationFacilities(@PathVariable String stationId) {
		List<AccessibilityFacilityResponse> response = transitMasterQueryUseCase.listStationFacilities(stationId)
			.stream()
			.map(AccessibilityFacilityResponse::from)
			.toList();

		return ApiResponse.ok(response);
	}

	@PatchMapping("/admin/facilities/{facilityId}/status")
	ApiResponse<AccessibilityFacilityResponse> updateFacilityStatus(
		@PathVariable String facilityId,
		@RequestBody UpdateAccessibilityFacilityStatusRequest request,
		Principal principal
	) {
		AccessibilityFacility facility = transitMasterAdminUseCase.updateFacilityStatus(
			request.toCommand(facilityId, principal.getName())
		);
		return ApiResponse.ok(AccessibilityFacilityResponse.from(facility));
	}

	record TransitOperatorResponse(
		String id,
		String name,
		String region,
		String websiteUrl,
		String contactUrl,
		DataSourceType dataSourceType,
		boolean active
	) {

		static TransitOperatorResponse from(TransitOperator operator) {
			return new TransitOperatorResponse(
				operator.id(),
				operator.name(),
				operator.region(),
				operator.websiteUrl(),
				operator.contactUrl(),
				operator.dataSourceType(),
				operator.active()
			);
		}
	}

	record SubwayLineResponse(
		String id,
		String operatorId,
		String name,
		String color,
		String region,
		String lineCode,
		boolean active
	) {

		static SubwayLineResponse from(SubwayLine line) {
			return new SubwayLineResponse(
				line.id(),
				line.operatorId(),
				line.name(),
				line.color(),
				line.region(),
				line.lineCode(),
				line.active()
			);
		}
	}

	record StationSummaryResponse(
		String id,
		String nameKo,
		String nameEn,
		String region,
		DataQualityLevel dataQualityLevel,
		LocalDate lastVerifiedAt,
		List<StationLineResponse> lines
	) {

		static StationSummaryResponse from(StationWithLines stationWithLines) {
			Station station = stationWithLines.station();
			return new StationSummaryResponse(
				station.id(),
				station.nameKo(),
				station.nameEn(),
				station.region(),
				station.dataQualityLevel(),
				station.lastVerifiedAt(),
				stationWithLines.lines()
					.stream()
					.map(StationLineResponse::from)
					.toList()
			);
		}
	}

	record StationDetailResponse(
		String id,
		String nameKo,
		String nameEn,
		String region,
		BigDecimal latitude,
		BigDecimal longitude,
		DataQualityLevel dataQualityLevel,
		LocalDate lastVerifiedAt,
		List<StationLineResponse> lines
	) {

		static StationDetailResponse from(StationWithLines stationWithLines) {
			Station station = stationWithLines.station();
			return new StationDetailResponse(
				station.id(),
				station.nameKo(),
				station.nameEn(),
				station.region(),
				station.latitude(),
				station.longitude(),
				station.dataQualityLevel(),
				station.lastVerifiedAt(),
				stationWithLines.lines()
					.stream()
					.map(StationLineResponse::from)
					.toList()
			);
		}
	}

	record StationLineResponse(
		String id,
		String operatorId,
		String name,
		String color,
		String stationCode,
		int sequence,
		String platformInfo
	) {

		static StationLineResponse from(StationLineSummary line) {
			return new StationLineResponse(
				line.id(),
				line.operatorId(),
				line.name(),
				line.color(),
				line.stationCode(),
				line.sequence(),
				line.platformInfo()
			);
		}
	}

	record StationExitResponse(
		String id,
		String stationId,
		String exitNumber,
		String name,
		BigDecimal latitude,
		BigDecimal longitude,
		boolean hasElevatorConnection,
		boolean hasStairOnlyPath,
		DataConfidenceLevel dataConfidence
	) {

		static StationExitResponse from(StationExit exit) {
			return new StationExitResponse(
				exit.id(),
				exit.stationId(),
				exit.exitNumber(),
				exit.name(),
				exit.latitude(),
				exit.longitude(),
				exit.hasElevatorConnection(),
				exit.hasStairOnlyPath(),
				exit.dataConfidence()
			);
		}
	}

	record AccessibilityFacilityResponse(
		String id,
		String stationId,
		String exitId,
		AccessibilityFacilityType type,
		String name,
		String floorFrom,
		String floorTo,
		BigDecimal latitude,
		BigDecimal longitude,
		String description,
		AccessibilityFacilityStatus status,
		DataConfidenceLevel dataConfidence,
		LocalDate lastUpdatedAt
	) {

		static AccessibilityFacilityResponse from(AccessibilityFacility facility) {
			return new AccessibilityFacilityResponse(
				facility.id(),
				facility.stationId(),
				facility.exitId(),
				facility.type(),
				facility.name(),
				facility.floorFrom(),
				facility.floorTo(),
				facility.latitude(),
				facility.longitude(),
				facility.description(),
				facility.status(),
				facility.dataConfidence(),
				facility.lastUpdatedAt()
			);
		}
	}

	record UpdateAccessibilityFacilityStatusRequest(
		AccessibilityFacilityStatus status
	) {

		UpdateAccessibilityFacilityStatusCommand toCommand(String facilityId, String updatedBy) {
			return new UpdateAccessibilityFacilityStatusCommand(facilityId, status, updatedBy);
		}
	}
}

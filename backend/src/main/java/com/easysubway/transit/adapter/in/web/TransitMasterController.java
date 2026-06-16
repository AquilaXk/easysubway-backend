package com.easysubway.transit.adapter.in.web;

import com.easysubway.common.web.ApiResponse;
import com.easysubway.transit.application.port.in.NearbyStationSearchCommand;
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
import com.easysubway.transit.domain.NearbyStation;
import com.easysubway.transit.domain.RouteNode;
import com.easysubway.transit.domain.RouteNodeType;
import com.easysubway.transit.domain.Station;
import com.easysubway.transit.domain.StationExit;
import com.easysubway.transit.domain.StationLayoutSource;
import com.easysubway.transit.domain.StationLayoutSourceType;
import com.easysubway.transit.domain.StationLineSummary;
import com.easysubway.transit.domain.StationWithLines;
import com.easysubway.transit.domain.SimplifiedStationLayout;
import com.easysubway.transit.domain.SimplifiedStationLayoutConfidence;
import com.easysubway.transit.domain.SimplifiedStationLayoutStatus;
import com.easysubway.transit.domain.SubwayLine;
import com.easysubway.transit.domain.TransitOperator;
import com.easysubway.transit.domain.TransitRegionSummary;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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

	@GetMapping("/api/v1/regions")
	ApiResponse<List<TransitRegionResponse>> regions() {
		List<TransitRegionResponse> response = transitMasterQueryUseCase.listRegions()
			.stream()
			.map(TransitRegionResponse::from)
			.toList();

		return ApiResponse.ok(response);
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

	@GetMapping("/api/v1/stations/nearby")
	ApiResponse<List<NearbyStationResponse>> nearbyStations(
		@RequestParam(required = false) BigDecimal lat,
		@RequestParam(required = false) BigDecimal lng,
		@RequestParam(required = false) Integer radiusMeters,
		@RequestParam(required = false) Integer limit
	) {
		List<NearbyStationResponse> response = transitMasterQueryUseCase
			.searchNearbyStations(NearbyStationSearchCommand.of(lat, lng, radiusMeters, limit))
			.stream()
			.map(NearbyStationResponse::from)
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

	@GetMapping("/admin/stations/{stationId}/layout-sources")
	ApiResponse<List<StationLayoutSourceResponse>> stationLayoutSources(@PathVariable String stationId) {
		List<StationLayoutSourceResponse> response = transitMasterQueryUseCase.listStationLayoutSources(stationId)
			.stream()
			.map(StationLayoutSourceResponse::from)
			.toList();

		return ApiResponse.ok(response);
	}

	@GetMapping("/admin/stations/{stationId}/layouts")
	ApiResponse<List<SimplifiedStationLayoutResponse>> simplifiedStationLayouts(@PathVariable String stationId) {
		List<SimplifiedStationLayoutResponse> response = transitMasterQueryUseCase
			.listSimplifiedStationLayouts(stationId)
			.stream()
			.map(SimplifiedStationLayoutResponse::from)
			.toList();

		return ApiResponse.ok(response);
	}

	@GetMapping("/admin/stations/{stationId}/route-nodes")
	ApiResponse<List<RouteNodeResponse>> routeNodes(@PathVariable String stationId) {
		List<RouteNodeResponse> response = transitMasterQueryUseCase.listRouteNodes(stationId)
			.stream()
			.map(RouteNodeResponse::from)
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

	record TransitRegionResponse(
		String name,
		int operatorCount,
		int lineCount,
		int stationCount,
		Map<DataQualityLevel, Long> dataQualityCounts
	) {

		static TransitRegionResponse from(TransitRegionSummary region) {
			return new TransitRegionResponse(
				region.name(),
				region.operatorCount(),
				region.lineCount(),
				region.stationCount(),
				region.dataQualityCounts()
			);
		}
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
		DataSourceType dataSourceType,
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
				station.dataSourceType(),
				station.lastVerifiedAt(),
				stationWithLines.lines()
					.stream()
					.map(StationLineResponse::from)
					.toList()
			);
		}
	}

	record NearbyStationResponse(
		String id,
		String nameKo,
		String nameEn,
		String region,
		DataQualityLevel dataQualityLevel,
		DataSourceType dataSourceType,
		LocalDate lastVerifiedAt,
		int distanceMeters,
		List<StationLineResponse> lines
	) {

		static NearbyStationResponse from(NearbyStation nearbyStation) {
			StationWithLines stationWithLines = nearbyStation.stationWithLines();
			Station station = stationWithLines.station();
			return new NearbyStationResponse(
				station.id(),
				station.nameKo(),
				station.nameEn(),
				station.region(),
				station.dataQualityLevel(),
				station.dataSourceType(),
				station.lastVerifiedAt(),
				nearbyStation.distanceMeters(),
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
		DataSourceType dataSourceType,
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
				station.dataSourceType(),
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
		DataConfidenceLevel dataConfidence,
		DataSourceType dataSourceType
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
				exit.dataConfidence(),
				exit.dataSourceType()
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
		DataSourceType dataSourceType,
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
				facility.dataSourceType(),
				facility.lastUpdatedAt()
			);
		}
	}

	record StationLayoutSourceResponse(
		String id,
		String stationId,
		StationLayoutSourceType sourceType,
		String sourceName,
		String sourceUrl,
		String license,
		boolean commercialUseAllowed,
		boolean attributionRequired,
		LocalDate capturedAt,
		LocalDate reviewedAt
	) {

		static StationLayoutSourceResponse from(StationLayoutSource source) {
			return new StationLayoutSourceResponse(
				source.id(),
				source.stationId(),
				source.sourceType(),
				source.sourceName(),
				source.sourceUrl(),
				source.license(),
				source.commercialUseAllowed(),
				source.attributionRequired(),
				source.capturedAt(),
				source.reviewedAt()
			);
		}
	}

	record SimplifiedStationLayoutResponse(
		String id,
		String stationId,
		int version,
		SimplifiedStationLayoutStatus status,
		List<String> sourceIds,
		SimplifiedStationLayoutConfidence confidenceLevel,
		String baseFloor,
		String layoutJson,
		String renderedPreviewUrl,
		String createdBy,
		String reviewedBy,
		LocalDate publishedAt,
		LocalDate lastVerifiedAt
	) {

		static SimplifiedStationLayoutResponse from(SimplifiedStationLayout layout) {
			return new SimplifiedStationLayoutResponse(
				layout.id(),
				layout.stationId(),
				layout.version(),
				layout.status(),
				layout.sourceIds(),
				layout.confidenceLevel(),
				layout.baseFloor(),
				layout.layoutJson(),
				layout.renderedPreviewUrl(),
				layout.createdBy(),
				layout.reviewedBy(),
				layout.publishedAt(),
				layout.lastVerifiedAt()
			);
		}
	}

	record RouteNodeResponse(
		String id,
		String stationId,
		RouteNodeType type,
		String name,
		String floor,
		BigDecimal latitude,
		BigDecimal longitude,
		String facilityId,
		String layoutId,
		int displayX,
		int displayY,
		String displayLabel,
		String accessibilityNote
	) {

		static RouteNodeResponse from(RouteNode node) {
			return new RouteNodeResponse(
				node.id(),
				node.stationId(),
				node.type(),
				node.name(),
				node.floor(),
				node.latitude(),
				node.longitude(),
				node.facilityId(),
				node.layoutId(),
				node.displayX(),
				node.displayY(),
				node.displayLabel(),
				node.accessibilityNote()
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

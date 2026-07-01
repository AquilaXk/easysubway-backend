package com.easysubway.route.adapter.in.web;

import com.easysubway.common.error.InvalidRequestException;
import com.easysubway.common.web.ApiResponse;
import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.application.port.in.RouteSearchUseCase;
import com.easysubway.route.application.port.in.SearchRouteCommand;
import com.easysubway.route.domain.ConstraintMode;
import com.easysubway.route.domain.EtaSource;
import com.easysubway.route.domain.RouteSearchResult;
import com.easysubway.route.domain.RouteSearchStatus;
import com.easysubway.route.domain.RouteStep;
import com.easysubway.route.domain.RouteWarning;
import com.easysubway.route.domain.RouteWarningCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RouteSearchController {

	private final RouteSearchUseCase routeSearchUseCase;

	RouteSearchController(RouteSearchUseCase routeSearchUseCase) {
		this.routeSearchUseCase = routeSearchUseCase;
	}

	@PostMapping("/api/v1/routes/search")
	ApiResponse<RouteSearchV1Response> searchRoute(@Valid @RequestBody RouteSearchRequest request) {
		return ApiResponse.ok(RouteSearchV1Response.from(routeSearchUseCase.searchRoute(request.toCommand())));
	}

	@PostMapping("/api/v2/routes/search")
	ApiResponse<RouteSearchV2Response> searchRouteV2(@Valid @RequestBody RouteSearchV2Request request) {
		OffsetDateTime departureTime = request.parsedDepartureTime();
		SearchRouteCommand command = request.toCommand();
		return ApiResponse.ok(RouteSearchV2Response.from(routeSearchUseCase.searchRoute(command), request, departureTime));
	}

	private record RouteSearchRequest(
		@NotBlank(message = "출발역을 선택해야 합니다.")
		String originStationId,
		@NotBlank(message = "도착역을 선택해야 합니다.")
		String destinationStationId,
		@NotNull(message = "이동 유형을 선택해야 합니다.")
		MobilityType mobilityType,
		String constraintMode
	) {

		SearchRouteCommand toCommand() {
			ConstraintMode mode = constraintMode == null || constraintMode.isBlank()
				? ConstraintMode.defaultFor(mobilityType)
				: parseConstraintMode(mobilityType, constraintMode);
			return new SearchRouteCommand(originStationId, destinationStationId, mobilityType, mode);
		}
	}

	private record RouteSearchV1Response(
		String routeSearchId,
		String originStationId,
		String originStationName,
		String destinationStationId,
		String destinationStationName,
		MobilityType mobilityType,
		RouteSearchStatus status,
		String lineId,
		String lineName,
		int score,
		int burdenCost,
		int estimatedDurationSeconds,
		int walkingDistanceMeters,
		int transferCount,
		List<RouteStep> steps,
		List<RouteWarning> warnings,
		List<String> blockedReasons,
		List<String> recommendationReasons,
		List<String> evidenceSummary,
		LocalDateTime createdAt,
		String etaSource,
		String routeQuality,
		boolean commercialEtaEligible
	) {

		private static RouteSearchV1Response from(RouteSearchResult result) {
			return new RouteSearchV1Response(
				result.routeSearchId(),
				result.originStationId(),
				result.originStationName(),
				result.destinationStationId(),
				result.destinationStationName(),
				result.mobilityType(),
				result.status(),
				result.lineId(),
				result.lineName(),
				result.score(),
				result.burdenCost(),
				result.estimatedDurationSeconds(),
				result.walkingDistanceMeters(),
				result.transferCount(),
				result.steps(),
				result.warnings(),
				result.blockedReasons(),
				result.recommendationReasons(),
				result.evidenceSummary(),
				result.createdAt(),
				result.etaSource().name(),
				"LEGACY_STATIC",
				false
			);
		}
	}

	private record RouteSearchV2Request(
		@NotBlank(message = "출발역을 선택해야 합니다.")
		String originStationId,
		@NotBlank(message = "도착역을 선택해야 합니다.")
		String destinationStationId,
		@NotBlank(message = "출발 시간을 선택해야 합니다.")
		@Pattern(
			regexp = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(Z|[+-]\\d{2}:\\d{2})",
			message = "출발 시간은 ISO offset 형식이어야 합니다."
		)
		String departureTime,
		@NotNull(message = "이동 유형을 선택해야 합니다.")
		MobilityType mobilityType,
		@NotBlank(message = "이동 제약 조건을 선택해야 합니다.")
		String constraintMode,
		@NotNull(message = "실시간 반영 여부를 선택해야 합니다.")
		Boolean useRealtime,
		@NotNull(message = "최대 환승 수를 선택해야 합니다.")
		@Min(value = 0, message = "최대 환승 수는 0 이상이어야 합니다.")
		@Max(value = 3, message = "최대 환승 수는 3 이하여야 합니다.")
		Integer maxTransfers,
		@Min(value = 1, message = "대안 경로 수는 1 이상이어야 합니다.")
		int alternativeCount
	) {

		SearchRouteCommand toCommand() {
			return new SearchRouteCommand(
				originStationId,
				destinationStationId,
				mobilityType,
				parseConstraintMode(mobilityType, constraintMode),
				maxTransfers
			);
		}

		OffsetDateTime parsedDepartureTime() {
			try {
				return OffsetDateTime.parse(departureTime);
			} catch (DateTimeParseException exception) {
				throw new InvalidRequestException("출발 시간은 ISO offset 형식이어야 합니다.", exception);
			}
		}

	}

	private record RouteSearchV2Response(
		String contractVersion,
		String originStationId,
		String destinationStationId,
		String departureTime,
		MobilityType mobilityType,
		String constraintMode,
		boolean useRealtime,
		int maxTransfers,
		int alternativeCount,
		List<String> statuses,
		List<ItineraryDto> itineraries
	) {

		private static RouteSearchV2Response from(
			RouteSearchResult result,
			RouteSearchV2Request request,
			OffsetDateTime departureTime
		) {
			return new RouteSearchV2Response(
				"ROUTE_SEARCH_V2",
				request.originStationId(),
				request.destinationStationId(),
				request.departureTime(),
				request.mobilityType(),
				request.constraintMode(),
				Boolean.TRUE.equals(request.useRealtime()),
				request.maxTransfers(),
				request.alternativeCount(),
				List.of(
					"FOUND",
					"BLOCKED_ACCESSIBILITY",
					"NO_TIMETABLE_SERVICE",
					"REALTIME_UNAVAILABLE_PLANNED_USED",
					"UNSUPPORTED_REGION",
					"ROUTE_GRAPH_UNKNOWN"
				),
				List.of(ItineraryDto.from(result, departureTime))
			);
		}
	}

	private record ItineraryDto(
		String itineraryId,
		String status,
		String plannedArrivalTime,
		String realtimeArrivalTime,
		String etaSource,
		String etaConfidence,
		int durationSeconds,
		int transferCount,
		int walkingDistanceMeters,
		AccessibilityRiskDto accessibilityRisk,
		List<LegDto> legs,
		boolean commercialEtaEligible
	) {

		private static ItineraryDto from(RouteSearchResult result, OffsetDateTime departureTime) {
			List<LegDto> legs = LegDto.fromSteps(result.steps(), departureTime, result.mobilityType());
			OffsetDateTime plannedArrivalTime = legs.isEmpty()
				? departureTime
				: OffsetDateTime.parse(legs.get(legs.size() - 1).plannedArrivalTime());
			int durationSeconds = Math.toIntExact(Duration.between(departureTime, plannedArrivalTime).toSeconds());
			return new ItineraryDto(
				result.routeSearchId() + "-primary",
				statusOf(result),
				formatOffset(plannedArrivalTime),
				null,
				result.etaSource().name(),
				etaConfidenceOf(result),
				durationSeconds,
				result.transferCount(),
				result.walkingDistanceMeters(),
				AccessibilityRiskDto.from(result),
				legs,
				false
			);
		}

		private static String statusOf(RouteSearchResult result) {
			return result.status() == RouteSearchStatus.BLOCKED ? "BLOCKED_ACCESSIBILITY" : result.status().name();
		}

		private static String etaConfidenceOf(RouteSearchResult result) {
			if (result.status() != RouteSearchStatus.FOUND) {
				return "UNKNOWN";
			}
			return switch (result.etaSource()) {
				case REALTIME -> "HIGH";
				case MIXED -> "MEDIUM";
				case PLANNED, FALLBACK -> "LOW";
			};
		}
	}

	private record AccessibilityRiskDto(
		int stairCount,
		int unknownAccessibilityCount,
		int generatedConnectorCount,
		int staleDataCount,
		int lowConfidenceCount,
		int unavailableFacilityCount,
		String riskLevel,
		List<String> reasonCodes,
		String level,
		List<String> reasons
	) {

		private static AccessibilityRiskDto from(RouteSearchResult result) {
			List<String> reasonCodes = reasonCodesFrom(result);
			int stairCount = Math.toIntExact(result.steps().stream()
				.filter(RouteStep::includesStairs)
				.count());
			int unknownAccessibilityCount = Math.toIntExact(result.steps().stream()
				.filter(step -> "UNKNOWN".equals(step.stairAccessState()))
				.count());
			// Keep the V2 response shape stable until route warnings expose these signals.
			int generatedConnectorCount = 0;
			int staleDataCount = countWarning(result.warnings(), RouteWarningCode.STALE_ACCESSIBILITY_DATA);
			int lowConfidenceCount = countWarning(result.warnings(), RouteWarningCode.LOW_DATA_CONFIDENCE);
			int unavailableFacilityCount = 0;
			String riskLevel = riskLevel(
				result.status(),
				stairCount,
				unknownAccessibilityCount,
				generatedConnectorCount,
				staleDataCount,
				lowConfidenceCount,
				unavailableFacilityCount
			);
			return new AccessibilityRiskDto(
				stairCount,
				unknownAccessibilityCount,
				generatedConnectorCount,
				staleDataCount,
				lowConfidenceCount,
				unavailableFacilityCount,
				riskLevel,
				reasonCodes,
				legacyLevel(riskLevel),
				reasonCodes
			);
		}

		private static AccessibilityRiskDto from(RouteStep step) {
			List<String> reasonCodes = reasonCodesFrom(step);
			int stairCount = step.includesStairs() ? 1 : 0;
			int unknownAccessibilityCount = "UNKNOWN".equals(step.stairAccessState()) ? 1 : 0;
			int generatedConnectorCount = countReason(reasonCodes, "GENERATED_CONNECTOR_UNVERIFIED");
			int staleDataCount = countReason(reasonCodes, "STALE_ACCESSIBILITY_DATA");
			int lowConfidenceCount = countReason(reasonCodes, "LOW_DATA_CONFIDENCE");
			int unavailableFacilityCount = countReason(reasonCodes, "FACILITY_UNAVAILABLE");
			String riskLevel = riskLevel(
				RouteSearchStatus.FOUND,
				stairCount,
				unknownAccessibilityCount,
				generatedConnectorCount,
				staleDataCount,
				lowConfidenceCount,
				unavailableFacilityCount
			);
			return new AccessibilityRiskDto(
				stairCount,
				unknownAccessibilityCount,
				generatedConnectorCount,
				staleDataCount,
				lowConfidenceCount,
				unavailableFacilityCount,
				riskLevel,
				reasonCodes,
				legacyLevel(riskLevel),
				reasonCodes
			);
		}

		private static List<String> reasonCodesFrom(RouteSearchResult result) {
			List<String> reasonCodes = new ArrayList<>();
			if (result.status() == RouteSearchStatus.BLOCKED) {
				reasonCodes.add("BLOCKED_ACCESSIBILITY");
			}
			result.warnings().stream()
				.map(warning -> warning.code().name())
				.forEach(reasonCodes::add);
			if (result.evidenceSummary().contains("ACCESSIBILITY_CHECK_REQUIRED")) {
				reasonCodes.add("ACCESSIBILITY_CHECK_REQUIRED");
			}
			return List.copyOf(reasonCodes.stream().distinct().toList());
		}

		private static List<String> reasonCodesFrom(RouteStep step) {
			List<String> reasonCodes = new ArrayList<>();
			if (step.includesStairs()) {
				reasonCodes.add("STAIR_ONLY_ACCESS");
			}
			if (step.requiresAccessibilityCheck() || "UNKNOWN".equals(step.stairAccessState())) {
				reasonCodes.add("ACCESSIBILITY_CHECK_REQUIRED");
			}
			return List.copyOf(reasonCodes);
		}

		private static int countReason(List<String> reasonCodes, String reasonCode) {
			return Math.toIntExact(reasonCodes.stream()
				.filter(reasonCode::equals)
				.count());
		}

		private static int countWarning(List<RouteWarning> warnings, RouteWarningCode warningCode) {
			return Math.toIntExact(warnings.stream()
				.filter(warning -> warning.code() == warningCode)
				.count());
		}

		private static String riskLevel(
			RouteSearchStatus status,
			int stairCount,
			int unknownAccessibilityCount,
			int generatedConnectorCount,
			int staleDataCount,
			int lowConfidenceCount,
			int unavailableFacilityCount
		) {
			if (status == RouteSearchStatus.BLOCKED) {
				return "BLOCKED";
			}
			if (unavailableFacilityCount > 0 || stairCount > 0) {
				return "HIGH";
			}
			if (unknownAccessibilityCount > 0 || generatedConnectorCount > 0) {
				return "MEDIUM";
			}
			if (staleDataCount > 0 || lowConfidenceCount > 0) {
				return "LOW";
			}
			return "NONE";
		}

		private static String legacyLevel(String riskLevel) {
			return switch (riskLevel) {
				case "BLOCKED" -> "BLOCKED";
				case "HIGH", "MEDIUM" -> "REVIEW_REQUIRED";
				case "LOW" -> "LOW";
				default -> "LOW";
			};
		}
	}

	private record LegDto(
		String legType,
		String fromStationId,
		String toStationId,
		String fromNodeId,
		String toNodeId,
		String lineId,
		String tripId,
		String trainNo,
		String plannedDepartureTime,
		String realtimeDepartureTime,
		String plannedArrivalTime,
		String realtimeArrivalTime,
		int waitTimeSeconds,
		int slackSeconds,
		int durationSeconds,
		int distanceMeters,
		String etaSource,
		String confidence,
		AccessibilityRiskDto accessibilityRisk
	) {

		private static List<LegDto> fromSteps(
			List<RouteStep> steps,
			OffsetDateTime departureTime,
			MobilityType mobilityType
		) {
			List<LegDto> legs = new ArrayList<>();
			OffsetDateTime cursor = departureTime;
			for (RouteStep step : steps) {
				String legType = legTypeOf(step);
				int durationSeconds = Math.max(0, step.estimatedMinutes()) * 60;
				int slackSeconds = slackSeconds(legType, mobilityType);
				OffsetDateTime plannedDepartureTime = cursor.plusSeconds(slackSeconds);
				OffsetDateTime plannedArrivalTime = plannedDepartureTime.plusSeconds(durationSeconds);
				legs.add(from(step, legType, plannedDepartureTime, plannedArrivalTime, durationSeconds, slackSeconds));
				cursor = plannedArrivalTime;
			}
			return List.copyOf(legs);
		}

		private static LegDto from(
			RouteStep step,
			String legType,
			OffsetDateTime departureTime,
			OffsetDateTime plannedArrivalTime,
			int durationSeconds,
			int slackSeconds
		) {
			return new LegDto(
				legType,
				step.fromStationId(),
				step.toStationId(),
				"",
				"",
				step.lineId(),
				"",
				"",
				formatOffset(departureTime),
				null,
				formatOffset(plannedArrivalTime),
				null,
				slackSeconds,
				slackSeconds,
				durationSeconds,
				Math.max(0, step.distanceMeters()),
				etaSourceOf(step).name(),
				etaConfidenceOf(step),
				AccessibilityRiskDto.from(step)
			);
		}

		private static int slackSeconds(String legType, MobilityType mobilityType) {
			if (!"RIDE".equals(legType)) {
				return 0;
			}
			// ponytail: schedule candidate selection belongs with timetable schema; expose only mobility buffer for now.
			return switch (mobilityType) {
				case LUGGAGE -> 60;
				case SENIOR, PREGNANT -> 90;
				case STROLLER, TEMPORARY_INJURY -> 120;
				case WHEELCHAIR -> 180;
			};
		}

		private static EtaSource etaSourceOf(RouteStep step) {
			if (step.timeSource() == null || step.timeSource().isBlank()) {
				return EtaSource.PLANNED;
			}
			try {
				return EtaSource.valueOf(step.timeSource());
			} catch (IllegalArgumentException exception) {
				return EtaSource.PLANNED;
			}
		}

		private static String etaConfidenceOf(RouteStep step) {
			if ("HIGH".equals(step.confidenceLabel())
				|| "MEDIUM".equals(step.confidenceLabel())
				|| "LOW".equals(step.confidenceLabel())) {
				return step.confidenceLabel();
			}
			return switch (etaSourceOf(step)) {
				case REALTIME -> "HIGH";
				case MIXED -> "MEDIUM";
				case PLANNED, FALLBACK -> "LOW";
			};
		}

		private static String legTypeOf(RouteStep step) {
			return switch (step.stepType()) {
				case "exit" -> "EGRESS";
				case "ride" -> "RIDE";
				case "transfer", "internal" -> "TRANSFER";
				default -> "ACCESS";
			};
		}
	}

	private static String formatOffset(OffsetDateTime dateTime) {
		return dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"));
	}

	private static ConstraintMode parseConstraintMode(String value) {
		try {
			return ConstraintMode.valueOf(value);
		} catch (IllegalArgumentException exception) {
			throw new InvalidRequestException("지원하지 않는 이동 제약 조건입니다.", exception);
		}
	}

	private static ConstraintMode parseConstraintMode(MobilityType mobilityType, String value) {
		if ("PROFILE_DEFAULT".equals(value)) {
			return ConstraintMode.defaultFor(mobilityType);
		}
		return parseConstraintMode(value);
	}
}

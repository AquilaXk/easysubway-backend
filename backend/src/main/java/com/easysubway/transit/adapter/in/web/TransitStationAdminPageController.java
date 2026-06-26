package com.easysubway.transit.adapter.in.web;

import com.easysubway.transit.application.port.in.CreateAccessibilityFacilityCommand;
import com.easysubway.transit.application.port.in.StationMasterDataCounts;
import com.easysubway.transit.application.port.in.StationSearchCommand;
import com.easysubway.transit.application.port.in.TransitMasterAdminUseCase;
import com.easysubway.transit.application.port.in.TransitMasterQueryUseCase;
import com.easysubway.transit.application.port.in.UpdateAccessibilityFacilityCommand;
import com.easysubway.transit.domain.AccessibilityFacility;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import com.easysubway.transit.domain.AccessibilityFacilityType;
import com.easysubway.transit.domain.DataConfidenceLevel;
import com.easysubway.transit.domain.DataQualityLevel;
import com.easysubway.transit.domain.DataSourceType;
import com.easysubway.transit.domain.MasterDataWriteNotAllowedException;
import com.easysubway.transit.domain.RouteEdge;
import com.easysubway.transit.domain.RouteNode;
import com.easysubway.transit.domain.StationExit;
import com.easysubway.transit.domain.StationLayoutSource;
import com.easysubway.transit.domain.StationWithLines;
import java.math.BigDecimal;
import java.security.Principal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
class TransitStationAdminPageController {

	private final TransitMasterQueryUseCase transitMasterQueryUseCase;
	private final TransitMasterAdminUseCase transitMasterAdminUseCase;

	TransitStationAdminPageController(
		TransitMasterQueryUseCase transitMasterQueryUseCase,
		TransitMasterAdminUseCase transitMasterAdminUseCase
	) {
		this.transitMasterQueryUseCase = transitMasterQueryUseCase;
		this.transitMasterAdminUseCase = transitMasterAdminUseCase;
	}

	@GetMapping("/admin/stations/page")
	String stationsPage(
		@RequestParam(required = false) String query,
		Model model
	) {
		Map<String, StationMasterDataCounts> counts = transitMasterQueryUseCase.countStationMasterDataByStationId();
		List<StationRow> stations = transitMasterQueryUseCase.searchStations(new StationSearchCommand(query, null))
			.stream()
			.map(station -> StationRow.from(station, counts.getOrDefault(
				station.station().id(),
				StationMasterDataCounts.empty()
			)))
			.toList();
		model.addAttribute("stations", stations);
		model.addAttribute("query", query);
		return "admin/stations/list";
	}

	@GetMapping("/admin/stations/{stationId}/page")
	String stationDetailPage(@PathVariable String stationId, Model model) {
		StationWithLines station = transitMasterQueryUseCase.getStation(stationId);
		model.addAttribute("station", StationDetail.from(station));
		model.addAttribute("exits", transitMasterQueryUseCase.listStationExits(stationId).stream()
			.map(ExitRow::from)
			.toList());
		model.addAttribute("facilities", transitMasterQueryUseCase.listStationFacilities(stationId).stream()
			.map(FacilityRow::from)
			.toList());
		model.addAttribute("layoutSourceCount", transitMasterQueryUseCase.listStationLayoutSources(stationId).size());
		model.addAttribute("layoutCount", transitMasterQueryUseCase.listSimplifiedStationLayouts(stationId).size());
		model.addAttribute("routeNodeCount", transitMasterQueryUseCase.listRouteNodes(stationId).size());
		model.addAttribute("routeEdgeCount", transitMasterQueryUseCase.listRouteEdges(stationId).size());
		return "admin/stations/detail";
	}

	@GetMapping("/admin/facilities/editor/page")
	String facilityEditorPage(
		@RequestParam(required = false) String stationId,
		@RequestParam(required = false) String facilityId,
		Model model
	) {
		List<StationWithLines> stations = transitMasterQueryUseCase.searchStations(new StationSearchCommand(null, null));
		String selectedStationId = stationId == null && !stations.isEmpty() ? stations.getFirst().station().id() : stationId;
		List<FacilityRow> facilities = selectedStationId == null ? List.of() : transitMasterQueryUseCase
			.listStationFacilities(selectedStationId)
			.stream()
			.map(FacilityRow::from)
			.toList();
		FacilityRow selectedFacility = "__new".equals(facilityId) ? null : facilities.stream()
			.filter(facility -> facility.facilityId().equals(facilityId))
			.findFirst()
			.orElse(facilities.isEmpty() ? null : facilities.getFirst());
		model.addAttribute("stations", stations.stream().map(StationOption::from).toList());
		model.addAttribute("selectedStationId", selectedStationId);
		model.addAttribute("facilities", facilities);
		model.addAttribute("selectedFacility", selectedFacility);
		model.addAttribute("typeOptions", Arrays.asList(AccessibilityFacilityType.values()));
		model.addAttribute("statusOptions", Arrays.asList(AccessibilityFacilityStatus.values()));
		model.addAttribute("confidenceOptions", Arrays.asList(DataConfidenceLevel.values()));
		model.addAttribute("sourceTypeOptions", Arrays.asList(DataSourceType.values()));
		model.addAttribute("masterDataWritable", transitMasterAdminUseCase.masterDataCapability().writable());
		return "admin/facilities/editor";
	}

	@PostMapping("/admin/facilities/editor/page")
	@PreAuthorize("hasAuthority('admin.master.edit')")
	String saveFacilityFromPage(
		@RequestParam(required = false) String facilityId,
		@RequestParam String stationId,
		@RequestParam(required = false) String exitId,
		@RequestParam AccessibilityFacilityType type,
		@RequestParam String name,
		@RequestParam(required = false) String floorFrom,
		@RequestParam(required = false) String floorTo,
		@RequestParam(required = false) BigDecimal latitude,
		@RequestParam(required = false) BigDecimal longitude,
		@RequestParam(required = false) String description,
		@RequestParam AccessibilityFacilityStatus status,
		@RequestParam DataConfidenceLevel dataConfidence,
		@RequestParam DataSourceType dataSourceType,
		Principal principal,
		RedirectAttributes redirectAttributes
	) {
		try {
			if (facilityId == null || facilityId.isBlank()) {
				String newFacilityId = "facility-" + stationId + "-" + type.name().toLowerCase() + "-" + System.currentTimeMillis();
				transitMasterAdminUseCase.createAccessibilityFacility(new CreateAccessibilityFacilityCommand(
					newFacilityId,
					stationId,
					blankToNull(exitId),
					type,
					name,
					blankToNull(floorFrom),
					blankToNull(floorTo),
					latitude,
					longitude,
					blankToNull(description),
					status,
					dataConfidence,
					dataSourceType,
					principal.getName()
				));
				return "redirect:/admin/facilities/editor/page?stationId=%s&facilityId=%s".formatted(stationId, newFacilityId);
			}
			transitMasterAdminUseCase.updateAccessibilityFacility(new UpdateAccessibilityFacilityCommand(
				facilityId,
				stationId,
				blankToNull(exitId),
				type,
				name,
				blankToNull(floorFrom),
				blankToNull(floorTo),
				latitude,
				longitude,
				blankToNull(description),
				status,
				dataConfidence,
				dataSourceType,
				principal.getName()
			));
		} catch (MasterDataWriteNotAllowedException exception) {
			redirectAttributes.addFlashAttribute("masterDataError", exception.getMessage());
		}
		return "redirect:/admin/facilities/editor/page?stationId=%s&facilityId=%s".formatted(stationId, facilityId);
	}

	private static String qualityLabel(DataQualityLevel level) {
		return switch (level) {
			case LEVEL_1 -> "Level 1";
			case LEVEL_2 -> "Level 2";
			case LEVEL_3 -> "Level 3";
			case LEVEL_4 -> "Level 4";
		};
	}

	private static String confidenceLabel(DataConfidenceLevel level) {
		return switch (level) {
			case HIGH -> "높음";
			case MEDIUM -> "보통";
			case LOW -> "낮음";
			case NEEDS_VERIFICATION -> "확인 필요";
		};
	}

	private static String statusLabel(AccessibilityFacilityStatus status) {
		return FacilityStatusRow.statusLabel(status);
	}

	record StationRow(
		String stationId,
		String stationName,
		String lineNames,
		String region,
		String qualityLabel,
		String lastVerifiedAt,
		int exitCount,
		int facilityCount,
		int layoutSourceCount,
		int routeNodeCount,
		int routeEdgeCount
	) {

		static StationRow from(StationWithLines stationWithLines, StationMasterDataCounts counts) {
			return new StationRow(
				stationWithLines.station().id(),
				stationWithLines.station().nameKo(),
				stationWithLines.lines().stream().map(line -> line.name()).reduce((a, b) -> a + ", " + b).orElse("-"),
				stationWithLines.station().region(),
				TransitStationAdminPageController.qualityLabel(stationWithLines.station().dataQualityLevel()),
				String.valueOf(stationWithLines.station().lastVerifiedAt()),
				counts.exitCount(),
				counts.facilityCount(),
				counts.layoutSourceCount(),
				counts.routeNodeCount(),
				counts.routeEdgeCount()
			);
		}
	}

	record StationDetail(
		String stationId,
		String stationName,
		String lineNames,
		String region,
		String latitude,
		String longitude,
		String qualityLabel,
		String sourceType,
		String lastVerifiedAt
	) {

		static StationDetail from(StationWithLines stationWithLines) {
			return new StationDetail(
				stationWithLines.station().id(),
				stationWithLines.station().nameKo(),
				stationWithLines.lines().stream().map(line -> line.name()).reduce((a, b) -> a + ", " + b).orElse("-"),
				stationWithLines.station().region(),
				String.valueOf(stationWithLines.station().latitude()),
				String.valueOf(stationWithLines.station().longitude()),
				TransitStationAdminPageController.qualityLabel(stationWithLines.station().dataQualityLevel()),
				stationWithLines.station().dataSourceType().name(),
				String.valueOf(stationWithLines.station().lastVerifiedAt())
			);
		}
	}

	record StationOption(String stationId, String stationName) {

		static StationOption from(StationWithLines stationWithLines) {
			return new StationOption(stationWithLines.station().id(), stationWithLines.station().nameKo());
		}
	}

	record ExitRow(String exitNumber, String name, String elevatorLabel, String stairOnlyLabel, String confidenceLabel) {

		static ExitRow from(StationExit exit) {
			return new ExitRow(
				exit.exitNumber(),
				exit.name(),
				exit.hasElevatorConnection() ? "엘리베이터 연결" : "엘리베이터 미확인",
				exit.hasStairOnlyPath() ? "계단 전용 경로 있음" : "계단 전용 아님",
				TransitStationAdminPageController.confidenceLabel(exit.dataConfidence())
			);
		}
	}

	record FacilityRow(
		String facilityId,
		String facilityName,
		AccessibilityFacilityType type,
		String typeLabel,
		String exitId,
		String floorFrom,
		String floorTo,
		String floorLabel,
		AccessibilityFacilityStatus status,
		String statusLabel,
		DataConfidenceLevel dataConfidence,
		String confidenceLabel,
		DataSourceType dataSourceType,
		String latitude,
		String longitude,
		String lastUpdatedAt,
		String description
	) {

		static FacilityRow from(AccessibilityFacility facility) {
			return new FacilityRow(
				facility.id(),
				facility.name(),
				facility.type(),
				facility.type().name(),
				facility.exitId(),
				facility.floorFrom(),
				facility.floorTo(),
				facility.floorFrom() + " → " + facility.floorTo(),
				facility.status(),
				TransitStationAdminPageController.statusLabel(facility.status()),
				facility.dataConfidence(),
				TransitStationAdminPageController.confidenceLabel(facility.dataConfidence()),
				facility.dataSourceType(),
				TransitStationAdminPageController.optionalText(facility.latitude()),
				TransitStationAdminPageController.optionalText(facility.longitude()),
				String.valueOf(facility.lastUpdatedAt()),
				facility.description()
			);
		}
	}

	private static String optionalText(Object value) {
		return value == null ? "" : String.valueOf(value);
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	record LayoutSourceRow(String sourceName, String sourceType, String license) {

		static LayoutSourceRow from(StationLayoutSource source) {
			return new LayoutSourceRow(source.sourceName(), source.sourceType().name(), source.license());
		}
	}

	record RouteNodeRow(String nodeName, String nodeType, String floor) {

		static RouteNodeRow from(RouteNode node) {
			return new RouteNodeRow(node.name(), node.type().name(), node.floor());
		}
	}

	record RouteEdgeRow(String edgeName, String edgeType, int confidenceScore) {

		static RouteEdgeRow from(RouteEdge edge) {
			return new RouteEdgeRow(
				edge.fromNodeId() + " → " + edge.toNodeId(),
				edge.type().name(),
				edge.reliabilityScore()
			);
		}
	}
}

package com.easysubway.transit.adapter.in.web;

import com.easysubway.admin.web.AdminFormErrorView;
import com.easysubway.common.web.pagination.AdminPageRequest;
import com.easysubway.common.web.pagination.EgovPaginationView;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
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
		@RequestParam(required = false) Integer page,
		@RequestParam(required = false) Integer size,
		Model model
	) {
		AdminPageRequest pageRequest = AdminPageRequest.of(page, size);
		Map<String, StationMasterDataCounts> counts = transitMasterQueryUseCase.countStationMasterDataByStationId();
		List<StationRow> stations = transitMasterQueryUseCase.searchStations(new StationSearchCommand(query, null))
			.stream()
			.map(station -> StationRow.from(station, counts.getOrDefault(
				station.station().id(),
				StationMasterDataCounts.empty()
			)))
			.toList();
		EgovPaginationView pageView = EgovPaginationView.from(pageRequest.page(), pageRequest.size(), stations.size());
		model.addAttribute("stations", pageView.pageItems(stations));
		model.addAttribute("page", pageView);
		model.addAttribute("paginationLinks", pageView.links(
			"/admin/stations/page",
			Collections.singletonMap("query", query)
		));
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
		populateFacilityEditorModel(model, stationId, facilityId, null);
		return "admin/facilities/editor";
	}

	private void populateFacilityEditorModel(
		Model model,
		String stationId,
		String facilityId,
		FacilityEditorForm submittedForm
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
		model.addAttribute("facilityForm", submittedForm == null
			? FacilityEditorForm.from(selectedStationId, selectedFacility)
			: submittedForm);
	}

	@PostMapping("/admin/facilities/editor/page")
	@PreAuthorize("hasAuthority('admin.master.edit')")
	String saveFacilityFromPage(
		@Valid @ModelAttribute("facilityForm") FacilityEditorForm form,
		BindingResult bindingResult,
		Principal principal,
		RedirectAttributes redirectAttributes,
		Model model,
		HttpServletResponse response
	) {
		if (bindingResult.hasErrors()) {
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			populateFacilityEditorModel(model, form.stationId(), form.facilityId(), form);
			model.addAttribute("facilityLatitudeValue", bindingResult.getFieldValue("latitude"));
			model.addAttribute("facilityLongitudeValue", bindingResult.getFieldValue("longitude"));
			AdminFormErrorView.expose(model, bindingResult);
			return "admin/facilities/editor";
		}
		try {
			if (form.facilityId() == null || form.facilityId().isBlank()) {
				String newFacilityId = "facility-" + form.stationId() + "-" + form.type().name().toLowerCase() + "-" + System.currentTimeMillis();
				transitMasterAdminUseCase.createAccessibilityFacility(new CreateAccessibilityFacilityCommand(
					newFacilityId,
					form.stationId(),
					blankToNull(form.exitId()),
					form.type(),
					form.name(),
					blankToNull(form.floorFrom()),
					blankToNull(form.floorTo()),
					form.latitude(),
					form.longitude(),
					blankToNull(form.description()),
					form.status(),
					form.dataConfidence(),
					form.dataSourceType(),
					principal.getName()
				));
				return "redirect:/admin/facilities/editor/page?stationId=%s&facilityId=%s".formatted(form.stationId(), newFacilityId);
			}
			transitMasterAdminUseCase.updateAccessibilityFacility(new UpdateAccessibilityFacilityCommand(
				form.facilityId(),
				form.stationId(),
				blankToNull(form.exitId()),
				form.type(),
				form.name(),
				blankToNull(form.floorFrom()),
				blankToNull(form.floorTo()),
				form.latitude(),
				form.longitude(),
				blankToNull(form.description()),
				form.status(),
				form.dataConfidence(),
				form.dataSourceType(),
				principal.getName()
			));
		} catch (MasterDataWriteNotAllowedException exception) {
			redirectAttributes.addFlashAttribute("masterDataError", exception.getMessage());
		}
		return "redirect:/admin/facilities/editor/page?stationId=%s&facilityId=%s".formatted(form.stationId(), form.facilityId());
	}

	private static String qualityLabel(DataQualityLevel level) {
		return level.label();
	}

	private static String confidenceLabel(DataConfidenceLevel level) {
		return switch (level) {
			case HIGH -> "확인된 정보";
			case MEDIUM -> "일부 확인된 정보";
			case LOW -> "확인이 더 필요한 정보";
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

	record FacilityEditorForm(
		String facilityId,
		@NotBlank(message = "{validation.transit.station-id.required}")
		String stationId,
		String exitId,
		@NotNull(message = "{validation.transit.facility-type.required}")
		AccessibilityFacilityType type,
		@NotBlank(message = "{validation.transit.facility-name.required}")
		String name,
		String floorFrom,
		String floorTo,
		BigDecimal latitude,
		BigDecimal longitude,
		String description,
		@NotNull(message = "{validation.transit.facility-status.required}")
		AccessibilityFacilityStatus status,
		@NotNull(message = "{validation.transit.facility-confidence.required}")
		DataConfidenceLevel dataConfidence,
		@NotNull(message = "{validation.transit.facility-source-type.required}")
		DataSourceType dataSourceType
	) {

		static FacilityEditorForm from(String selectedStationId, FacilityRow facility) {
			if (facility == null) {
				return new FacilityEditorForm(
					"",
					selectedStationId,
					"",
					null,
					"",
					"",
					"",
					null,
					null,
					"",
					null,
					null,
					null
				);
			}
			return new FacilityEditorForm(
				facility.facilityId(),
				selectedStationId,
				facility.exitId(),
				facility.type(),
				facility.facilityName(),
				facility.floorFrom(),
				facility.floorTo(),
				blankToBigDecimal(facility.latitude()),
				blankToBigDecimal(facility.longitude()),
				facility.description(),
				facility.status(),
				facility.dataConfidence(),
				facility.dataSourceType()
			);
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

	private static BigDecimal blankToBigDecimal(String value) {
		return value == null || value.isBlank() ? null : new BigDecimal(value);
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

package com.easysubway.transit.adapter.in.web;

import com.easysubway.admin.web.AdminFormErrorView;
import com.easysubway.transit.application.port.in.TransitMasterAdminUseCase;
import com.easysubway.transit.application.port.in.TransitMasterQueryUseCase;
import com.easysubway.transit.application.port.in.UpdateRouteEdgeCommand;
import com.easysubway.transit.application.port.in.UpdateRouteNodeDisplayCommand;
import com.easysubway.transit.application.port.in.UpdateSimplifiedStationLayoutStatusCommand;
import com.easysubway.transit.application.port.in.UpdateStationLayoutSourceCommand;
import com.easysubway.transit.domain.RouteEdge;
import com.easysubway.transit.domain.RouteEdgeNotFoundException;
import com.easysubway.transit.domain.RouteNode;
import com.easysubway.transit.domain.RouteNodeNotFoundException;
import com.easysubway.transit.domain.SimplifiedStationLayout;
import com.easysubway.transit.domain.SimplifiedStationLayoutNotFoundException;
import com.easysubway.transit.domain.SimplifiedStationLayoutStatus;
import com.easysubway.transit.domain.StationLayoutSource;
import com.easysubway.transit.domain.StationLayoutSourceNotFoundException;
import com.easysubway.transit.domain.StationLayoutSourceType;
import com.easysubway.transit.domain.StationLineSummary;
import com.easysubway.transit.domain.StationWithLines;
import com.easysubway.transit.domain.MasterDataWriteNotAllowedException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.security.Principal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
class TransitStationLayoutAdminPageController {

	private final TransitMasterAdminUseCase transitMasterAdminUseCase;
	private final TransitMasterQueryUseCase transitMasterQueryUseCase;

	TransitStationLayoutAdminPageController(
		TransitMasterAdminUseCase transitMasterAdminUseCase,
		TransitMasterQueryUseCase transitMasterQueryUseCase
	) {
		this.transitMasterAdminUseCase = transitMasterAdminUseCase;
		this.transitMasterQueryUseCase = transitMasterQueryUseCase;
	}

	@GetMapping("/admin/stations/{stationId}/layouts/page")
	String stationLayoutsPage(@PathVariable String stationId, Model model) {
		populateStationLayoutsModel(stationId, model);
		return "admin/stations/layouts";
	}

	private void populateStationLayoutsModel(String stationId, Model model) {
		StationWithLines station = transitMasterQueryUseCase.getStation(stationId);
		model.addAttribute("station", StationLayoutPageStation.from(station));
		model.addAttribute("layoutSources", layoutSourceRows(stationId));
		model.addAttribute("sourceTypeOptions", sourceTypeOptions());
		model.addAttribute("layouts", layoutRows(stationId));
		model.addAttribute("layoutStatusOptions", layoutStatusOptions());
		model.addAttribute("routeNodes", routeNodeRows(stationId));
		model.addAttribute("routeEdges", routeEdgeRows(stationId));
		model.addAttribute("masterDataWritable", transitMasterAdminUseCase.masterDataCapability().writable());
	}

	private String stationLayoutsValidationError(String stationId, Model model, BindingResult bindingResult, HttpServletResponse response) {
		response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
		populateStationLayoutsModel(stationId, model);
		AdminFormErrorView.expose(model, bindingResult);
		return "admin/stations/layouts";
	}

	@PostMapping("/admin/stations/{stationId}/layouts/{layoutId}/page/status")
	String updateLayoutStatusFromPage(
		@PathVariable String stationId,
		@PathVariable String layoutId,
		@Valid @ModelAttribute("layoutStatusForm") LayoutStatusForm form,
		BindingResult bindingResult,
		Principal principal,
		RedirectAttributes redirectAttributes,
		Model model,
		HttpServletResponse response
	) {
		if (bindingResult.hasErrors()) {
			return stationLayoutsValidationError(stationId, model, bindingResult, response);
		}
		requireLayoutInStation(stationId, layoutId);
		try {
			transitMasterAdminUseCase.updateSimplifiedStationLayoutStatus(new UpdateSimplifiedStationLayoutStatusCommand(
				layoutId,
				form.status(),
				principal.getName(),
				form.expectedVersion()
			));
		} catch (MasterDataWriteNotAllowedException exception) {
			redirectAttributes.addFlashAttribute("masterDataError", exception.getMessage());
		}
		return "redirect:/admin/stations/%s/layouts/page".formatted(stationId);
	}

	@PostMapping("/admin/stations/{stationId}/layout-sources/{sourceId}/page")
	String updateStationLayoutSourceFromPage(
		@PathVariable String stationId,
		@PathVariable String sourceId,
		@Valid @ModelAttribute("layoutSourceForm") LayoutSourceForm form,
		BindingResult bindingResult,
		Principal principal,
		RedirectAttributes redirectAttributes,
		Model model,
		HttpServletResponse response
	) {
		if (bindingResult.hasErrors()) {
			return stationLayoutsValidationError(stationId, model, bindingResult, response);
		}
		requireStationLayoutSourceInStation(stationId, sourceId);
		try {
			transitMasterAdminUseCase.updateStationLayoutSource(new UpdateStationLayoutSourceCommand(
				stationId,
				sourceId,
				form.sourceType(),
				form.sourceName(),
				form.sourceUrl(),
				form.license(),
				form.commercialUseAllowed(),
				form.attributionRequired(),
				form.capturedAt(),
				form.reviewedAt(),
				principal.getName()
			));
		} catch (MasterDataWriteNotAllowedException exception) {
			redirectAttributes.addFlashAttribute("masterDataError", exception.getMessage());
		}
		return "redirect:/admin/stations/%s/layouts/page".formatted(stationId);
	}

	@PostMapping("/admin/stations/{stationId}/route-nodes/{nodeId}/page")
	String updateRouteNodeDisplayFromPage(
		@PathVariable String stationId,
		@PathVariable String nodeId,
		@Valid @ModelAttribute("routeNodeForm") RouteNodeForm form,
		BindingResult bindingResult,
		Principal principal,
		RedirectAttributes redirectAttributes,
		Model model,
		HttpServletResponse response
	) {
		if (bindingResult.hasErrors()) {
			return stationLayoutsValidationError(stationId, model, bindingResult, response);
		}
		requireRouteNodeInStation(stationId, nodeId);
		try {
			transitMasterAdminUseCase.updateRouteNodeDisplay(new UpdateRouteNodeDisplayCommand(
				stationId,
				nodeId,
				form.displayX(),
				form.displayY(),
				form.displayLabel(),
				form.accessibilityNote(),
				principal.getName()
			));
		} catch (MasterDataWriteNotAllowedException exception) {
			redirectAttributes.addFlashAttribute("masterDataError", exception.getMessage());
		}
		return "redirect:/admin/stations/%s/layouts/page".formatted(stationId);
	}

	@PostMapping("/admin/stations/{stationId}/route-edges/{edgeId}/page")
	String updateRouteEdgeFromPage(
		@PathVariable String stationId,
		@PathVariable String edgeId,
		@Valid @ModelAttribute("routeEdgeForm") RouteEdgeForm form,
		BindingResult bindingResult,
		Principal principal,
		RedirectAttributes redirectAttributes,
		Model model,
		HttpServletResponse response
	) {
		if (bindingResult.hasErrors()) {
			return stationLayoutsValidationError(stationId, model, bindingResult, response);
		}
		requireRouteEdgeInStation(stationId, edgeId);
		try {
			transitMasterAdminUseCase.updateRouteEdge(new UpdateRouteEdgeCommand(
				stationId,
				edgeId,
				form.distanceMeters(),
				form.estimatedSeconds(),
				form.hasStairs(),
				form.requiresElevator(),
				form.requiresEscalator(),
				form.slopeLevel(),
				form.widthLevel(),
				form.reliabilityScore(),
				form.active(),
				principal.getName()
			));
		} catch (MasterDataWriteNotAllowedException exception) {
			redirectAttributes.addFlashAttribute("masterDataError", exception.getMessage());
		}
		return "redirect:/admin/stations/%s/layouts/page".formatted(stationId);
	}

	private void requireLayoutInStation(String stationId, String layoutId) {
		transitMasterQueryUseCase.getStation(stationId);
		boolean matched = transitMasterQueryUseCase.listSimplifiedStationLayouts(stationId)
			.stream()
			.anyMatch(layout -> layout.id().equals(layoutId));
		if (!matched) {
			throw new SimplifiedStationLayoutNotFoundException();
		}
	}

	private void requireStationLayoutSourceInStation(String stationId, String sourceId) {
		transitMasterQueryUseCase.getStation(stationId);
		boolean matched = transitMasterQueryUseCase.listStationLayoutSources(stationId)
			.stream()
			.anyMatch(source -> source.id().equals(sourceId));
		if (!matched) {
			throw new StationLayoutSourceNotFoundException();
		}
	}

	private void requireRouteNodeInStation(String stationId, String nodeId) {
		transitMasterQueryUseCase.getStation(stationId);
		boolean matched = transitMasterQueryUseCase.listRouteNodes(stationId)
			.stream()
			.anyMatch(node -> node.id().equals(nodeId));
		if (!matched) {
			throw new RouteNodeNotFoundException();
		}
	}

	private void requireRouteEdgeInStation(String stationId, String edgeId) {
		transitMasterQueryUseCase.getStation(stationId);
		boolean matched = transitMasterQueryUseCase.listRouteEdges(stationId)
			.stream()
			.anyMatch(edge -> edge.id().equals(edgeId));
		if (!matched) {
			throw new RouteEdgeNotFoundException();
		}
	}

	private List<StationLayoutSourceRow> layoutSourceRows(String stationId) {
		return transitMasterQueryUseCase.listStationLayoutSources(stationId)
			.stream()
			.map(StationLayoutSourceRow::from)
			.toList();
	}

	private List<SimplifiedStationLayoutRow> layoutRows(String stationId) {
		return transitMasterQueryUseCase.listSimplifiedStationLayouts(stationId)
			.stream()
			.map(SimplifiedStationLayoutRow::from)
			.toList();
	}

	private List<RouteNodeRow> routeNodeRows(String stationId) {
		return transitMasterQueryUseCase.listRouteNodes(stationId)
			.stream()
			.map(RouteNodeRow::from)
			.toList();
	}

	private List<RouteEdgeRow> routeEdgeRows(String stationId) {
		return transitMasterQueryUseCase.listRouteEdges(stationId)
			.stream()
			.map(RouteEdgeRow::from)
			.toList();
	}

	private static List<LayoutStatusOption> layoutStatusOptions() {
		return Arrays.stream(SimplifiedStationLayoutStatus.values())
			.map(status -> new LayoutStatusOption(status, status.name()))
			.toList();
	}

	private static List<SourceTypeOption> sourceTypeOptions() {
		return Arrays.stream(StationLayoutSourceType.values())
			.map(type -> new SourceTypeOption(type, type.name()))
			.toList();
	}

	record LayoutStatusForm(
		@NotNull(message = "{validation.transit.layout-status.required}")
		SimplifiedStationLayoutStatus status,
		Integer expectedVersion
	) {

		LayoutStatusForm(SimplifiedStationLayoutStatus status) {
			this(status, null);
		}
	}

	record LayoutSourceForm(
		@NotNull(message = "{validation.transit.layout-source-type.required}")
		StationLayoutSourceType sourceType,
		@NotBlank(message = "{validation.transit.layout-source-name.required}")
		String sourceName,
		@NotBlank(message = "{validation.transit.layout-source-url.required}")
		String sourceUrl,
		@NotBlank(message = "{validation.transit.layout-source-license.required}")
		String license,
		@NotNull(message = "{validation.transit.layout-source-commercial-use.required}")
		Boolean commercialUseAllowed,
		@NotNull(message = "{validation.transit.layout-source-attribution.required}")
		Boolean attributionRequired,
		@NotNull(message = "{validation.transit.layout-source-captured-at.required}")
		LocalDate capturedAt,
		LocalDate reviewedAt
	) {
	}

	record RouteNodeForm(
		@NotNull(message = "{validation.transit.route-node-display-coordinate.required}")
		Integer displayX,
		@NotNull(message = "{validation.transit.route-node-display-coordinate.required}")
		Integer displayY,
		@NotBlank(message = "{validation.transit.route-node-label.required}")
		String displayLabel,
		String accessibilityNote
	) {
	}

	record RouteEdgeForm(
		@NotNull(message = "{validation.transit.route-edge-distance.required}")
		Integer distanceMeters,
		@NotNull(message = "{validation.transit.route-edge-estimated-seconds.required}")
		Integer estimatedSeconds,
		@NotNull(message = "{validation.transit.route-edge-stairs.required}")
		Boolean hasStairs,
		@NotNull(message = "{validation.transit.route-edge-elevator.required}")
		Boolean requiresElevator,
		@NotNull(message = "{validation.transit.route-edge-escalator.required}")
		Boolean requiresEscalator,
		@NotNull(message = "{validation.transit.route-edge-slope.required}")
		Integer slopeLevel,
		@NotNull(message = "{validation.transit.route-edge-width.required}")
		Integer widthLevel,
		@NotNull(message = "{validation.transit.route-edge-reliability.required}")
		Integer reliabilityScore,
		@NotNull(message = "{validation.transit.route-edge-active.required}")
		Boolean active
	) {
	}

	record StationLayoutPageStation(String stationId, String stationName, String lineNames) {

		static StationLayoutPageStation from(StationWithLines stationWithLines) {
			return new StationLayoutPageStation(
				stationWithLines.station().id(),
				stationWithLines.station().nameKo(),
				stationWithLines.lines()
					.stream()
					.map(StationLineSummary::name)
					.collect(Collectors.joining(", "))
			);
		}
	}

	record StationLayoutSourceRow(
		String id,
		StationLayoutSourceType sourceTypeValue,
		String sourceName,
		String sourceType,
		String sourceUrl,
		String license,
		boolean commercialUseAllowed,
		String commercialUseLabel,
		boolean attributionRequired,
		String attributionLabel,
		String capturedAt,
		String rawReviewedAt,
		String reviewedAt
	) {

		static StationLayoutSourceRow from(StationLayoutSource source) {
			return new StationLayoutSourceRow(
				source.id(),
				source.sourceType(),
				source.sourceName(),
				source.sourceType().name(),
				source.sourceUrl(),
				source.license(),
				source.commercialUseAllowed(),
				source.commercialUseAllowed() ? "상업적 사용 가능" : "상업적 사용 불가",
				source.attributionRequired(),
				source.attributionRequired() ? "출처 표시 필요" : "출처 표시 불필요",
				source.capturedAt().toString(),
				source.reviewedAt() == null ? "" : source.reviewedAt().toString(),
				source.reviewedAt() == null ? "검수 전" : source.reviewedAt().toString()
			);
		}
	}

	record SimplifiedStationLayoutRow(
		String id,
		int version,
		SimplifiedStationLayoutStatus statusValue,
		String status,
		String confidenceLevel,
		String baseFloor,
		String sourceIds,
		String lastVerifiedAt
	) {

		static SimplifiedStationLayoutRow from(SimplifiedStationLayout layout) {
			return new SimplifiedStationLayoutRow(
				layout.id(),
				layout.version(),
				layout.status(),
				layout.status().name(),
				layout.confidenceLevel().name(),
				layout.baseFloor(),
				String.join(", ", layout.sourceIds()),
				layout.lastVerifiedAt().toString()
			);
		}
	}

	record LayoutStatusOption(SimplifiedStationLayoutStatus value, String label) {
	}

	record SourceTypeOption(StationLayoutSourceType value, String label) {
	}

	record RouteNodeRow(
		String id,
		String type,
		String name,
		String floor,
		String layoutId,
		int displayX,
		int displayY,
		String displayLabel,
		String positionLabel,
		String rawAccessibilityNote,
		String accessibilityNote
	) {

		static RouteNodeRow from(RouteNode node) {
			return new RouteNodeRow(
				node.id(),
				node.type().name(),
				node.name(),
				node.floor(),
				node.layoutId(),
				node.displayX(),
				node.displayY(),
				node.displayLabel(),
				"x %d, y %d".formatted(node.displayX(), node.displayY()),
				node.accessibilityNote(),
				node.accessibilityNote() == null ? "접근성 메모 없음" : node.accessibilityNote()
			);
		}
	}

	record RouteEdgeRow(
		String id,
		String fromNodeId,
		String toNodeId,
		String type,
		int distanceMeters,
		String distanceLabel,
		int estimatedSeconds,
		String estimatedSecondsLabel,
		boolean hasStairs,
		String stairsLabel,
		boolean requiresElevator,
		String elevatorLabel,
		boolean requiresEscalator,
		String escalatorLabel,
		int slopeLevel,
		int widthLevel,
		int reliabilityScore,
		boolean active,
		String activeLabel,
		String reliabilityLabel
	) {

		static RouteEdgeRow from(RouteEdge edge) {
			return new RouteEdgeRow(
				edge.id(),
				edge.fromNodeId(),
				edge.toNodeId(),
				edge.type().name(),
				edge.distanceMeters(),
				edge.distanceMeters() + "m",
				edge.estimatedSeconds(),
				edge.estimatedSeconds() + "초",
				edge.hasStairs(),
				edge.hasStairs() ? "계단 포함" : "계단 없음",
				edge.requiresElevator(),
				edge.requiresElevator() ? "엘리베이터 필요" : "엘리베이터 불필요",
				edge.requiresEscalator(),
				edge.requiresEscalator() ? "에스컬레이터 필요" : "에스컬레이터 불필요",
				edge.slopeLevel(),
				edge.widthLevel(),
				edge.reliabilityScore(),
				edge.active(),
				edge.active() ? "활성" : "비활성",
				"신뢰도 " + edge.reliabilityScore()
			);
		}
	}
}

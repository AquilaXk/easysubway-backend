package com.easysubway.transit.adapter.in.web;

import com.easysubway.transit.application.port.in.TransitMasterQueryUseCase;
import com.easysubway.transit.domain.RouteEdge;
import com.easysubway.transit.domain.RouteNode;
import com.easysubway.transit.domain.SimplifiedStationLayout;
import com.easysubway.transit.domain.StationLayoutSource;
import com.easysubway.transit.domain.StationLineSummary;
import com.easysubway.transit.domain.StationWithLines;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
class TransitStationLayoutAdminPageController {

	private final TransitMasterQueryUseCase transitMasterQueryUseCase;

	TransitStationLayoutAdminPageController(TransitMasterQueryUseCase transitMasterQueryUseCase) {
		this.transitMasterQueryUseCase = transitMasterQueryUseCase;
	}

	@GetMapping("/admin/stations/{stationId}/layouts/page")
	String stationLayoutsPage(@PathVariable String stationId, Model model) {
		StationWithLines station = transitMasterQueryUseCase.getStation(stationId);
		model.addAttribute("station", StationLayoutPageStation.from(station));
		model.addAttribute("layoutSources", layoutSourceRows(stationId));
		model.addAttribute("layouts", layoutRows(stationId));
		model.addAttribute("routeNodes", routeNodeRows(stationId));
		model.addAttribute("routeEdges", routeEdgeRows(stationId));
		return "admin/stations/layouts";
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
		String sourceName,
		String sourceType,
		String license,
		String commercialUseLabel,
		String attributionLabel,
		String capturedAt,
		String reviewedAt
	) {

		static StationLayoutSourceRow from(StationLayoutSource source) {
			return new StationLayoutSourceRow(
				source.sourceName(),
				source.sourceType().name(),
				source.license(),
				source.commercialUseAllowed() ? "상업적 사용 가능" : "상업적 사용 불가",
				source.attributionRequired() ? "출처 표시 필요" : "출처 표시 불필요",
				source.capturedAt().toString(),
				source.reviewedAt() == null ? "검수 전" : source.reviewedAt().toString()
			);
		}
	}

	record SimplifiedStationLayoutRow(
		String id,
		int version,
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
				layout.status().name(),
				layout.confidenceLevel().name(),
				layout.baseFloor(),
				String.join(", ", layout.sourceIds()),
				layout.lastVerifiedAt().toString()
			);
		}
	}

	record RouteNodeRow(
		String id,
		String type,
		String name,
		String floor,
		String layoutId,
		String displayLabel,
		String positionLabel,
		String accessibilityNote
	) {

		static RouteNodeRow from(RouteNode node) {
			return new RouteNodeRow(
				node.id(),
				node.type().name(),
				node.name(),
				node.floor(),
				node.layoutId(),
				node.displayLabel(),
				"x %d, y %d".formatted(node.displayX(), node.displayY()),
				node.accessibilityNote() == null ? "접근성 메모 없음" : node.accessibilityNote()
			);
		}
	}

	record RouteEdgeRow(
		String id,
		String fromNodeId,
		String toNodeId,
		String type,
		String distanceLabel,
		String estimatedSecondsLabel,
		String stairsLabel,
		String elevatorLabel,
		String escalatorLabel,
		String reliabilityLabel
	) {

		static RouteEdgeRow from(RouteEdge edge) {
			return new RouteEdgeRow(
				edge.id(),
				edge.fromNodeId(),
				edge.toNodeId(),
				edge.type().name(),
				edge.distanceMeters() + "m",
				edge.estimatedSeconds() + "초",
				edge.hasStairs() ? "계단 포함" : "계단 없음",
				edge.requiresElevator() ? "엘리베이터 필요" : "엘리베이터 불필요",
				edge.requiresEscalator() ? "에스컬레이터 필요" : "에스컬레이터 불필요",
				"신뢰도 " + edge.reliabilityScore()
			);
		}
	}
}

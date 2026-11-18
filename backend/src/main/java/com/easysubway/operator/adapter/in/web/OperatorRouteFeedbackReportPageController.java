package com.easysubway.operator.adapter.in.web;

import com.easysubway.route.adapter.in.web.RouteFeedbackDashboardAssembler;
import com.easysubway.route.adapter.in.web.RouteFeedbackDashboardView;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
class OperatorRouteFeedbackReportPageController {

	private final RouteFeedbackDashboardAssembler routeFeedbackDashboardAssembler;

	OperatorRouteFeedbackReportPageController(RouteFeedbackDashboardAssembler routeFeedbackDashboardAssembler) {
		this.routeFeedbackDashboardAssembler = routeFeedbackDashboardAssembler;
	}

	@GetMapping("/operator/route-feedback-report/page")
	String routeFeedbackReportPage(
		@RequestParam(required = false) String q,
		@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
		@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
		@RequestParam(required = false) String sort,
		@RequestParam(required = false) String direction,
		Model model
	) {
		OperatorReportQuery query = OperatorReportQuery.of(q, from, to, sort, direction);
		model.addAttribute("query", query);
		model.addAttribute("summary", filtered(routeFeedbackDashboardAssembler.assemble(), query));
		return "operator/route-feedback-report";
	}

	private static RouteFeedbackDashboardView filtered(RouteFeedbackDashboardView view, OperatorReportQuery query) {
		List<RouteFeedbackDashboardView.RecentBlockedFeedbackRow> recentRows = view.recentBlockedFeedbacks()
			.stream()
			.filter(row -> query.matches(
				row.originStationName(),
				row.destinationStationName(),
				row.mobilityTypeLabel()
			))
			.filter(row -> query.includesDateLabel(row.createdAtLabel()))
			.sorted(routeFeedbackComparator(query))
			.toList();
		return new RouteFeedbackDashboardView(
			view.totalCount(),
			view.helpfulCount(),
			view.notHelpfulCount(),
			view.blockedByRealWorldCount(),
			view.ratingRows(),
			recentRows,
			view.etaCalibrationBuckets()
		);
	}

	private static Comparator<RouteFeedbackDashboardView.RecentBlockedFeedbackRow> routeFeedbackComparator(
		OperatorReportQuery query
	) {
		Comparator<RouteFeedbackDashboardView.RecentBlockedFeedbackRow> comparator = switch (query.sort()) {
			case "origin" -> Comparator.comparing(RouteFeedbackDashboardView.RecentBlockedFeedbackRow::originStationName);
			case "destination" -> Comparator.comparing(
				RouteFeedbackDashboardView.RecentBlockedFeedbackRow::destinationStationName
			);
			case "mobility" -> Comparator.comparing(RouteFeedbackDashboardView.RecentBlockedFeedbackRow::mobilityTypeLabel);
			default -> Comparator.comparing(RouteFeedbackDashboardView.RecentBlockedFeedbackRow::createdAtLabel);
		};
		return "desc".equals(query.direction()) || query.sort().isBlank() ? comparator.reversed() : comparator;
	}
}

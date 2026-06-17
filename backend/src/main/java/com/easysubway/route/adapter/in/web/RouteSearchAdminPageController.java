package com.easysubway.route.adapter.in.web;

import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.application.port.in.RouteSearchDashboardUseCase;
import com.easysubway.route.domain.RouteSearchDashboardSummary;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class RouteSearchAdminPageController {

	private final RouteSearchDashboardUseCase routeSearchDashboardUseCase;

	RouteSearchAdminPageController(RouteSearchDashboardUseCase routeSearchDashboardUseCase) {
		this.routeSearchDashboardUseCase = routeSearchDashboardUseCase;
	}

	@GetMapping("/admin/routes/searches/page")
	String routeSearchDashboardPage(Model model) {
		RouteSearchDashboardSummary summary = routeSearchDashboardUseCase.summarizeRouteSearches();
		model.addAttribute("summary", RouteSearchDashboardView.from(summary));
		return "admin/routes/searches";
	}

	record RouteSearchDashboardView(
		long totalCount,
		long foundCount,
		long blockedCount,
		List<MobilityTypeCountRow> mobilityTypeRows
	) {

		static RouteSearchDashboardView from(RouteSearchDashboardSummary summary) {
			return new RouteSearchDashboardView(
				summary.totalCount(),
				summary.foundCount(),
				summary.blockedCount(),
				summary.mobilityTypeCounts()
					.stream()
					.map(row -> new MobilityTypeCountRow(mobilityTypeLabel(row.mobilityType()), row.count()))
					.toList()
			);
		}
	}

	record MobilityTypeCountRow(String label, long count) {
	}

	private static String mobilityTypeLabel(MobilityType mobilityType) {
		return switch (mobilityType) {
			case SENIOR -> "고령자";
			case STROLLER -> "유모차 동반";
			case WHEELCHAIR -> "휠체어 사용자";
			case PREGNANT -> "임산부";
			case TEMPORARY_INJURY -> "일시 부상";
			case LUGGAGE -> "큰 짐 동반";
		};
	}
}

package com.easysubway.route.application.port.out;

import com.easysubway.route.domain.RouteFeedbackDashboardSummary;

public interface SummarizeRouteFeedbackPort {

	RouteFeedbackDashboardSummary summarizeRouteFeedbacks();
}

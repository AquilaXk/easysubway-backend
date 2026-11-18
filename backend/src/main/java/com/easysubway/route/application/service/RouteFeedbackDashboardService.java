package com.easysubway.route.application.service;

import com.easysubway.route.application.port.in.RouteFeedbackDashboardUseCase;
import com.easysubway.route.application.port.out.SummarizeRouteFeedbackPort;
import com.easysubway.route.domain.RouteFeedbackDashboardSummary;
import org.springframework.stereotype.Service;

@Service
public class RouteFeedbackDashboardService implements RouteFeedbackDashboardUseCase {

	private final SummarizeRouteFeedbackPort summarizeRouteFeedbackPort;

	public RouteFeedbackDashboardService(SummarizeRouteFeedbackPort summarizeRouteFeedbackPort) {
		this.summarizeRouteFeedbackPort = summarizeRouteFeedbackPort;
	}

	@Override
	public RouteFeedbackDashboardSummary summarizeRouteFeedbacks() {
		return summarizeRouteFeedbackPort.summarizeRouteFeedbacks();
	}
}

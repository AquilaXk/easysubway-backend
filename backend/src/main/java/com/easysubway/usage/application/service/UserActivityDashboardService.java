package com.easysubway.usage.application.service;

import com.easysubway.usage.application.port.in.UserActivityDashboardUseCase;
import com.easysubway.usage.application.port.out.SummarizeUserActivityPort;
import com.easysubway.usage.domain.UserActivityDashboardSummary;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class UserActivityDashboardService implements UserActivityDashboardUseCase {

	static final int SUMMARY_DAYS = 7;

	private final SummarizeUserActivityPort summarizeUserActivityPort;
	private final Clock clock;

	@Autowired
	public UserActivityDashboardService(
		SummarizeUserActivityPort summarizeUserActivityPort,
		ObjectProvider<Clock> clockProvider
	) {
		this(summarizeUserActivityPort, clockProvider.getIfAvailable(Clock::systemDefaultZone));
	}

	UserActivityDashboardService(SummarizeUserActivityPort summarizeUserActivityPort, Clock clock) {
		this.summarizeUserActivityPort = summarizeUserActivityPort;
		this.clock = clock;
	}

	@Override
	public UserActivityDashboardSummary summarizeUserActivity() {
		return summarizeUserActivityPort.summarizeUserActivity(LocalDate.now(clock), SUMMARY_DAYS);
	}
}

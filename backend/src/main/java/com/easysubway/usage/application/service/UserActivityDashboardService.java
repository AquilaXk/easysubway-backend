package com.easysubway.usage.application.service;

import com.easysubway.common.time.BusinessTime;
import com.easysubway.usage.application.port.in.UserActivityDashboardUseCase;
import com.easysubway.usage.application.port.out.SummarizeUserActivityPort;
import com.easysubway.usage.domain.UserActivityDashboardSummary;
import java.time.Clock;
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
		this(summarizeUserActivityPort, clockProvider.getIfAvailable(Clock::systemUTC));
	}

	UserActivityDashboardService(SummarizeUserActivityPort summarizeUserActivityPort, Clock clock) {
		this.summarizeUserActivityPort = summarizeUserActivityPort;
		this.clock = clock;
	}

	@Override
	public UserActivityDashboardSummary summarizeUserActivity() {
		// 집계 쪽이 저장된 UTC 발생 시각을 한국 달력 날짜로 묶으므로 기준일도 한국 날짜다.
		return summarizeUserActivityPort.summarizeUserActivity(BusinessTime.today(clock), SUMMARY_DAYS);
	}
}

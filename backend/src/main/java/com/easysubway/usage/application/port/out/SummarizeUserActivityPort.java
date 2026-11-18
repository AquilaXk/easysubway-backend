package com.easysubway.usage.application.port.out;

import com.easysubway.usage.domain.UserActivityDashboardSummary;
import java.time.LocalDate;

public interface SummarizeUserActivityPort {

	UserActivityDashboardSummary summarizeUserActivity(LocalDate today, int days);
}

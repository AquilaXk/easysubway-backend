package com.easysubway.report.application.port.out;

import java.time.LocalDateTime;

public interface PurgeFacilityReportPersonalDataPort {

	int purgePersonalDataCreatedBefore(LocalDateTime cutoff);
}

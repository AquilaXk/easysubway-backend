package com.easysubway.quality.application.port.in;

import com.easysubway.quality.domain.DataQualitySummary;

public interface DataQualityUseCase {

	DataQualitySummary summarizeDataQuality();
}

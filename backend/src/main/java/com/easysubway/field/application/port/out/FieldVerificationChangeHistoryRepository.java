package com.easysubway.field.application.port.out;

import com.easysubway.field.domain.FieldVerificationChangeHistory;
import java.util.List;

public interface FieldVerificationChangeHistoryRepository {

	void save(FieldVerificationChangeHistory history);

	List<FieldVerificationChangeHistory> listByStationId(String stationId);
}

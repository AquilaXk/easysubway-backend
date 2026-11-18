package com.easysubway.usage.application.port.out;

import java.time.LocalDateTime;

public interface RecordUserActivityPort {

	void recordUserActivity(String userId, LocalDateTime occurredAt);
}

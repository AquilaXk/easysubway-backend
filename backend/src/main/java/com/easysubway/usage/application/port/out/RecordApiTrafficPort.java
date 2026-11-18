package com.easysubway.usage.application.port.out;

import java.time.LocalDateTime;

public interface RecordApiTrafficPort {

	void recordApiTraffic(int statusCode, long durationMillis, LocalDateTime occurredAt);
}

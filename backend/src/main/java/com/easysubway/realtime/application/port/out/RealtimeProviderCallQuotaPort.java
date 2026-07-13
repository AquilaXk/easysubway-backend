package com.easysubway.realtime.application.port.out;

import java.time.Instant;
import java.time.ZoneId;

public interface RealtimeProviderCallQuotaPort {

	boolean tryAcquire(
		String providerId,
		Instant now,
		ZoneId providerZone,
		int limitPerMinute,
		int limitPerDay
	);
}

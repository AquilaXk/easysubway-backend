package com.easysubway.realtime.adapter.out.persistence;

import com.easysubway.realtime.application.port.out.RealtimeArrivalArchivePort;
import com.easysubway.realtime.application.port.out.RealtimeProviderCallQuotaPort;
import com.easysubway.realtime.domain.RealtimeArrivalObservation;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile({"default", "dev", "test"})
public class DevelopmentRealtimeSafetyPorts implements RealtimeArrivalArchivePort, RealtimeProviderCallQuotaPort {
	private final Map<String, QuotaState> statesByProvider = new HashMap<>();

	@Override
	public void saveAll(List<RealtimeArrivalObservation> observations) {
		// 로컬·테스트 profile은 운영 archive를 생성하지 않는다.
	}

	@Override
	public int deleteExpired(Instant now) {
		Objects.requireNonNull(now, "now must not be null");
		return 0;
	}

	@Override
	public synchronized boolean tryAcquire(
		String providerId,
		Instant now,
		ZoneId providerZone,
		int limitPerMinute,
		int limitPerDay
	) {
		Objects.requireNonNull(providerId, "providerId must not be null");
		Objects.requireNonNull(now, "now must not be null");
		Objects.requireNonNull(providerZone, "providerZone must not be null");
		if (limitPerMinute <= 0) {
			throw new IllegalArgumentException("limitPerMinute must be positive");
		}
		if (limitPerDay <= 0) {
			throw new IllegalArgumentException("limitPerDay must be positive");
		}
		QuotaState state = statesByProvider.computeIfAbsent(providerId, ignored -> new QuotaState());
		long minute = now.getEpochSecond() / 60;
		long day = now.atZone(providerZone).toLocalDate().toEpochDay();
		if (minute != state.windowMinute) {
			state.windowMinute = minute;
			state.minuteCalls = 0;
		}
		if (day != state.windowDay) {
			state.windowDay = day;
			state.dailyCalls = 0;
		}
		if (state.minuteCalls >= limitPerMinute || state.dailyCalls >= limitPerDay) {
			return false;
		}
		state.minuteCalls += 1;
		state.dailyCalls += 1;
		return true;
	}

	private static final class QuotaState {
		private long windowMinute = Long.MIN_VALUE;
		private long windowDay = Long.MIN_VALUE;
		private int minuteCalls;
		private int dailyCalls;
	}
}

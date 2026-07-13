package com.easysubway.realtime.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("dev/test realtime safety port")
class DevelopmentRealtimeSafetyPortsTest {

	@Test
	@DisplayName("provider별 quota 상태를 서로 격리한다")
	void isolatesQuotaByProvider() {
		DevelopmentRealtimeSafetyPorts ports = new DevelopmentRealtimeSafetyPorts();
		Instant now = Instant.parse("2026-07-13T01:00:00Z");
		ZoneId providerZone = ZoneId.of("Asia/Seoul");

		assertThat(ports.tryAcquire("seoul-topis", now, providerZone, 1, 1)).isTrue();
		assertThat(ports.tryAcquire("seoul-topis", now, providerZone, 1, 1)).isFalse();
		assertThat(ports.tryAcquire("other-provider", now, providerZone, 1, 1)).isTrue();
	}

	@Test
	@DisplayName("분당·일일 quota를 독립적으로 제한한다")
	void enforcesMinuteAndDailyLimitsIndependently() {
		Instant now = Instant.parse("2026-07-13T01:00:00Z");
		ZoneId providerZone = ZoneId.of("Asia/Seoul");
		DevelopmentRealtimeSafetyPorts minuteLimited = new DevelopmentRealtimeSafetyPorts();
		DevelopmentRealtimeSafetyPorts dailyLimited = new DevelopmentRealtimeSafetyPorts();

		assertThat(minuteLimited.tryAcquire("seoul-topis", now, providerZone, 1, 10)).isTrue();
		assertThat(minuteLimited.tryAcquire("seoul-topis", now, providerZone, 1, 10)).isFalse();
		assertThat(minuteLimited.tryAcquire("seoul-topis", now.plusSeconds(60), providerZone, 1, 10)).isTrue();

		assertThat(dailyLimited.tryAcquire("seoul-topis", now, providerZone, 10, 1)).isTrue();
		assertThat(dailyLimited.tryAcquire("seoul-topis", now.plusSeconds(60), providerZone, 10, 1)).isFalse();
	}

	@Test
	@DisplayName("분 경계와 provider 지역의 날짜 경계에서 해당 window를 reset한다")
	void resetsMinuteAndProviderDayWindows() {
		ZoneId providerZone = ZoneId.of("Asia/Seoul");
		DevelopmentRealtimeSafetyPorts minuteWindow = new DevelopmentRealtimeSafetyPorts();
		Instant minuteEnd = Instant.parse("2026-07-13T01:00:59Z");
		assertThat(minuteWindow.tryAcquire("seoul-topis", minuteEnd, providerZone, 1, 10)).isTrue();
		assertThat(minuteWindow.tryAcquire("seoul-topis", minuteEnd.plusSeconds(1), providerZone, 1, 10)).isTrue();

		DevelopmentRealtimeSafetyPorts dayWindow = new DevelopmentRealtimeSafetyPorts();
		Instant kstDayEnd = Instant.parse("2026-07-13T14:59:59Z");
		assertThat(dayWindow.tryAcquire("seoul-topis", kstDayEnd, providerZone, 10, 1)).isTrue();
		assertThat(dayWindow.tryAcquire("seoul-topis", kstDayEnd.plusSeconds(1), providerZone, 10, 1)).isTrue();
	}

	@Test
	@DisplayName("quota 기준 시각과 provider timezone은 필수다")
	void rejectsNullTimeInputs() {
		DevelopmentRealtimeSafetyPorts ports = new DevelopmentRealtimeSafetyPorts();
		Instant now = Instant.parse("2026-07-13T01:00:00Z");
		ZoneId providerZone = ZoneId.of("Asia/Seoul");

		assertThatThrownBy(() -> ports.tryAcquire("seoul-topis", null, providerZone, 1, 1))
			.isInstanceOf(NullPointerException.class)
			.hasMessage("now must not be null");
		assertThatThrownBy(() -> ports.tryAcquire("seoul-topis", now, null, 1, 1))
			.isInstanceOf(NullPointerException.class)
			.hasMessage("providerZone must not be null");
		assertThatThrownBy(() -> ports.tryAcquire("seoul-topis", now, providerZone, 0, 1))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("limitPerMinute must be positive");
		assertThatThrownBy(() -> ports.tryAcquire("seoul-topis", now, providerZone, 1, 0))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("limitPerDay must be positive");
	}
}

package com.easysubway.realtime.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("dev/test realtime safety port")
class DevelopmentRealtimeSafetyPortsTest {

	@Test
	@DisplayName("로컬·테스트 환경 archive 포트는 no-op으로 동작한다")
	void archivePortOperatesAsNoOp() {
		DevelopmentRealtimeSafetyPorts ports = new DevelopmentRealtimeSafetyPorts();
		ports.saveAll(List.of());
		assertThat(ports.deleteExpired(Instant.parse("2026-07-13T01:00:00Z"))).isEqualTo(0);
		assertThatThrownBy(() -> ports.deleteExpired(null))
			.isInstanceOf(NullPointerException.class)
			.hasMessage("now must not be null");
	}
}

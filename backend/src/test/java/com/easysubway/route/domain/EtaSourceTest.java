package com.easysubway.route.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("ETA 출처 enum 및 저장값 호환 테스트")
class EtaSourceTest {

	@Test
	@DisplayName("과거 저장값 FALLBACK은 PLANNED_WITHOUT_REALTIME으로 호환 매핑된다")
	void fromStoredMapsLegacyFallback() {
		assertThat(EtaSource.fromStored("FALLBACK"))
			.isEqualTo(EtaSource.PLANNED_WITHOUT_REALTIME);
	}

	@Test
	@DisplayName("신규 저장값 PLANNED_WITHOUT_REALTIME은 그대로 매핑된다")
	void fromStoredMapsPlannedWithoutRealtime() {
		assertThat(EtaSource.fromStored("PLANNED_WITHOUT_REALTIME"))
			.isEqualTo(EtaSource.PLANNED_WITHOUT_REALTIME);
	}

	@ParameterizedTest
	@ValueSource(strings = {"STATIC_BACKEND_ESTIMATE", "PLANNED", "REALTIME", "MIXED"})
	@DisplayName("기존 표준 저장값들은 동일한 enum 상수로 매핑된다")
	void fromStoredMapsStandardValues(String value) {
		assertThat(EtaSource.fromStored(value))
			.isEqualTo(EtaSource.valueOf(value));
	}

	@Test
	@DisplayName("알 수 없는 저장값은 IllegalArgumentException 예외를 던진다")
	void fromStoredThrowsOnUnknownValue() {
		assertThatThrownBy(() -> EtaSource.fromStored("NOPE"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("null 입력은 IllegalArgumentException 예외를 던진다")
	void fromStoredThrowsOnNull() {
		assertThatThrownBy(() -> EtaSource.fromStored(null))
			.isInstanceOf(IllegalArgumentException.class);
	}
}

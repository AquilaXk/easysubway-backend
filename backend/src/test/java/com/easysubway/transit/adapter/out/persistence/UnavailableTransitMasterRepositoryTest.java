package com.easysubway.transit.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import com.easysubway.transit.domain.SimplifiedStationLayoutStatus;
import java.time.LocalDate;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("운영 프로필 마스터 데이터 미지원 저장소")
class UnavailableTransitMasterRepositoryTest {

	private final UnavailableTransitMasterRepository repository = new UnavailableTransitMasterRepository();

	@Test
	@DisplayName("읽기 포트는 운영 readiness 기준 데이터를 반환한다")
	void readOperationsReturnSeedMasterData() {
		assertThat(repository.loadOperators()).isNotEmpty();
		assertThat(repository.loadLines()).isNotEmpty();
		assertThat(repository.loadStations()).isNotEmpty();
	}

	@Test
	@DisplayName("쓰기 포트 호출은 조용히 무시하지 않고 명시적으로 실패한다")
	void writeOperationsFailExplicitly() {
		assertUnsupportedWrite(() -> repository.saveFacilityStatus(
			"facility-1",
			AccessibilityFacilityStatus.NORMAL,
			LocalDate.of(2026, 6, 19)
		));
		assertUnsupportedWrite(() -> repository.saveAccessibilityFacility(null));
		assertUnsupportedWrite(() -> repository.saveStationLayoutSource(null));
		assertUnsupportedWrite(() -> repository.saveSimplifiedStationLayoutStatus(
			"layout-1",
			SimplifiedStationLayoutStatus.PUBLISHED,
			"admin",
			LocalDate.of(2026, 6, 19)
		));
		assertUnsupportedWrite(() -> repository.saveRouteNode(null));
		assertUnsupportedWrite(() -> repository.saveRouteEdge(null));
	}

	private void assertUnsupportedWrite(ThrowingCallable callable) {
		assertThatThrownBy(callable)
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("도시철도 마스터 데이터 쓰기");
	}
}

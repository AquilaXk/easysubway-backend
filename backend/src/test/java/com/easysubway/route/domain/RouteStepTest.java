package com.easysubway.route.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RouteStep 생성자 위임과 기본값 정규화")
class RouteStepTest {

	@Test
	@DisplayName("servedAt까지 받는 생성자는 provenance를 보존하고 도보·열차 식별 필드를 비워 둔다")
	void servedAtConstructorKeepsProvenanceAndLeavesTripFieldsEmpty() {
		RouteStep step = new RouteStep(
			2, "ride", "2호선 승차", "시간표 기준 이동", "L2", "2호선", "S1", "S2",
			5, 0, false, "UNKNOWN", false,
			EtaSource.PLANNED.name(), "TIMETABLE", "시간표", List.of("R1"),
			"snapshot-1", "2026-10-01T00:00:00Z", "2026-10-01T00:00:01Z", "2026-10-01T00:00:02Z"
		);

		assertThat(step.providerSnapshotId()).isEqualTo("snapshot-1");
		assertThat(step.providerObservedAt()).isEqualTo("2026-10-01T00:00:00Z");
		assertThat(step.gatewayReceivedAt()).isEqualTo("2026-10-01T00:00:01Z");
		assertThat(step.servedAt()).isEqualTo("2026-10-01T00:00:02Z");
		assertThat(step.reasonCodes()).containsExactly("R1");
		assertThat(step.walkSeconds()).isNull();
		assertThat(step.tripId()).isNull();
		assertThat(step.trainNo()).isNull();
		assertThat(step.plannedDepartureTime()).isNull();
		assertThat(step.plannedArrivalTime()).isNull();
	}

	@Test
	@DisplayName("계단 상태가 비어 있으면 계단 포함 여부로만 STAIR_ONLY 또는 UNKNOWN을 채운다")
	void blankStairAccessStateIsDerivedFromIncludesStairsOnly() {
		RouteStep withStairs = new RouteStep(
			1, "transfer", "환승", "계단 이동", "L2", "2호선", "S1", "S1",
			2, 40, true, " ", true,
			null, null, null, null, null, null, null, null
		);
		RouteStep withoutStairs = new RouteStep(
			1, "transfer", "환승", "이동", "L2", "2호선", "S1", "S1",
			2, 40, false, null, true,
			null, null, null, null, null, null, null, null
		);

		assertThat(withStairs.stairAccessState()).isEqualTo("STAIR_ONLY");
		assertThat(withoutStairs.stairAccessState()).isEqualTo("UNKNOWN");
		assertThat(withoutStairs.timeSource()).isEqualTo("UNKNOWN");
		assertThat(withoutStairs.distanceSource()).isEqualTo("UNKNOWN");
		assertThat(withoutStairs.confidenceLabel()).isEqualTo("확인 필요");
		assertThat(withoutStairs.reasonCodes()).isEmpty();
	}
}

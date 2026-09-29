package com.easysubway.journey.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.BitSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FacilityAvailabilityViewTest {

	@Test
	@DisplayName("FacilityStatusUnavailableException 메시지 및 원인 예외를 올바르게 보존한다")
	void testFacilityStatusUnavailableException() {
		FacilityStatusUnavailableException ex1 = new FacilityStatusUnavailableException("status unavailable");
		assertThat(ex1.getMessage()).isEqualTo("status unavailable");
		assertThat(ex1.getCause()).isNull();

		Throwable cause = new IllegalStateException("timeout");
		FacilityStatusUnavailableException ex2 = new FacilityStatusUnavailableException("failed", cause);
		assertThat(ex2.getMessage()).isEqualTo("failed");
		assertThat(ex2.getCause()).isSameAs(cause);
	}

	@Test
	@DisplayName("SimpleFacilityAvailabilityView는 정상 파라미터로 생성 시 방어적 복사본을 제공한다")
	void testSimpleFacilityAvailabilityViewNormal() {
		Instant now = Instant.parse("2026-09-30T00:00:00Z");
		BitSet bits = new BitSet();
		bits.set(1);
		bits.set(3);

		SimpleFacilityAvailabilityView view = new SimpleFacilityAvailabilityView(
			true,
			now,
			Set.of("edge-1"),
			bits
		);

		assertThat(view.available()).isTrue();
		assertThat(view.observedAt()).isEqualTo(now);
		assertThat(view.blockedPathwayEdgeIds()).containsExactly("edge-1");
		assertThat(view.blockedTransitionIds().get(1)).isTrue();
		assertThat(view.blockedTransitionIds().get(3)).isTrue();
		assertThat(view.blockedTransitionIds().get(2)).isFalse();

		// 방어적 복사 검증: view의 반환 BitSet을 수정해도 내부 상태는 변경되지 않아야 함
		BitSet retrieved = view.blockedTransitionIds();
		retrieved.clear(1);
		assertThat(view.blockedTransitionIds().get(1)).isTrue();
	}

	@Test
	@DisplayName("SimpleFacilityAvailabilityView는 null 집합과 BitSet 전달 시 빈 집합 및 빈 BitSet으로 초기화한다")
	void testSimpleFacilityAvailabilityViewNulls() {
		SimpleFacilityAvailabilityView view = new SimpleFacilityAvailabilityView(
			false,
			null,
			null,
			null
		);

		assertThat(view.available()).isFalse();
		assertThat(view.observedAt()).isNull();
		assertThat(view.blockedPathwayEdgeIds()).isEmpty();
		assertThat(view.blockedTransitionIds().isEmpty()).isTrue();
	}

	@Test
	@DisplayName("UnavailableFacilityAvailabilityView는 항상 비가용 상태와 빈 차단 집합을 반환한다")
	void testUnavailableFacilityAvailabilityView() {
		UnavailableFacilityAvailabilityView view = UnavailableFacilityAvailabilityView.INSTANCE;

		assertThat(view.available()).isFalse();
		assertThat(view.observedAt()).isNull();
		assertThat(view.blockedPathwayEdgeIds()).isEmpty();
		assertThat(view.blockedTransitionIds().isEmpty()).isTrue();
	}

	@Test
	@DisplayName("FacilityAvailabilityView static factory 메서드와 FacilityAvailabilityPort.unavailable 동작을 검증한다")
	void testStaticFactories() {
		Instant now = Instant.parse("2026-09-30T00:00:00Z");

		FacilityAvailabilityView unavail = FacilityAvailabilityView.unavailable();
		assertThat(unavail.available()).isFalse();

		FacilityAvailabilityView empty = FacilityAvailabilityView.empty(now);
		assertThat(empty.available()).isTrue();
		assertThat(empty.observedAt()).isEqualTo(now);
		assertThat(empty.blockedPathwayEdgeIds()).isEmpty();
		assertThat(empty.blockedTransitionIds().isEmpty()).isTrue();

		FacilityAvailabilityView blocked1 = FacilityAvailabilityView.blocked(now, Set.of("edge-A"));
		assertThat(blocked1.available()).isTrue();
		assertThat(blocked1.blockedPathwayEdgeIds()).containsExactly("edge-A");
		assertThat(blocked1.blockedTransitionIds().isEmpty()).isTrue();

		BitSet bits = new BitSet();
		bits.set(5);
		FacilityAvailabilityView blocked2 = FacilityAvailabilityView.blocked(now, Set.of("edge-B"), bits);
		assertThat(blocked2.available()).isTrue();
		assertThat(blocked2.blockedPathwayEdgeIds()).containsExactly("edge-B");
		assertThat(blocked2.blockedTransitionIds().get(5)).isTrue();

		FacilityAvailabilityPort port = FacilityAvailabilityPort.unavailable();
		assertThat(port.currentView().available()).isFalse();
	}
}

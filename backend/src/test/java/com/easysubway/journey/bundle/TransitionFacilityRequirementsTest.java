package com.easysubway.journey.bundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TransitionFacilityRequirementsTest {

	@Test
	@DisplayName("missing 요구사항은 isPresent=false 및 빈 맵/리스트를 반환한다")
	void missingRequirements() {
		var missing = TransitionFacilityRequirements.missing();
		assertThat(missing.isPresent()).isFalse();
		assertThat(missing.requirementsByTransition()).isEmpty();
		assertThat(missing.segmentsForTransition("any")).isEmpty();
		assertThat(missing.segmentsForTransition(null)).isEmpty();
	}

	@Test
	@DisplayName("빈 맵으로 생성된 요구사항은 isPresent=true 및 빈 맵/리스트를 반환한다")
	void emptyRequirements() {
		var empty = TransitionFacilityRequirements.of(Map.of());
		assertThat(empty.isPresent()).isTrue();
		assertThat(empty.requirementsByTransition()).isEmpty();
		assertThat(empty.segmentsForTransition("any")).isEmpty();
		assertThat(empty.segmentsForTransition(null)).isEmpty();
	}

	@Test
	@DisplayName("유효한 매핑으로 생성된 요구사항은 불변 복사본을 보관하고 세그먼트 조회가 정상 동작한다")
	void validRequirements() {
		var reqs = TransitionFacilityRequirements.of(Map.of(
			"t1", List.of(Set.of("f1", "f2"), Set.of("f3"))
		));
		assertThat(reqs.isPresent()).isTrue();
		assertThat(reqs.requirementsByTransition()).hasSize(1);
		assertThat(reqs.segmentsForTransition("t1")).hasSize(2);
		assertThat(reqs.segmentsForTransition("t1").get(0)).containsExactlyInAnyOrder("f1", "f2");
		assertThat(reqs.segmentsForTransition("t1").get(1)).containsExactly("f3");
		assertThat(reqs.segmentsForTransition("unknown")).isEmpty();
		assertThat(reqs.segmentsForTransition(null)).isEmpty();

		assertThatThrownBy(() -> reqs.requirementsByTransition().put("t2", List.of()))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	@DisplayName("null 입력 시 NullPointerException을 던진다")
	void nullChecks() {
		assertThatThrownBy(() -> TransitionFacilityRequirements.of(null))
			.isInstanceOf(NullPointerException.class);
	}
}

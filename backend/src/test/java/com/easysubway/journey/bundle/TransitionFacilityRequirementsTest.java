package com.easysubway.journey.bundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.journey.bundle.TransitionFacilityRequirements.Requirement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("번들 전환-시설 요구 매핑")
class TransitionFacilityRequirementsTest {

	private static final String EXIT = TransitionFacilityRequirements.EXIT_ELEVATORS;
	private static final String DIRECTION = TransitionFacilityRequirements.PLATFORM_DIRECTION_ELEVATORS;

	@Test
	@DisplayName("매핑 없음은 전환 키가 없는 명시적 상태이고, 빈 매핑과 구분된다")
	void missingMappingIsDistinctFromEmptyMapping() {
		assertThat(TransitionFacilityRequirements.missing().present()).isFalse();
		assertThat(TransitionFacilityRequirements.missing().transitionKeys()).isEmpty();
		assertThat(TransitionFacilityRequirements.of(List.of()).present()).isTrue();
	}

	@Test
	@DisplayName("요구 행을 전환 키별로 묶고, 요구 행이 없는 전환은 빈 목록을 낸다")
	void groupsRowsByTransitionKey() {
		var rows = new ArrayList<Requirement>(List.of(
			new Requirement("exit-b", "path-1", "station-a", EXIT, "smrt-elev:0201:2:9번 출입구"),
			new Requirement("entry-b", "path-1", "station-a", EXIT, "smrt-elev:0201:2:9번 출입구"),
			new Requirement("exit-b", "path-1", "station-a", DIRECTION, "smrt-elev:0201:2:가역 방면1-1")));
		var requirements = TransitionFacilityRequirements.of(rows);
		rows.clear();

		assertThat(requirements.transitionKeys()).containsExactly("entry-b", "exit-b");
		assertThat(requirements.requirementsFor("exit-b")).extracting(Requirement::facilityId)
			.containsExactly("smrt-elev:0201:2:9번 출입구", "smrt-elev:0201:2:가역 방면1-1");
		assertThat(requirements.requirementsFor("entry-a")).isEmpty();
		assertThatThrownBy(() -> requirements.requirementsFor("exit-b").clear())
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	@DisplayName("빈 값이나 계약 밖 group_kind 행은 만들 수 없다")
	void rejectsBlankFieldsAndUnknownGroupKinds() {
		assertThatThrownBy(() -> new Requirement(" ", "path-1", "station-a", EXIT, "smrt-elev:1"))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("transition_key");
		assertThatThrownBy(() -> new Requirement("exit-b", null, "station-a", EXIT, "smrt-elev:1"))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("path_id");
		assertThatThrownBy(() -> new Requirement("exit-b", "path-1", "", EXIT, "smrt-elev:1"))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("direction_next_station_id");
		assertThatThrownBy(() -> new Requirement("exit-b", "path-1", "station-a", "ESCALATORS", "smrt-elev:1"))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("group_kind");
		assertThatThrownBy(() -> new Requirement("exit-b", "path-1", "station-a", EXIT, " "))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("facility_id");
	}
}

package com.easysubway.transit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("쉬운 내부 구조도")
class SimplifiedStationLayoutTest {

	@Test
	@DisplayName("필수 문자열과 기준 자료 식별자는 공백을 정리해 저장한다")
	void trimsRequiredTextAndSourceIds() {
		var layout = new SimplifiedStationLayout(
			" layout-sangnoksu-draft ",
			" station-sangnoksu ",
			1,
			SimplifiedStationLayoutStatus.DRAFT,
			List.of(" layout-source-sangnoksu-station-map "),
			SimplifiedStationLayoutConfidence.OFFICIAL_DIAGRAM_REFERENCED,
			" B1 ",
			" {\"nodes\":[],\"edges\":[]} ",
			null,
			" admin-user ",
			null,
			null,
			LocalDate.of(2026, 6, 12)
		);

		assertThat(layout.id()).isEqualTo("layout-sangnoksu-draft");
		assertThat(layout.stationId()).isEqualTo("station-sangnoksu");
		assertThat(layout.sourceIds()).containsExactly("layout-source-sangnoksu-station-map");
		assertThat(layout.baseFloor()).isEqualTo("B1");
		assertThat(layout.layoutJson()).isEqualTo("{\"nodes\":[],\"edges\":[]}");
		assertThat(layout.createdBy()).isEqualTo("admin-user");
	}

	@Test
	@DisplayName("버전은 1 이상이어야 한다")
	void rejectsVersionLowerThanOne() {
		assertThatThrownBy(() -> layoutWithVersion(0))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("version must be greater than zero.");
	}

	@Test
	@DisplayName("기준 자료 식별자가 없으면 구조도를 만들 수 없다")
	void rejectsEmptySourceIds() {
		assertThatThrownBy(() -> new SimplifiedStationLayout(
			"layout-sangnoksu-draft",
			"station-sangnoksu",
			1,
			SimplifiedStationLayoutStatus.DRAFT,
			List.of(),
			SimplifiedStationLayoutConfidence.OFFICIAL_DIAGRAM_REFERENCED,
			"B1",
			"{\"nodes\":[],\"edges\":[]}",
			null,
			"admin-user",
			null,
			null,
			LocalDate.of(2026, 6, 12)
		))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("sourceIds must not be empty.");
	}

	private SimplifiedStationLayout layoutWithVersion(int version) {
		return new SimplifiedStationLayout(
			"layout-sangnoksu-draft",
			"station-sangnoksu",
			version,
			SimplifiedStationLayoutStatus.DRAFT,
			List.of("layout-source-sangnoksu-station-map"),
			SimplifiedStationLayoutConfidence.OFFICIAL_DIAGRAM_REFERENCED,
			"B1",
			"{\"nodes\":[],\"edges\":[]}",
			null,
			"admin-user",
			null,
			null,
			LocalDate.of(2026, 6, 12)
		);
	}
}

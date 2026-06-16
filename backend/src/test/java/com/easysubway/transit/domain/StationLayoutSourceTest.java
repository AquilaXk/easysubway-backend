package com.easysubway.transit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("역 내부 구조도 기준 자료")
class StationLayoutSourceTest {

	@Test
	@DisplayName("필수 문자열은 공백을 정리해 저장한다")
	void trimsRequiredTextFields() {
		var source = new StationLayoutSource(
			" layout-source-1 ",
			" station-sangnoksu ",
			StationLayoutSourceType.OPERATOR_DIAGRAM,
			" 상록수역 역사 안내도 ",
			" https://www.seoulmetro.co.kr ",
			" 운영기관 안내도 확인용 ",
			false,
			true,
			LocalDate.of(2026, 6, 12),
			LocalDate.of(2026, 6, 13)
		);

		assertThat(source.id()).isEqualTo("layout-source-1");
		assertThat(source.stationId()).isEqualTo("station-sangnoksu");
		assertThat(source.sourceName()).isEqualTo("상록수역 역사 안내도");
		assertThat(source.sourceUrl()).isEqualTo("https://www.seoulmetro.co.kr");
		assertThat(source.license()).isEqualTo("운영기관 안내도 확인용");
	}

	@Test
	@DisplayName("필수 필드가 비어 있으면 기준 자료를 만들 수 없다")
	void rejectsBlankRequiredFields() {
		assertThatThrownBy(() -> sourceWithBlankId())
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("id must not be blank.");
	}

	@Test
	@DisplayName("검수일은 수집일보다 빠를 수 없다")
	void rejectsReviewedAtBeforeCapturedAt() {
		assertThatThrownBy(() -> new StationLayoutSource(
			"layout-source-1",
			"station-sangnoksu",
			StationLayoutSourceType.OPERATOR_DIAGRAM,
			"상록수역 역사 안내도",
			"https://www.seoulmetro.co.kr",
			"운영기관 안내도 확인용",
			false,
			true,
			LocalDate.of(2026, 6, 12),
			LocalDate.of(2026, 6, 11)
		))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("reviewedAt must not be before capturedAt.");
	}

	private StationLayoutSource sourceWithBlankId() {
		return new StationLayoutSource(
			" ",
			"station-sangnoksu",
			StationLayoutSourceType.OPERATOR_DIAGRAM,
			"상록수역 역사 안내도",
			"https://www.seoulmetro.co.kr",
			"운영기관 안내도 확인용",
			false,
			true,
			LocalDate.of(2026, 6, 12),
			LocalDate.of(2026, 6, 12)
		);
	}
}

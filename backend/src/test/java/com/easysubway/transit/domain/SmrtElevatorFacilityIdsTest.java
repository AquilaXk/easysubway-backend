package com.easysubway.transit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * data 레포 {@code tools/datapack/build-station-elevator-paths.mjs}(AquilaXk/easysubway-data#835)의
 * {@code normalizeElevatorLocation}·{@code parseElevatorLocation}·{@code buildSmrtElevatorFacilityId}와 바이트 동일한
 * 시설 id를 만드는지 고정하는 계약 테스트다. 기대값은 data 레포 테스트
 * ({@code build-station-elevator-paths.test.mjs})의 사례를 손으로 옮기고, JS {@code \s}·{@code trim}·{@code split}
 * 의미가 Java 기본 의미와 갈리는 경계 사례를 더했다.
 */
@DisplayName("smrt-elev 시설 id 규칙(data#835 계약)")
class SmrtElevatorFacilityIdsTest {

	@Test
	@DisplayName("data#835 사례: 공백을 접어 id를 만든다")
	void buildsIdentifierFromDataRepositoryCase() {
		assertThat(SmrtElevatorFacilityIds.build("0201", "2호선", "  9번   출입구 "))
			.contains("smrt-elev:0201:2:9번 출입구");
		assertThat(SmrtElevatorFacilityIds.build("0201", "2호선", "9,10번 출입구 사이"))
			.contains("smrt-elev:0201:2:9,10번 출입구 사이");
		assertThat(SmrtElevatorFacilityIds.build("0201", "2호선", "나역 방면 5-1, 다역 방면2-2"))
			.contains("smrt-elev:0201:2:나역 방면 5-1, 다역 방면2-2");
		assertThat(SmrtElevatorFacilityIds.build("0201", "4호선", "나역 방면1-1"))
			.contains("smrt-elev:0201:4:나역 방면1-1");
		assertThat(SmrtElevatorFacilityIds.build("0701", "7호선", "1번 출입구"))
			.contains("smrt-elev:0701:7:1번 출입구");
	}

	@Test
	@DisplayName("data#835 사례: 같은 위치를 다르게 띄어 쓴 두 행은 같은 id가 된다")
	void differentlySpacedLocationsCollapseToSameIdentifier() {
		assertThat(SmrtElevatorFacilityIds.build("0201", "2호선", " 3번  출입구"))
			.isEqualTo(SmrtElevatorFacilityIds.build("0201", "2호선", "3번 출입구"))
			.contains("smrt-elev:0201:2:3번 출입구");
	}

	@Test
	@DisplayName("data#835 사례: 출입구·방면 문법 밖 위치는 식별 불가다")
	void locationsOutsideGrammarAreUnidentifiable() {
		assertThat(SmrtElevatorFacilityIds.isIdentifiableLocation("9번 출입구")).isTrue();
		assertThat(SmrtElevatorFacilityIds.isIdentifiableLocation("10,9번 출입구 사이")).isTrue();
		assertThat(SmrtElevatorFacilityIds.isIdentifiableLocation("나역 방면 5-1, 다역 방면2-2")).isTrue();
		assertThat(SmrtElevatorFacilityIds.isIdentifiableLocation("동대문(1) 방면2-3")).isTrue();
		for (String value : new String[] {
			"대합실",
			"9번 출입구(대합실 내)",
			"신내, 봉화산 방면4-1",
			"명일 방면1-1, 2-2 사이",
			"환승통로(나역 방면2-3)",
			"9-1번 출입구"
		}) {
			assertThat(SmrtElevatorFacilityIds.isIdentifiableLocation(value)).as(value).isFalse();
			assertThat(SmrtElevatorFacilityIds.build("0201", "2호선", value)).as(value).isEmpty();
		}
	}

	@Test
	@DisplayName("data#835 사례: 노선명이 'N호선'이 아니거나 역코드가 4자리 숫자가 아니면 식별 불가다")
	void lineOrStationCodeOutsideFormatIsUnidentifiable() {
		assertThat(SmrtElevatorFacilityIds.build("4201", "공항철도", "1번 출입구")).isEmpty();
		assertThat(SmrtElevatorFacilityIds.build("0201", "수도권 2호선", "1번 출입구")).isEmpty();
		assertThat(SmrtElevatorFacilityIds.build("201", "2호선", "1번 출입구")).isEmpty();
		assertThat(SmrtElevatorFacilityIds.build("0201", "0호선", "1번 출입구")).isEmpty();
		assertThat(SmrtElevatorFacilityIds.build("02O1", "2호선", "1번 출입구")).isEmpty();
		assertThat(SmrtElevatorFacilityIds.isStationLineIdentifiable("0201", "2호선")).isTrue();
		assertThat(SmrtElevatorFacilityIds.isStationLineIdentifiable("0201", "공항철도")).isFalse();
	}

	@Test
	@DisplayName("역코드·노선명은 JS trim 뒤에 검사하고 위치는 NFKC 정규화 뒤 공백을 한 칸으로 접는다")
	void trimsStationAndLineAndNormalizesLocationWithNfkc() {
		assertThat(SmrtElevatorFacilityIds.build(" 0201 ", "　2호선 ", "９번　출입구"))
			.contains("smrt-elev:0201:2:9번 출입구");
		assertThat(SmrtElevatorFacilityIds.normalizeLocation("﻿ 나역 방면\t5-1  "))
			.isEqualTo("나역 방면 5-1");
	}

	@Test
	@DisplayName("JS \\s에 들지 않는 U+0085·U+180E는 공백으로 접지 않는다")
	void doesNotTreatNonJavaScriptWhitespaceAsWhitespace() {
		assertThat(SmrtElevatorFacilityIds.normalizeLocation("9번\u0085출입구")).isEqualTo("9번\u0085출입구");
		assertThat(SmrtElevatorFacilityIds.isIdentifiableLocation("9번 출입구\u0085")).isFalse();
		assertThat(SmrtElevatorFacilityIds.isIdentifiableLocation("나역᠎방면1-1")).isTrue();
		assertThat(SmrtElevatorFacilityIds.normalizeLocation("나역᠎방면1-1")).isEqualTo("나역᠎방면1-1");
	}

	@Test
	@DisplayName("JS split처럼 끝의 빈 방면 항목도 남겨 식별 불가로 본다")
	void keepsTrailingEmptyDirectionItemLikeJavaScriptSplit() {
		assertThat(SmrtElevatorFacilityIds.isIdentifiableLocation("나역 방면1-1,")).isFalse();
		assertThat(SmrtElevatorFacilityIds.isIdentifiableLocation(", 나역 방면1-1")).isFalse();
		assertThat(SmrtElevatorFacilityIds.isIdentifiableLocation("나역 방면1-1 , 다역 방면2-2")).isTrue();
		assertThat(SmrtElevatorFacilityIds.isIdentifiableLocation("")).isFalse();
		assertThat(SmrtElevatorFacilityIds.isIdentifiableLocation("   ")).isFalse();
	}

	@Test
	@DisplayName("정규 id만 관리자 확인 대상 시설로 받아들인다")
	void acceptsOnlyCanonicalIdentifiers() {
		assertThat(SmrtElevatorFacilityIds.isCanonical("smrt-elev:0201:2:9번 출입구")).isTrue();
		assertThat(SmrtElevatorFacilityIds.isCanonical("smrt-elev:0201:2:나역 방면 5-1, 다역 방면2-2")).isTrue();
		assertThat(SmrtElevatorFacilityIds.isCanonical("smrt-elev:0201:2:9번  출입구")).isFalse();
		assertThat(SmrtElevatorFacilityIds.isCanonical("smrt-elev:0201:2:대합실")).isFalse();
		assertThat(SmrtElevatorFacilityIds.isCanonical("smrt-elev:201:2:9번 출입구")).isFalse();
		assertThat(SmrtElevatorFacilityIds.isCanonical("smrt-elev:0201:0:9번 출입구")).isFalse();
		assertThat(SmrtElevatorFacilityIds.isCanonical("kric-elev:0201:2:9번 출입구")).isFalse();
		assertThat(SmrtElevatorFacilityIds.isCanonical("smrt-elev:0201:2")).isFalse();
		assertThat(SmrtElevatorFacilityIds.isCanonical(null)).isFalse();
	}

	@Test
	@DisplayName("null 입력은 식별 규칙에 넣지 않는다")
	void rejectsNullInputs() {
		assertThatThrownBy(() -> SmrtElevatorFacilityIds.normalizeLocation(null))
			.isInstanceOf(NullPointerException.class);
		assertThat(SmrtElevatorFacilityIds.build(null, "2호선", "9번 출입구")).isEqualTo(Optional.empty());
		assertThat(SmrtElevatorFacilityIds.build("0201", null, "9번 출입구")).isEmpty();
		assertThat(SmrtElevatorFacilityIds.build("0201", "2호선", null)).isEmpty();
	}
}

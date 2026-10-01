package com.easysubway.transit.adapter.out.seoul;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.transit.adapter.out.seoul.SeoulMetroElevatorFeed.Classification;
import com.easysubway.transit.adapter.out.seoul.SeoulMetroElevatorFeed.UnidentifiableReason;
import com.easysubway.transit.application.port.out.FacilityOperationalStatusStore.FeedObservation;
import com.easysubway.transit.domain.FacilityOperationalState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("서울교통공사 getFcElvtr 행 분류")
class SeoulMetroElevatorFeedTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Test
	@DisplayName("코드 매핑: M은 가동, S·T·I·B는 불가(원천 코드 유지), D는 시설 목록에서 제외")
	void mapsOperationCodes() {
		Classification result = SeoulMetroElevatorFeed.classify(List.of(
			row("0201", "2호선", "1번 출입구", "M"),
			row("0201", "2호선", "2번 출입구", "S"),
			row("0201", "2호선", "3번 출입구", "T"),
			row("0201", "2호선", "4번 출입구", "I"),
			row("0201", "2호선", "5번 출입구", "B"),
			row("0201", "2호선", "6번 출입구", "D")
		));

		assertThat(result.observations()).containsExactly(
			new FeedObservation("smrt-elev:0201:2:1번 출입구", FacilityOperationalState.OPERATING, "M"),
			new FeedObservation("smrt-elev:0201:2:2번 출입구", FacilityOperationalState.OUT_OF_SERVICE, "S"),
			new FeedObservation("smrt-elev:0201:2:3번 출입구", FacilityOperationalState.OUT_OF_SERVICE, "T"),
			new FeedObservation("smrt-elev:0201:2:4번 출입구", FacilityOperationalState.OUT_OF_SERVICE, "I"),
			new FeedObservation("smrt-elev:0201:2:5번 출입구", FacilityOperationalState.OUT_OF_SERVICE, "B")
		);
		assertThat(result.facilitiesByCode()).containsExactly(
			Map.entry("M", 1), Map.entry("S", 1), Map.entry("T", 1), Map.entry("I", 1),
			Map.entry("B", 1), Map.entry("D", 1), Map.entry("UNKNOWN", 0)
		);
		assertThat(result.unknownCodeFacilities()).isZero();
	}

	@Test
	@DisplayName("알 수 없는 코드·빈 코드는 그 시설 상태를 만들지 않고 따로 센다")
	void unknownOrMissingCodeProducesNoObservation() {
		JsonNode missingCode = MAPPER.createObjectNode()
			.put("stnCd", "0201").put("stnNm", "가역").put("lineNm", "2호선").put("dtlPstn", "3번 출입구");
		Classification result = SeoulMetroElevatorFeed.classify(List.of(
			row("0201", "2호선", "1번 출입구", "X"),
			row("0201", "2호선", "2번 출입구", " "),
			missingCode,
			row("0201", "2호선", "4번 출입구", " M ")
		));

		assertThat(result.observations()).containsExactly(
			new FeedObservation("smrt-elev:0201:2:4번 출입구", FacilityOperationalState.OPERATING, "M")
		);
		assertThat(result.unknownCodeFacilities()).isEqualTo(3);
		assertThat(result.facilitiesByCode()).containsEntry("UNKNOWN", 3).containsEntry("M", 1);
	}

	@Test
	@DisplayName("data#835와 같은 사유로 식별 불가 행을 빼고 사유별로 센다")
	void countsUnidentifiableRowsByReason() {
		Classification result = SeoulMetroElevatorFeed.classify(List.of(
			row("0201", "2호선", "9번 출입구", "M"),
			row("0201", "2호선", "9,10번 출입구 사이", "S"),
			row("0201", "2호선", "나역 방면2-3", "M"),
			row("0201", "2호선", "나역 방면 5-1, 다역 방면2-2", "M"),
			row("0201", "2호선", "대합실", "S"),
			row("0201", "2호선", "환승통로(나역 방면2-3)", "M"),
			row("0201", "2호선", "3번 출입구", "S"),
			row("0201", "2호선", " 3번  출입구", "M"),
			row("0201", "4호선", "9번 출입구", "M"),
			row("0201", "4호선", "나역 방면1-1", "M"),
			row("0701", "7호선", "1번 출입구", "M"),
			row("4201", "공항철도", "1번 출입구", "M")
		));

		assertThat(result.observations()).extracting(FeedObservation::facilityId).containsExactly(
			"smrt-elev:0201:2:9번 출입구",
			"smrt-elev:0201:2:9,10번 출입구 사이",
			"smrt-elev:0201:2:나역 방면2-3",
			"smrt-elev:0201:2:나역 방면 5-1, 다역 방면2-2",
			"smrt-elev:0201:4:9번 출입구",
			"smrt-elev:0201:4:나역 방면1-1",
			"smrt-elev:0701:7:1번 출입구"
		);
		assertThat(result.unidentifiable()).containsExactly(
			Map.entry(UnidentifiableReason.LINE_OR_STATION_CODE, 1),
			Map.entry(UnidentifiableReason.LOCATION_FORMAT, 2),
			Map.entry(UnidentifiableReason.DUPLICATE, 2)
		);
		assertThat(result.unidentifiableTotal()).isEqualTo(5);
	}

	@Test
	@DisplayName("D 행은 중복 판정에서도 빠진다")
	void deletedRowsDoNotCreateDuplicates() {
		Classification result = SeoulMetroElevatorFeed.classify(List.of(
			row("0201", "2호선", "3번 출입구", "D"),
			row("0201", "2호선", " 3번 출입구", "S")
		));

		assertThat(result.observations()).containsExactly(
			new FeedObservation("smrt-elev:0201:2:3번 출입구", FacilityOperationalState.OUT_OF_SERVICE, "S")
		);
		assertThat(result.unidentifiableTotal()).isZero();
	}

	@Test
	@DisplayName("중복 id는 코드가 알 수 없어도 중복으로만 센다")
	void duplicateRowsWithUnknownCodeCountAsDuplicate() {
		Classification result = SeoulMetroElevatorFeed.classify(List.of(
			row("0201", "2호선", "3번 출입구", "X"),
			row("0201", "2호선", "3번 출입구", "M")
		));

		assertThat(result.observations()).isEmpty();
		assertThat(result.unidentifiable()).containsEntry(UnidentifiableReason.DUPLICATE, 2);
		assertThat(result.unknownCodeFacilities()).isZero();
	}

	@Test
	@DisplayName("필수 필드가 없거나 문자열이 아니거나 비어 있으면 응답 형식 오류다")
	void rejectsMalformedRows() {
		for (String field : new String[] {"stnCd", "stnNm", "lineNm", "dtlPstn"}) {
			var missing = row("0201", "2호선", "1번 출입구", "M");
			missing.remove(field);
			assertMalformed(missing, field);
			var blank = row("0201", "2호선", "1번 출입구", "M");
			blank.put(field, " 　");
			assertMalformed(blank, field);
			var number = row("0201", "2호선", "1번 출입구", "M");
			number.put(field, 201);
			assertMalformed(number, field);
		}
		var numericCode = row("0201", "2호선", "1번 출입구", "M");
		numericCode.put("oprtngSitu", 1);
		assertMalformed(numericCode, "oprtngSitu");
		assertThatThrownBy(() -> SeoulMetroElevatorFeed.classify(List.of(MAPPER.createArrayNode())))
			.isInstanceOf(SeoulMetroElevatorFeedException.class)
			.hasMessage("MALFORMED_ROW");
	}

	@Test
	@DisplayName("null 코드는 빈 코드처럼 알 수 없는 코드로 센다")
	void nullCodeCountsAsUnknown() {
		var nullCode = row("0201", "2호선", "1번 출입구", "M");
		nullCode.putNull("oprtngSitu");

		Classification result = SeoulMetroElevatorFeed.classify(List.of(nullCode));

		assertThat(result.observations()).isEmpty();
		assertThat(result.unknownCodeFacilities()).isEqualTo(1);
	}

	@Test
	@DisplayName("분류 결과 목록은 바꿀 수 없다")
	void classificationIsImmutable() {
		Classification result = SeoulMetroElevatorFeed.classify(new ArrayList<>(List.of(row("0201", "2호선", "1번 출입구", "M"))));

		assertThatThrownBy(() -> result.observations().clear()).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> result.facilitiesByCode().clear()).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> result.unidentifiable().clear()).isInstanceOf(UnsupportedOperationException.class);
	}

	private static void assertMalformed(JsonNode row, String field) {
		assertThatThrownBy(() -> SeoulMetroElevatorFeed.classify(List.of(row)))
			.as(field)
			.isInstanceOf(SeoulMetroElevatorFeedException.class)
			.hasMessage("MALFORMED_ROW");
	}

	private static com.fasterxml.jackson.databind.node.ObjectNode row(
		String stationCode,
		String lineName,
		String location,
		String code
	) {
		return MAPPER.createObjectNode()
			.put("stnCd", stationCode)
			.put("stnNm", "가역")
			.put("lineNm", lineName)
			.put("dtlPstn", location)
			.put("oprtngSitu", code);
	}
}

package com.easysubway.transit.adapter.out.seoul;

import com.easysubway.transit.application.port.out.FacilityOperationalStatusStore.FeedObservation;
import com.easysubway.transit.domain.FacilityOperationalState;
import com.easysubway.transit.domain.SmrtElevatorFacilityIds;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 서울교통공사 {@code getFcElvtr} 행을 시설 운영 상태 관측으로 분류한다(#419).
 *
 * <p>코드 매핑(#403 QA 결정): {@code M} 사용가능 → {@link FacilityOperationalState#OPERATING},
 * {@code S} 보수중·{@code T} 중지·{@code I} 점검중·{@code B} 공사중 → {@link FacilityOperationalState#OUT_OF_SERVICE}
 * (원천 코드는 그대로 남긴다), {@code D} 삭제 → 시설 목록에서 제외. 그 밖의 코드나 빈 코드는 상태를 만들지 않고 센다.
 *
 * <p>식별(data#835와 같은 순서): D 행을 뺀 뒤 역코드·노선 형식 → 위치 문법 → 같은 id 중복 순으로 거른다. 중복 id는
 * 순번으로 가르지 않고 모두 식별 불가로 뺀다. 필수 필드({@code stnCd}·{@code stnNm}·{@code lineNm}·{@code dtlPstn})가
 * 없거나 비었거나 코드가 문자열이 아니면 응답 형식 오류라 회차 전체를 반영하지 않는다.
 */
final class SeoulMetroElevatorFeed {

	static final String UNKNOWN_CODE = "UNKNOWN";
	static final List<String> REPORTED_CODES = List.of("M", "S", "T", "I", "B", "D", UNKNOWN_CODE);
	private static final String DELETED_CODE = "D";
	private static final String MALFORMED_ROW = "MALFORMED_ROW";
	private static final Pattern EDGE_WHITESPACE = Pattern.compile(
		"(?:^[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+)"
			+ "|(?:[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+\\z)"
	);
	private static final Map<String, FacilityOperationalState> STATES = Map.of(
		"M", FacilityOperationalState.OPERATING,
		"S", FacilityOperationalState.OUT_OF_SERVICE,
		"T", FacilityOperationalState.OUT_OF_SERVICE,
		"I", FacilityOperationalState.OUT_OF_SERVICE,
		"B", FacilityOperationalState.OUT_OF_SERVICE
	);

	enum UnidentifiableReason {
		LINE_OR_STATION_CODE,
		LOCATION_FORMAT,
		DUPLICATE
	}

	record Classification(
		List<FeedObservation> observations,
		Map<String, Integer> facilitiesByCode,
		Map<UnidentifiableReason, Integer> unidentifiable,
		int unknownCodeFacilities
	) {

		Classification {
			observations = List.copyOf(observations);
			facilitiesByCode = Collections.unmodifiableMap(new LinkedHashMap<>(facilitiesByCode));
			unidentifiable = Collections.unmodifiableMap(new EnumMap<>(unidentifiable));
		}

		int unidentifiableTotal() {
			return unidentifiable.values().stream().mapToInt(Integer::intValue).sum();
		}
	}

	private SeoulMetroElevatorFeed() {
	}

	static Classification classify(List<JsonNode> rows) {
		Map<String, Integer> facilitiesByCode = new LinkedHashMap<>();
		REPORTED_CODES.forEach(code -> facilitiesByCode.put(code, 0));
		Map<UnidentifiableReason, Integer> unidentifiable = new EnumMap<>(UnidentifiableReason.class);
		for (UnidentifiableReason reason : UnidentifiableReason.values()) {
			unidentifiable.put(reason, 0);
		}
		List<IdentifiedRow> identified = new ArrayList<>();
		Map<String, Integer> idCounts = new HashMap<>();
		for (JsonNode row : rows) {
			String code = code(row);
			facilitiesByCode.merge(facilitiesByCode.containsKey(code) ? code : UNKNOWN_CODE, 1, Integer::sum);
			String stationCode = requiredText(row, "stnCd");
			requiredText(row, "stnNm");
			String lineName = requiredText(row, "lineNm");
			String location = requiredText(row, "dtlPstn");
			if (DELETED_CODE.equals(code)) {
				continue;
			}
			if (!SmrtElevatorFacilityIds.isStationLineIdentifiable(stationCode, lineName)) {
				unidentifiable.merge(UnidentifiableReason.LINE_OR_STATION_CODE, 1, Integer::sum);
				continue;
			}
			var facilityId = SmrtElevatorFacilityIds.build(stationCode, lineName, location);
			if (facilityId.isEmpty()) {
				unidentifiable.merge(UnidentifiableReason.LOCATION_FORMAT, 1, Integer::sum);
				continue;
			}
			identified.add(new IdentifiedRow(facilityId.get(), code));
			idCounts.merge(facilityId.get(), 1, Integer::sum);
		}
		List<FeedObservation> observations = new ArrayList<>();
		int unknownCodeFacilities = 0;
		for (IdentifiedRow row : identified) {
			FacilityOperationalState state = STATES.get(row.code());
			if (idCounts.get(row.facilityId()) > 1) {
				unidentifiable.merge(UnidentifiableReason.DUPLICATE, 1, Integer::sum);
			} else if (state == null) {
				unknownCodeFacilities++;
			} else {
				observations.add(new FeedObservation(row.facilityId(), state, row.code()));
			}
		}
		return new Classification(observations, facilitiesByCode, unidentifiable, unknownCodeFacilities);
	}

	private static String code(JsonNode row) {
		if (!row.isObject()) {
			throw new SeoulMetroElevatorFeedException(MALFORMED_ROW);
		}
		JsonNode code = row.path("oprtngSitu");
		if (code.isMissingNode() || code.isNull()) {
			return "";
		}
		if (!code.isTextual()) {
			throw new SeoulMetroElevatorFeedException(MALFORMED_ROW);
		}
		return jsTrim(code.textValue());
	}

	private static String requiredText(JsonNode row, String field) {
		JsonNode value = row.path(field);
		if (!value.isTextual() || jsTrim(value.textValue()).isEmpty()) {
			throw new SeoulMetroElevatorFeedException(MALFORMED_ROW);
		}
		return value.textValue();
	}

	private static String jsTrim(String value) {
		return EDGE_WHITESPACE.matcher(value).replaceAll("");
	}

	private record IdentifiedRow(String facilityId, String code) {
	}
}

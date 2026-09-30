package com.easysubway.transit.domain;

import java.text.Normalizer;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 서울교통공사 엘리베이터 시설 id {@code smrt-elev:<역코드>:<노선코드>:<위치 정규화>}(#419 QA 결정 추가 2).
 *
 * <p>data 레포 {@code tools/datapack/build-station-elevator-paths.mjs}(AquilaXk/easysubway-data#835)의
 * {@code normalizeElevatorLocation}·{@code parseElevatorLocation}·{@code buildSmrtElevatorFacilityId}와 바이트 동일해야
 * 번들 시설 id와 이어진다. 그래서 JS 의미를 그대로 옮긴다.
 * <ul>
 *   <li>공백은 JS {@code \s}·{@code String.prototype.trim}과 같은 문자 집합(WhiteSpace+LineTerminator)만 쓴다.</li>
 *   <li>{@code .}는 JS처럼 {@code \n \r U+2028 U+2029}만 빼고, 끝 앵커는 줄바꿈 앞에서 멈추지 않는 {@code \z}를 쓴다.</li>
 *   <li>방면 목록 분리는 JS {@code split}처럼 끝의 빈 항목을 남긴다.</li>
 * </ul>
 */
public final class SmrtElevatorFacilityIds {

	public static final String PREFIX = "smrt-elev:";

	private static final String JS_WHITESPACE =
		"\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF";
	private static final String NON_WHITESPACE = "[^" + JS_WHITESPACE + "]";
	private static final String ANY_EXCEPT_LINE_TERMINATOR = "[^\\n\\r\\u2028\\u2029]";
	private static final Pattern WHITESPACE_RUN = Pattern.compile("[" + JS_WHITESPACE + "]+");
	private static final Pattern EDGE_WHITESPACE = Pattern.compile("^[" + JS_WHITESPACE + "]+|[" + JS_WHITESPACE + "]+\\z");
	// 출입구형: "<N>번 출입구", "<N>,<M>[,...]번 출입구", 뒤에 " 사이"까지 허용한다.
	private static final Pattern EXIT_LOCATION = Pattern.compile("^\\d+(?:,\\d+)*번 출입구(?: 사이)?\\z");
	// 방면형: "<X> 방면<N>-<M>" 항목을 쉼표로 나열한다. "방면" 앞뒤 공백은 한 칸까지 허용한다.
	private static final Pattern DIRECTION_ITEM = Pattern.compile(
		"^" + NON_WHITESPACE + "(?:" + ANY_EXCEPT_LINE_TERMINATOR + "*" + NON_WHITESPACE + ")? ?방면 ?\\d+-\\d+\\z"
	);
	private static final Pattern DIRECTION_ITEM_SEPARATOR = Pattern.compile("[" + JS_WHITESPACE + "]*,[" + JS_WHITESPACE + "]*");
	private static final Pattern SEOUL_METRO_LINE_NAME = Pattern.compile("^([1-9])호선\\z");
	private static final Pattern PROVIDER_STATION_CODE = Pattern.compile("^\\d{4}\\z");
	private static final Pattern CANONICAL_ID = Pattern.compile("^smrt-elev:(\\d{4}):([1-9]):(.+)\\z", Pattern.DOTALL);

	private SmrtElevatorFacilityIds() {
	}

	/** JS {@code value.normalize("NFKC").trim().replace(/\s+/gu, " ")}. */
	public static String normalizeLocation(String value) {
		Objects.requireNonNull(value, "value");
		String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
		return WHITESPACE_RUN.matcher(jsTrim(normalized)).replaceAll(" ");
	}

	/** data#835 {@code parseElevatorLocation(value) !== null}. */
	public static boolean isIdentifiableLocation(String value) {
		String normalized = normalizeLocation(value);
		if (EXIT_LOCATION.matcher(normalized).matches()) {
			return true;
		}
		for (String item : DIRECTION_ITEM_SEPARATOR.split(normalized, -1)) {
			if (!DIRECTION_ITEM.matcher(item).matches()) {
				return false;
			}
		}
		return true;
	}

	/** 원천 {@code stnCd}·{@code lineNm}이 id 형식(4자리 역코드, "N호선")을 따르는지. */
	public static boolean isStationLineIdentifiable(String stationCode, String lineName) {
		return lineCode(lineName).isPresent()
			&& stationCode != null
			&& PROVIDER_STATION_CODE.matcher(jsTrim(stationCode)).matches();
	}

	/**
	 * 원천 행의 {@code stnCd}·{@code lineNm}·{@code dtlPstn}으로 시설 id를 만든다. 역코드·노선·위치 형식이 문법 밖이면
	 * 식별 불가라 비어 있다.
	 */
	public static Optional<String> build(String stationCode, String lineName, String location) {
		if (location == null || !isStationLineIdentifiable(stationCode, lineName)) {
			return Optional.empty();
		}
		String trimmedLocation = jsTrim(location);
		if (!isIdentifiableLocation(trimmedLocation)) {
			return Optional.empty();
		}
		return Optional.of(PREFIX + jsTrim(stationCode) + ":" + lineCode(lineName).orElseThrow() + ":"
			+ normalizeLocation(trimmedLocation));
	}

	/** 이미 정규화된, 식별 가능한 smrt-elev id인지. */
	public static boolean isCanonical(String facilityId) {
		if (facilityId == null) {
			return false;
		}
		Matcher matcher = CANONICAL_ID.matcher(facilityId);
		if (!matcher.matches()) {
			return false;
		}
		String location = matcher.group(3);
		return location.equals(normalizeLocation(location)) && isIdentifiableLocation(location);
	}

	private static Optional<String> lineCode(String lineName) {
		if (lineName == null) {
			return Optional.empty();
		}
		Matcher matcher = SEOUL_METRO_LINE_NAME.matcher(jsTrim(lineName));
		return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
	}

	private static String jsTrim(String value) {
		return EDGE_WHITESPACE.matcher(value).replaceAll("");
	}
}

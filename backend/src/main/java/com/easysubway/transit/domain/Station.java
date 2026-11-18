package com.easysubway.transit.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

import java.util.Locale;

public record Station(
	String id,
	String nameKo,
	String nameEn,
	String region,
	BigDecimal latitude,
	BigDecimal longitude,
	DataQualityLevel dataQualityLevel,
	DataSourceType dataSourceType,
	LocalDate lastVerifiedAt,
	boolean active
) {

	private static final char[] KOREAN_INITIAL_CONSONANTS = {
		'ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ', 'ㅅ',
		'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'
	};
	private static final int HANGUL_SYLLABLE_START = 0xAC00;
	private static final int HANGUL_SYLLABLE_END = 0xD7A3;
	private static final int HANGUL_JUNG_JONG_COUNT = 21 * 28;

	public boolean matches(String keyword) {
		if (keyword == null || keyword.isBlank()) {
			return true;
		}
		String trimmedKeyword = keyword.trim();
		String normalizedKeyword = trimmedKeyword.toLowerCase(Locale.ROOT);
		return nameKo.contains(trimmedKeyword)
			|| nameEn.toLowerCase(Locale.ROOT).contains(normalizedKeyword)
			|| initialConsonantsOf(nameKo).contains(trimmedKeyword);
	}

	private static String initialConsonantsOf(String value) {
		StringBuilder initials = new StringBuilder();
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			if (isHangulSyllable(character)) {
				// 한글 완성형 음절은 유니코드 순서로 초성 인덱스를 계산할 수 있다.
				int initialIndex = (character - HANGUL_SYLLABLE_START) / HANGUL_JUNG_JONG_COUNT;
				initials.append(KOREAN_INITIAL_CONSONANTS[initialIndex]);
			} else {
				initials.append(character);
			}
		}
		return initials.toString();
	}

	private static boolean isHangulSyllable(char character) {
		return character >= HANGUL_SYLLABLE_START && character <= HANGUL_SYLLABLE_END;
	}
}

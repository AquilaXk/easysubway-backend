package com.easysubway.train.domain;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

public final class TrainSearchScopePolicy {

	private static final Set<String> SUPPORTED_TRAIN_TYPES = Set.of(
		"KTX",
		"KTX_SANCHEON",
		"SRT",
		"ITX_MAUM",
		"ITX_SAEMAEUL",
		"SAEMAEUL",
		"MUGUNGHWA",
		"NURIRO"
	);

	private TrainSearchScopePolicy() {}

	public static Set<String> supportedTrainTypes() {
		return SUPPORTED_TRAIN_TYPES;
	}

	public static String requireSupported(String trainType) {
		String normalized = normalize(trainType);
		if (!SUPPORTED_TRAIN_TYPES.contains(normalized)) {
			throw new IllegalArgumentException(
				"TRAIN_SEARCH_UNSUPPORTED_TRAIN_TYPE: 지원하지 않는 열차종입니다."
			);
		}
		return normalized;
	}

	public static <T> List<T> retainSupported(List<T> rows, Function<T, String> trainType) {
		return rows.stream()
			.filter(row -> SUPPORTED_TRAIN_TYPES.contains(normalize(trainType.apply(row))))
			.toList();
	}

	private static String normalize(String trainType) {
		return trainType == null ? "" : trainType.trim().toUpperCase(Locale.ROOT);
	}
}

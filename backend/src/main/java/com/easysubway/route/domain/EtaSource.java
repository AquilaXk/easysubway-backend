package com.easysubway.route.domain;

public enum EtaSource {
	STATIC_BACKEND_ESTIMATE,
	PLANNED,
	REALTIME,
	MIXED,
	PLANNED_WITHOUT_REALTIME;

	public static EtaSource fromStored(String value) {
		if (value == null) {
			throw new IllegalArgumentException("stored etaSource value must not be null");
		}
		if ("FALLBACK".equals(value) || "PLANNED_WITHOUT_REALTIME".equals(value)) {
			return PLANNED_WITHOUT_REALTIME;
		}
		return EtaSource.valueOf(value);
	}
}

package com.easysubway.transit.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

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

	public boolean matches(String keyword) {
		if (keyword == null || keyword.isBlank()) {
			return true;
		}
		String normalizedKeyword = keyword.trim().toLowerCase();
		return nameKo.contains(keyword.trim()) || nameEn.toLowerCase().contains(normalizedKeyword);
	}
}

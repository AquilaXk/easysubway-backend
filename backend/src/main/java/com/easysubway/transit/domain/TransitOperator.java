package com.easysubway.transit.domain;

public record TransitOperator(
	String id,
	String name,
	String region,
	String websiteUrl,
	String contactUrl,
	DataSourceType dataSourceType,
	boolean active
) {
}

package com.easysubway.route.domain;

public record RouteStep(
	int sequence,
	String title,
	String description,
	String lineId,
	String lineName,
	String fromStationId,
	String toStationId
) {
}

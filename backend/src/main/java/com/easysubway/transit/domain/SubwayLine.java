package com.easysubway.transit.domain;

public record SubwayLine(
	String id,
	String operatorId,
	String name,
	String color,
	String region,
	String lineCode,
	boolean active
) {
}

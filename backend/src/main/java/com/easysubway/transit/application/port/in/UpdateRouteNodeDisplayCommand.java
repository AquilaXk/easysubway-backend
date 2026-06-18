package com.easysubway.transit.application.port.in;

public record UpdateRouteNodeDisplayCommand(
	String stationId,
	String nodeId,
	int displayX,
	int displayY,
	String displayLabel,
	String accessibilityNote,
	String updatedBy
) {
}

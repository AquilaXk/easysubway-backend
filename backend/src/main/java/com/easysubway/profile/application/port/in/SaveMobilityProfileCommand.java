package com.easysubway.profile.application.port.in;

import com.easysubway.profile.domain.MobilityType;

public record SaveMobilityProfileCommand(
	String userId,
	MobilityType mobilityType,
	boolean avoidStairs,
	boolean requireElevator,
	boolean allowEscalator,
	boolean minimizeTransfers,
	boolean avoidLongWalks,
	boolean largeText,
	boolean highContrast,
	boolean simpleView
) {
}

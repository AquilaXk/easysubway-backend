package com.easysubway.profile.domain;

import java.time.LocalDateTime;

public record MobilityProfile(
	String userId,
	MobilityType mobilityType,
	boolean avoidStairs,
	boolean requireElevator,
	boolean allowEscalator,
	boolean minimizeTransfers,
	boolean avoidLongWalks,
	boolean largeText,
	boolean highContrast,
	boolean simpleView,
	LocalDateTime updatedAt
) {
}

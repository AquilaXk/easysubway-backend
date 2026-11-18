package com.easysubway.route.domain;

import com.easysubway.profile.domain.MobilityType;

public final class BoardingSlackPolicy {

	private BoardingSlackPolicy() {
	}

	public static int secondsFor(MobilityType mobilityType) {
		return switch (mobilityType) {
			case LUGGAGE -> 60;
			case SENIOR, PREGNANT -> 90;
			case STROLLER, TEMPORARY_INJURY -> 120;
			case WHEELCHAIR -> 180;
		};
	}
}

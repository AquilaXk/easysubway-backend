package com.easysubway.route.application.port.in;

import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.domain.ConstraintMode;
import com.easysubway.route.domain.EtaSource;
import com.easysubway.route.domain.RouteEtaOffsetBucket;
import com.easysubway.route.domain.RouteFeedbackRating;

public record SubmitRouteFeedbackCommand(
	String routeSearchId,
	String userId,
	RouteFeedbackRating rating,
	String comment,
	String itineraryId,
	MobilityType mobilityType,
	ConstraintMode constraintMode,
	EtaSource etaSource,
	RouteEtaOffsetBucket etaOffsetBucket,
	boolean etaFeedbackOptedIn
) {

	public SubmitRouteFeedbackCommand(
		String routeSearchId,
		String userId,
		RouteFeedbackRating rating,
		String comment
	) {
		this(
			routeSearchId,
			userId,
			rating,
			comment,
			"",
			null,
			null,
			null,
			RouteEtaOffsetBucket.NOT_PROVIDED,
			false
		);
	}
}

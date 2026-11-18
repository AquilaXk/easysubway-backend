package com.easysubway.route.domain;

import com.easysubway.profile.domain.MobilityType;
import java.time.LocalDateTime;

public record RouteFeedback(
	String feedbackId,
	String routeSearchId,
	String userId,
	RouteFeedbackRating rating,
	String comment,
	String itineraryId,
	MobilityType mobilityType,
	ConstraintMode constraintMode,
	EtaSource etaSource,
	RouteEtaOffsetBucket etaOffsetBucket,
	boolean etaFeedbackOptedIn,
	LocalDateTime createdAt
) {

	public RouteFeedback(
		String feedbackId,
		String routeSearchId,
		String userId,
		RouteFeedbackRating rating,
		String comment,
		LocalDateTime createdAt
	) {
		this(
			feedbackId,
			routeSearchId,
			userId,
			rating,
			comment,
			"",
			null,
			null,
			null,
			RouteEtaOffsetBucket.NOT_PROVIDED,
			false,
			createdAt
		);
	}
}

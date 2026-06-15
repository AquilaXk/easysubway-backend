package com.easysubway.route.application.port.in;

import com.easysubway.route.domain.RouteFeedbackRating;

public record SubmitRouteFeedbackCommand(
	String routeSearchId,
	String userId,
	RouteFeedbackRating rating,
	String comment
) {
}

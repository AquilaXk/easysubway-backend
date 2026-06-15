package com.easysubway.route.domain;

import java.time.LocalDateTime;

public record RouteFeedback(
	String feedbackId,
	String routeSearchId,
	String userId,
	RouteFeedbackRating rating,
	String comment,
	LocalDateTime createdAt
) {
}

package com.easysubway.ads.domain;

import java.time.LocalDateTime;

public record AdCreative(
	String id,
	String placementId,
	String imageUrl,
	String landingUrl,
	String advertiserName,
	String altText,
	LocalDateTime startsAt,
	LocalDateTime endsAt
) {
}

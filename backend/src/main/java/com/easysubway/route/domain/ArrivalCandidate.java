package com.easysubway.route.domain;

import java.time.Instant;

public record ArrivalCandidate(
	String trainNo,
	String lineId,
	String direction,
	String destination,
	int etaSeconds,
	Instant expectedArrivalAt,
	Instant providerReceivedAt,
	String servicePattern,
	ArrivalFreshness freshness,
	EtaConfidence confidence
) {
	public ArrivalCandidate(
		String trainNo,
		String lineId,
		String direction,
		String destination,
		int etaSeconds,
		Instant expectedArrivalAt,
		Instant providerReceivedAt,
		ArrivalFreshness freshness,
		EtaConfidence confidence
	) {
		this(
			trainNo,
			lineId,
			direction,
			destination,
			etaSeconds,
			expectedArrivalAt,
			providerReceivedAt,
			null,
			freshness,
			confidence
		);
	}

	public ArrivalCandidate {
		if (etaSeconds < 0) {
			throw new IllegalArgumentException("etaSeconds must be greater than or equal to zero.");
		}
		if (expectedArrivalAt == null) {
			throw new IllegalArgumentException("expectedArrivalAt is required.");
		}
		if (freshness == null) {
			throw new IllegalArgumentException("freshness is required.");
		}
		if (confidence == null) {
			throw new IllegalArgumentException("confidence is required.");
		}
	}
}

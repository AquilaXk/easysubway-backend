package com.easysubway.route.application.service;

import java.util.Objects;

public record OfficialFareQuote(
	String originStationId,
	String destinationStationId,
	String sourceId,
	String snapshotId,
	String mappingLedgerHash,
	int gnrlCardFare,
	int gnrlCashFare,
	int yungCardFare,
	int yungCashFare,
	int childCardFare,
	int childCashFare
) {
	public OfficialFareQuote {
		if (originStationId == null || originStationId.isBlank()) throw new IllegalArgumentException("originStationId must not be blank");
		if (destinationStationId == null || destinationStationId.isBlank()) throw new IllegalArgumentException("destinationStationId must not be blank");
		if (originStationId.equals(destinationStationId)) throw new IllegalArgumentException("originStationId and destinationStationId must differ");
		if (sourceId == null || sourceId.isBlank()) throw new IllegalArgumentException("sourceId must not be blank");
		if (snapshotId == null || snapshotId.isBlank()) throw new IllegalArgumentException("snapshotId must not be blank");
		Objects.requireNonNull(mappingLedgerHash, "mappingLedgerHash");
		if (gnrlCardFare < 0 || gnrlCashFare < 0 || yungCardFare < 0 || yungCashFare < 0
			|| childCardFare < 0 || childCashFare < 0) {
			throw new IllegalArgumentException("fare must not be negative");
		}
	}

	public static String fareKey(String origin, String destination) {
		if (origin == null || origin.isBlank()) throw new IllegalArgumentException("origin must not be blank");
		if (destination == null || destination.isBlank()) throw new IllegalArgumentException("destination must not be blank");
		return origin + "^@" + destination;
	}
}

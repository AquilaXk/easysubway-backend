package com.easysubway.journey.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/** Stable physical-itinerary and readiness-qualified candidate identities for Journey Profile V1. */
public final class JourneyProfileCandidateIdentity {

	private static final String PHYSICAL_VERSION = "PHYSICAL_ITINERARY_V1";
	private static final String CANDIDATE_VERSION = "JOURNEY_PROFILE_CANDIDATE_V1";
	private static final Pattern SHA_256 = Pattern.compile("^[a-f0-9]{64}$");

	private JourneyProfileCandidateIdentity() {
	}

	public static String physicalItineraryId(
		String originStationId,
		String destinationStationId,
		JourneyProfileRaptorPort.Itinerary itinerary
	) {
		String origin = requireText(originStationId, "originStationId");
		String destination = requireText(destinationStationId, "destinationStationId");
		JourneyProfileRaptorPort.Itinerary requiredItinerary = Objects.requireNonNull(itinerary, "itinerary");
		StringBuilder canonical = new StringBuilder();
		append(canonical, PHYSICAL_VERSION);
		append(canonical, requiredItinerary.serviceDate().toString());
		append(canonical, origin);
		append(canonical, destination);
		append(canonical, requiredItinerary.plannedArrivalAtDestination().toString());
		append(canonical, Integer.toString(requiredItinerary.legs().size()));
		for (JourneyProfileRaptorPort.Leg leg : requiredItinerary.legs()) {
			switch (leg) {
				case JourneyProfileRaptorPort.AccessLeg access -> appendAccess(canonical, access);
				case JourneyProfileRaptorPort.RideLeg ride -> appendRide(canonical, ride);
			}
		}
		return sha256(canonical);
	}

	public static String candidateId(String physicalItineraryId, Instant readyAt) {
		String physicalId = requireText(physicalItineraryId, "physicalItineraryId");
		if (!SHA_256.matcher(physicalId).matches()) {
			throw new IllegalArgumentException("physicalItineraryId must be lowercase SHA-256");
		}
		StringBuilder canonical = new StringBuilder();
		append(canonical, CANDIDATE_VERSION);
		append(canonical, physicalId);
		append(canonical, Objects.requireNonNull(readyAt, "readyAt").toString());
		return sha256(canonical);
	}

	private static void appendAccess(StringBuilder canonical, JourneyProfileRaptorPort.AccessLeg access) {
		append(canonical, "ACCESS");
		append(canonical, access.kind().name());
		append(canonical, access.fromStationId());
		append(canonical, access.toStationId());
		append(canonical, Integer.toString(access.durationSeconds()));
		append(canonical, Integer.toString(access.distanceMeters()));
		append(canonical, Boolean.toString(access.includesStairs()));
		append(canonical, Boolean.toString(access.verified()));
		append(canonical, access.verificationStatus());
	}

	private static void appendRide(StringBuilder canonical, JourneyProfileRaptorPort.RideLeg ride) {
		append(canonical, "RIDE");
		append(canonical, ride.lineId());
		append(canonical, ride.tripId());
		append(canonical, ride.directionStationId());
		append(canonical, ride.fromStationId());
		append(canonical, ride.toStationId());
		append(canonical, ride.plannedDepartureTime().toString());
		append(canonical, ride.plannedArrivalTime().toString());
	}

	private static void append(StringBuilder canonical, String value) {
		String required = Objects.requireNonNull(value, "canonical value");
		canonical.append(required.length()).append(':').append(required);
	}

	private static String sha256(StringBuilder canonical) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static String requireText(String value, String name) {
		Objects.requireNonNull(value, name);
		if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
		return value;
	}
}

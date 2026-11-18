package com.easysubway.journey.application;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;

/** Resolves the Journey V3 service-day contract shared by the application and planner. */
public final class ServiceDayResolver {

	public static final String TIMEZONE = "Asia/Seoul";
	public static final String CUTOFF_LOCAL_TIME = "03:00";
	public static final ZoneId ZONE = ZoneId.of(TIMEZONE);
	private static final int CUTOFF_HOUR = 3;

	private ServiceDayResolver() {
	}

	public static ResolvedServiceDay resolve(Instant instant) {
		Objects.requireNonNull(instant, "instant");
		ZonedDateTime local = instant.atZone(ZONE);
		LocalDate serviceDate = local.toLocalDate();
		if (local.getHour() < CUTOFF_HOUR) serviceDate = serviceDate.minusDays(1);
		int secondsFromServiceDayStart = Math.toIntExact(Duration.between(
			serviceDate.atStartOfDay(ZONE), local).toSeconds());
		return new ResolvedServiceDay(serviceDate, secondsFromServiceDayStart);
	}

	public record ResolvedServiceDay(LocalDate serviceDate, int secondsFromServiceDayStart) {
		public ResolvedServiceDay {
			serviceDate = Objects.requireNonNull(serviceDate, "serviceDate");
			if (secondsFromServiceDayStart < 0) {
				throw new IllegalArgumentException("secondsFromServiceDayStart must not be negative");
			}
		}
	}
}

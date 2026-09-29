package com.easysubway.route.application.service;

import com.easysubway.journey.application.JourneyRaptorRuntimeView;
import com.easysubway.journey.bundle.RouteBundleRuntimeView;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import java.util.Objects;
import java.util.regex.Pattern;

public final class RaptorRouteBundleRuntimeView implements RouteBundleRuntimeView, JourneyRaptorRuntimeView {

	private static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");

	private final String routeBundleSha256;
	private final long generation;
	private final RouteTimetableRaptorPlanner.CompiledTimetable compiledTimetable;
	private final com.easysubway.journey.bundle.TransitionFacilityRequirements facilityRequirements;

	private RaptorRouteBundleRuntimeView(
		String routeBundleSha256,
		long generation,
		RouteTimetableRaptorPlanner.CompiledTimetable compiledTimetable,
		com.easysubway.journey.bundle.TransitionFacilityRequirements facilityRequirements
	) {
		this.routeBundleSha256 = requireSha256(routeBundleSha256);
		if (generation < 1) throw new IllegalArgumentException("generation must be positive");
		this.generation = generation;
		this.compiledTimetable = Objects.requireNonNull(compiledTimetable, "compiledTimetable");
		this.facilityRequirements = facilityRequirements != null
			? facilityRequirements
			: com.easysubway.journey.bundle.TransitionFacilityRequirements.missing();
	}

	public static RaptorRouteBundleRuntimeView compile(
		String routeBundleSha256,
		long generation,
		RouteTimetable timetable
	) {
		return compile(routeBundleSha256, generation, timetable, com.easysubway.journey.bundle.TransitionFacilityRequirements.missing());
	}

	public static RaptorRouteBundleRuntimeView compile(
		String routeBundleSha256,
		long generation,
		RouteTimetable timetable,
		com.easysubway.journey.bundle.TransitionFacilityRequirements facilityRequirements
	) {
		return new RaptorRouteBundleRuntimeView(
			routeBundleSha256,
			generation,
			new RouteTimetableRaptorPlanner().compile(Objects.requireNonNull(timetable, "timetable")),
			facilityRequirements
		);
	}

	public com.easysubway.journey.bundle.TransitionFacilityRequirements facilityRequirements() {
		return facilityRequirements;
	}

	@Override
	public String routeBundleSha256() {
		return routeBundleSha256;
	}

	@Override
	public long generation() {
		return generation;
	}

	RouteTimetableRaptorPlanner.CompiledTimetable compiledTimetable() {
		return compiledTimetable;
	}

	private static String requireSha256(String value) {
		Objects.requireNonNull(value, "routeBundleSha256");
		if (!SHA256.matcher(value).matches()) {
			throw new IllegalArgumentException("routeBundleSha256 must be lowercase SHA-256");
		}
		return value;
	}
}

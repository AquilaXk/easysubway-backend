package com.easysubway.route.application.service;

import com.easysubway.journey.application.JourneyRaptorRuntimeView;
import com.easysubway.journey.bundle.RouteBundleFacilityCatalog;
import com.easysubway.journey.bundle.RouteBundleRuntimeView;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public final class RaptorRouteBundleRuntimeView
	implements RouteBundleRuntimeView, JourneyRaptorRuntimeView, RouteBundleFacilityCatalog {

	private static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");

	private final String routeBundleSha256;
	private final long generation;
	private final RouteTimetableRaptorPlanner.CompiledTimetable compiledTimetable;
	private final List<Facility> smrtElevatorFacilities;

	private RaptorRouteBundleRuntimeView(
		String routeBundleSha256,
		long generation,
		RouteTimetableRaptorPlanner.CompiledTimetable compiledTimetable,
		List<Facility> smrtElevatorFacilities
	) {
		this.routeBundleSha256 = requireSha256(routeBundleSha256);
		if (generation < 1) throw new IllegalArgumentException("generation must be positive");
		this.generation = generation;
		this.compiledTimetable = Objects.requireNonNull(compiledTimetable, "compiledTimetable");
		this.smrtElevatorFacilities = List.copyOf(smrtElevatorFacilities);
	}

	/** 시설 목록 없이 경로 탐색 런타임만 만든다. 번들 컴파일러는 아래 시설 목록 판을 쓴다. */
	public static RaptorRouteBundleRuntimeView compile(
		String routeBundleSha256,
		long generation,
		RouteTimetable timetable
	) {
		return compile(routeBundleSha256, generation, timetable, List.of());
	}

	/** #419: 번들 accessibility 구성요소의 {@code smrt-elev:} 시설 목록을 함께 담는다. */
	public static RaptorRouteBundleRuntimeView compile(
		String routeBundleSha256,
		long generation,
		RouteTimetable timetable,
		List<Facility> smrtElevatorFacilities
	) {
		return new RaptorRouteBundleRuntimeView(
			routeBundleSha256,
			generation,
			new RouteTimetableRaptorPlanner().compile(Objects.requireNonNull(timetable, "timetable")),
			Objects.requireNonNull(smrtElevatorFacilities, "smrtElevatorFacilities")
		);
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

	@Override
	public List<Facility> smrtElevatorFacilities() {
		return smrtElevatorFacilities;
	}

	private static String requireSha256(String value) {
		Objects.requireNonNull(value, "routeBundleSha256");
		if (!SHA256.matcher(value).matches()) {
			throw new IllegalArgumentException("routeBundleSha256 must be lowercase SHA-256");
		}
		return value;
	}
}

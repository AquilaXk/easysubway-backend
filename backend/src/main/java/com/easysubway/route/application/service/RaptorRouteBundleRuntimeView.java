package com.easysubway.route.application.service;

import com.easysubway.journey.application.JourneyRaptorRuntimeView;
import com.easysubway.journey.bundle.RouteBundleFacilityCatalog;
import com.easysubway.journey.bundle.RouteBundleRuntimeView;
import com.easysubway.journey.bundle.TransitionFacilityRequirementSource;
import com.easysubway.journey.bundle.TransitionFacilityRequirements;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public final class RaptorRouteBundleRuntimeView
	implements RouteBundleRuntimeView, JourneyRaptorRuntimeView, RouteBundleFacilityCatalog,
	TransitionFacilityRequirementSource {

	private static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");

	private final String routeBundleSha256;
	private final long generation;
	private final RouteTimetableRaptorPlanner.CompiledTimetable compiledTimetable;
	private final List<Facility> smrtElevatorFacilities;
	private final TransitionFacilityRequirements transitionFacilityRequirements;

	private RaptorRouteBundleRuntimeView(
		String routeBundleSha256,
		long generation,
		RouteTimetableRaptorPlanner.CompiledTimetable compiledTimetable,
		List<Facility> smrtElevatorFacilities,
		TransitionFacilityRequirements transitionFacilityRequirements
	) {
		this.routeBundleSha256 = requireSha256(routeBundleSha256);
		if (generation < 1) throw new IllegalArgumentException("generation must be positive");
		this.generation = generation;
		this.compiledTimetable = Objects.requireNonNull(compiledTimetable, "compiledTimetable");
		this.smrtElevatorFacilities = List.copyOf(smrtElevatorFacilities);
		this.transitionFacilityRequirements = requireCompiledTransitions(
			Objects.requireNonNull(transitionFacilityRequirements, "transitionFacilityRequirements"), compiledTimetable);
	}

	/** 시설 목록 없이 경로 탐색 런타임만 만든다. 번들 컴파일러는 아래 시설 목록 판을 쓴다. */
	public static RaptorRouteBundleRuntimeView compile(
		String routeBundleSha256,
		long generation,
		RouteTimetable timetable
	) {
		return compile(routeBundleSha256, generation, timetable, List.of());
	}

	/** #419: 번들 accessibility 구성요소의 {@code smrt-elev:} 시설 목록을 함께 담는다. 전환-시설 요구 매핑은 없다. */
	public static RaptorRouteBundleRuntimeView compile(
		String routeBundleSha256,
		long generation,
		RouteTimetable timetable,
		List<Facility> smrtElevatorFacilities
	) {
		return compile(routeBundleSha256, generation, timetable, smrtElevatorFacilities,
			TransitionFacilityRequirements.missing());
	}

	/**
	 * #418: 전환-시설 요구 매핑을 함께 담는다. 요구 행의 전환 키는 모두 컴파일된 전환으로 해석되어야 한다. 해석되지 않는 키가
	 * 있으면 요청 시점에 시설 뷰 전체가 쓸 수 없게 되므로 활성화 전에 번들을 거부한다.
	 */
	public static RaptorRouteBundleRuntimeView compile(
		String routeBundleSha256,
		long generation,
		RouteTimetable timetable,
		List<Facility> smrtElevatorFacilities,
		TransitionFacilityRequirements transitionFacilityRequirements
	) {
		return new RaptorRouteBundleRuntimeView(
			routeBundleSha256,
			generation,
			new RouteTimetableRaptorPlanner().compile(Objects.requireNonNull(timetable, "timetable")),
			Objects.requireNonNull(smrtElevatorFacilities, "smrtElevatorFacilities"),
			transitionFacilityRequirements
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

	@Override
	public TransitionFacilityRequirements transitionFacilityRequirements() {
		return transitionFacilityRequirements;
	}

	private static TransitionFacilityRequirements requireCompiledTransitions(
		TransitionFacilityRequirements requirements,
		RouteTimetableRaptorPlanner.CompiledTimetable compiledTimetable
	) {
		for (String transitionKey : requirements.transitionKeys()) {
			if (compiledTimetable.transitionIdsForEdge(transitionKey).length == 0) {
				throw new IllegalArgumentException(
					"transition facility requirement edge does not resolve to a compiled transition: " + transitionKey);
			}
		}
		return requirements;
	}

	private static String requireSha256(String value) {
		Objects.requireNonNull(value, "routeBundleSha256");
		if (!SHA256.matcher(value).matches()) {
			throw new IllegalArgumentException("routeBundleSha256 must be lowercase SHA-256");
		}
		return value;
	}
}

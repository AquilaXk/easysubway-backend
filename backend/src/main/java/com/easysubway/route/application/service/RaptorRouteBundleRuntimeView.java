package com.easysubway.route.application.service;

import com.easysubway.journey.application.JourneyRaptorRuntimeView;
import com.easysubway.journey.bundle.RouteBundleFacilityCatalog;
import com.easysubway.journey.application.StationTimetableIndex;
import com.easysubway.journey.application.StationTimetableSnapshotPort.StationLine;
import com.easysubway.journey.bundle.RouteBundleRuntimeView;
import com.easysubway.journey.bundle.RouteBundleStationTimetableSource;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public final class RaptorRouteBundleRuntimeView
	implements RouteBundleRuntimeView, JourneyRaptorRuntimeView, RouteBundleFacilityCatalog,
	RouteBundleStationTimetableSource {

	private static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");

	private final String routeBundleSha256;
	private final long generation;
	private final RouteTimetableRaptorPlanner.CompiledTimetable compiledTimetable;
	private final List<Facility> smrtElevatorFacilities;
	private final Map<String, OfficialFareQuote> officialFareQuotes;
	private final Set<StationLine> canonicalStationLines;
	private final Object stationTimetableIndexLock = new Object();
	private StationTimetableIndex stationTimetableIndex;

	private RaptorRouteBundleRuntimeView(
		String routeBundleSha256,
		long generation,
		RouteTimetableRaptorPlanner.CompiledTimetable compiledTimetable,
		List<Facility> smrtElevatorFacilities,
		Map<String, OfficialFareQuote> officialFareQuotes,
		Set<StationLine> canonicalStationLines
	) {
		this.routeBundleSha256 = requireSha256(routeBundleSha256);
		if (generation < 1) throw new IllegalArgumentException("generation must be positive");
		this.generation = generation;
		this.compiledTimetable = Objects.requireNonNull(compiledTimetable, "compiledTimetable");
		this.smrtElevatorFacilities = List.copyOf(smrtElevatorFacilities);
		for (var entry : Objects.requireNonNull(officialFareQuotes, "officialFareQuotes").entrySet()) {
			Objects.requireNonNull(entry.getValue(), "fareQuote");
			String expectedKey = OfficialFareQuote.fareKey(entry.getValue().originStationId(), entry.getValue().destinationStationId());
			if (!expectedKey.equals(entry.getKey())) {
				throw new IllegalArgumentException("officialFareQuotes key mismatch: expected " + expectedKey + " but got " + entry.getKey());
			}
		}
		this.officialFareQuotes = Map.copyOf(officialFareQuotes);
		this.canonicalStationLines = Set.copyOf(Objects.requireNonNull(canonicalStationLines, "canonicalStationLines"));
	}

	/** 시설 목록 없이 경로 탐색 런타임만 만든다. 번들 컴파일러는 아래 시설 목록 판을 쓴다. */
	public static RaptorRouteBundleRuntimeView compile(
		String routeBundleSha256,
		long generation,
		RouteTimetable timetable
	) {
		return compile(routeBundleSha256, generation, timetable, List.of(), Map.of());
	}

	/** 공식 운임만 담고 시설 목록 없이 만든다. */
	public static RaptorRouteBundleRuntimeView compile(
		String routeBundleSha256,
		long generation,
		RouteTimetable timetable,
		Map<String, OfficialFareQuote> officialFareQuotes
	) {
		return compile(routeBundleSha256, generation, timetable, List.of(), officialFareQuotes);
	}

	/** #419 시설 목록과 #430 공식 운임을 함께 담는다. 정본 역·노선이 없어 역 시간표는 모든 역을 찾지 못함으로 답한다. */
	public static RaptorRouteBundleRuntimeView compile(
		String routeBundleSha256,
		long generation,
		RouteTimetable timetable,
		List<Facility> smrtElevatorFacilities,
		Map<String, OfficialFareQuote> officialFareQuotes
	) {
		return compile(routeBundleSha256, generation, timetable, smrtElevatorFacilities, officialFareQuotes, Set.of());
	}

	/** #476 번들 station_lines(정본 역·노선)를 함께 담아 역 시간표가 같은 세대에서 읽게 한다. */
	public static RaptorRouteBundleRuntimeView compile(
		String routeBundleSha256,
		long generation,
		RouteTimetable timetable,
		List<Facility> smrtElevatorFacilities,
		Map<String, OfficialFareQuote> officialFareQuotes,
		Set<StationLine> canonicalStationLines
	) {
		return new RaptorRouteBundleRuntimeView(
			routeBundleSha256,
			generation,
			new RouteTimetableRaptorPlanner().compile(Objects.requireNonNull(timetable, "timetable")),
			Objects.requireNonNull(smrtElevatorFacilities, "smrtElevatorFacilities"),
			officialFareQuotes,
			canonicalStationLines
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

	public Map<String, OfficialFareQuote> officialFareQuotes() {
		return officialFareQuotes;
	}

	// #476 F3: 역 시간표 색인은 이 세대 객체에 묶어 처음 쓰일 때 한 번만 만든다. 세대가 바뀌면 새 객체가 새 색인을 만들고,
	// 이전 세대 색인은 그 세대 객체와 함께 버려진다.
	@Override
	public StationTimetableIndex stationTimetableIndex() {
		synchronized (stationTimetableIndexLock) {
			if (stationTimetableIndex == null) stationTimetableIndex = StationTimetableIndex.of(compiledTimetable.source());
			return stationTimetableIndex;
		}
	}

	@Override
	public Set<StationLine> canonicalStationLines() {
		return canonicalStationLines;
	}

	private static String requireSha256(String value) {
		Objects.requireNonNull(value, "routeBundleSha256");
		if (!SHA256.matcher(value).matches()) {
			throw new IllegalArgumentException("routeBundleSha256 must be lowercase SHA-256");
		}
		return value;
	}
}

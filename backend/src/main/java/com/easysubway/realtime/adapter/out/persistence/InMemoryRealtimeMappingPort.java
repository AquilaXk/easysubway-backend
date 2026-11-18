package com.easysubway.realtime.adapter.out.persistence;

import com.easysubway.realtime.application.RealtimeQuery;
import com.easysubway.realtime.application.port.out.RealtimeMappingPort;
import com.easysubway.realtime.domain.RealtimeMapping;
import com.easysubway.realtime.domain.RealtimeTripMapping;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!prod & !staging & !release & !prod-like")
public class InMemoryRealtimeMappingPort implements RealtimeMappingPort {

	private final List<RealtimeMapping> mappings;
	private final List<RealtimeTripMapping> tripMappings;

	public InMemoryRealtimeMappingPort() {
		this(seededMappings(), seededTripMappings());
	}

	InMemoryRealtimeMappingPort(List<RealtimeMapping> mappings) {
		this(mappings, List.of());
	}

	InMemoryRealtimeMappingPort(List<RealtimeMapping> mappings, List<RealtimeTripMapping> tripMappings) {
		this.mappings = List.copyOf(mappings);
		this.tripMappings = List.copyOf(tripMappings);
	}

	public static InMemoryRealtimeMappingPort seededFixture() {
		return new InMemoryRealtimeMappingPort(seededMappings(), seededTripMappings());
	}

	private static List<RealtimeMapping> seededMappings() {
		return List.of(new RealtimeMapping(
			"seoul-topis",
			"station-sangnoksu",
			"seoul-4",
			"1004",
			"1004000448",
			"상록수",
			"4호선",
			true,
			true,
			"OFFICIAL",
			1L
		));
	}

	private static List<RealtimeTripMapping> seededTripMappings() {
		return List.of(
			new RealtimeTripMapping(
				"seoul-topis",
				"seoul-4",
				"1004",
				"상행",
				"당고개 방면",
				"당고개",
				"당고개",
				"",
				"",
				"OFFICIAL",
				1L
			),
			new RealtimeTripMapping(
				"seoul-topis",
				"seoul-4",
				"1004",
				"하행",
				"오이도 방면",
				"오이도",
				"오이도",
				"",
				"",
				"OFFICIAL",
				1L
			)
		);
	}

	@Override
	public Optional<RealtimeMapping> findArrivalMapping(String providerId, RealtimeQuery query) {
		return mappings.stream()
			.filter((mapping) -> mapping.providerId().equals(providerId))
			.filter((mapping) -> mapping.stationId().equals(query.stationId()))
			.filter((mapping) -> mapping.matchesLine(query.lineId()))
			.findFirst();
	}

	@Override
	public Optional<RealtimeMapping> findTrainPositionMapping(String providerId, RealtimeQuery query) {
		return mappings.stream()
			.filter((mapping) -> mapping.providerId().equals(providerId))
			.filter((mapping) -> mapping.matchesLine(query.lineId()))
			.findFirst();
	}

	@Override
	public Optional<RealtimeTripMapping> findTripMapping(
		String providerId,
		String lineId,
		String providerLineId,
		String rawDirection,
		String rawDestination,
		String rawServicePattern
	) {
		return tripMappings.stream()
			.filter((mapping) -> mapping.providerId().equals(providerId))
			.filter((mapping) -> mapping.matchesLine(lineId, providerLineId))
			.filter((mapping) -> mapping.matchesRaw(rawDirection, rawDestination, rawServicePattern))
			.filter(RealtimeTripMapping::liveEligible)
			.max(Comparator.comparingInt(RealtimeTripMapping::specificity));
	}
}

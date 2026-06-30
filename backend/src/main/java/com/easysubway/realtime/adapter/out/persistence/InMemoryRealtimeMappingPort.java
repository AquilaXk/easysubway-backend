package com.easysubway.realtime.adapter.out.persistence;

import com.easysubway.realtime.application.RealtimeQuery;
import com.easysubway.realtime.application.port.out.RealtimeMappingPort;
import com.easysubway.realtime.domain.RealtimeMapping;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!prod & !staging & !release & !prod-like")
public class InMemoryRealtimeMappingPort implements RealtimeMappingPort {

	private final List<RealtimeMapping> mappings;

	public InMemoryRealtimeMappingPort() {
		this(seededMappings());
	}

	InMemoryRealtimeMappingPort(List<RealtimeMapping> mappings) {
		this.mappings = List.copyOf(mappings);
	}

	public static InMemoryRealtimeMappingPort seededFixture() {
		return new InMemoryRealtimeMappingPort(seededMappings());
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
}

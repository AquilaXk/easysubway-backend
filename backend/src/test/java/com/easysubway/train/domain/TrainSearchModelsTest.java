package com.easysubway.train.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.train.application.TrainSearchProvider.Catalog;
import com.easysubway.train.domain.TrainSearchModels.Journey;
import com.easysubway.train.domain.TrainSearchModels.SearchResult;
import com.easysubway.train.domain.TrainSearchModels.Station;
import com.easysubway.train.domain.TrainSearchModels.TrainType;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrainSearchModelsTest {

	@Test
	void catalogDefensivelyCopiesItsLists() {
		var stations = new ArrayList<>(List.of(new Station("NAT010000", "서울")));
		var trainTypes = new ArrayList<>(List.of(new TrainType("KTX", "KTX")));
		var catalog = new Catalog(Instant.parse("2026-07-19T00:00:00Z"), stations, trainTypes);

		stations.clear();
		trainTypes.clear();

		assertThat(catalog.stations()).hasSize(1);
		assertThat(catalog.trainTypes()).hasSize(1);
		assertThat(catalog.stations()).isUnmodifiable();
		assertThat(catalog.trainTypes()).isUnmodifiable();
	}

	@Test
	void searchResultDefensivelyCopiesItsLists() {
		var outbound = new ArrayList<>(List.of(journey("101")));
		var inbound = new ArrayList<>(List.of(journey("102")));
		var result = new SearchResult(OffsetDateTime.parse("2026-07-19T09:00:00+09:00"), outbound, inbound);

		outbound.clear();
		inbound.clear();

		assertThat(result.outbound()).hasSize(1);
		assertThat(result.inbound()).hasSize(1);
		assertThat(result.outbound()).isUnmodifiable();
		assertThat(result.inbound()).isUnmodifiable();
	}

	private Journey journey(String number) {
		return new Journey(
			number,
			"KTX",
			"NAT010000",
			"서울",
			OffsetDateTime.parse("2026-07-20T09:00:00+09:00"),
			"NAT011668",
			"대전",
			OffsetDateTime.parse("2026-07-20T10:00:00+09:00"),
			60,
			23_700
		);
	}
}

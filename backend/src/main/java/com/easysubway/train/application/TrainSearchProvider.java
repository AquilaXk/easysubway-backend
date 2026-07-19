package com.easysubway.train.application;

import com.easysubway.train.domain.TrainSearchModels.Journey;
import com.easysubway.train.domain.TrainSearchModels.LegQuery;
import com.easysubway.train.domain.TrainSearchModels.Station;
import com.easysubway.train.domain.TrainSearchModels.TrainType;
import java.time.Instant;
import java.util.List;

public interface TrainSearchProvider {

	Catalog catalog();

	default Catalog catalog(Instant deadline) {
		return catalog();
	}

	List<Journey> search(LegQuery query);

	default List<Journey> search(LegQuery query, Instant deadline) {
		return search(query);
	}

	record Catalog(Instant observedAt, List<Station> stations, List<TrainType> trainTypes) {
		public Catalog {
			stations = List.copyOf(stations);
			trainTypes = List.copyOf(trainTypes);
		}
	}

	final class ProviderFailure extends RuntimeException {

		public ProviderFailure(String code) {
			super(code);
		}

		public ProviderFailure(String code, Throwable cause) {
			super(code, cause);
		}
	}
}

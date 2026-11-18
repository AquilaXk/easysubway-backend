package com.easysubway.train.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class TrainSearchModels {

	private TrainSearchModels() {}

	public record Station(String id, String name) {}

	public record TrainType(String code, String name, List<String> providerCodes) {
		public TrainType {
			providerCodes = List.copyOf(providerCodes);
		}

		public TrainType(String code, String name) {
			this(code, name, List.of());
		}
	}

	public record LegQuery(
		String departureStationId,
		String arrivalStationId,
		LocalDate departureDate,
		String trainType,
		List<String> providerTrainGradeCodes,
		String departureStationName,
		String arrivalStationName
	) {
		public LegQuery {
			providerTrainGradeCodes = List.copyOf(providerTrainGradeCodes);
		}

		public LegQuery(
			String departureStationId,
			String arrivalStationId,
			LocalDate departureDate,
			String trainType
		) {
			this(departureStationId, arrivalStationId, departureDate, trainType, List.of(), null, null);
		}

		public LegQuery(
			String departureStationId,
			String arrivalStationId,
			LocalDate departureDate,
			String trainType,
			String providerTrainGradeCode
		) {
			this(
				departureStationId,
				arrivalStationId,
				departureDate,
				trainType,
				List.of(providerTrainGradeCode),
				null,
				null
			);
		}

		public LegQuery(
			String departureStationId,
			String arrivalStationId,
			LocalDate departureDate,
			String trainType,
			List<String> providerTrainGradeCodes
		) {
			this(departureStationId, arrivalStationId, departureDate, trainType, providerTrainGradeCodes, null, null);
		}
	}

	public record SearchCriteria(
		String departureStationId,
		String arrivalStationId,
		LocalDate departureDate,
		LocalDate returnDate,
		String trainType
	) {}

	public record SearchResult(
		OffsetDateTime observedAt,
		List<Journey> outbound,
		List<Journey> inbound
	) {
		public SearchResult {
			outbound = List.copyOf(outbound);
			inbound = List.copyOf(inbound);
		}
	}

	public record Journey(
		String trainNumber,
		String trainType,
		String departureStationId,
		String departureStationName,
		OffsetDateTime departureAt,
		String arrivalStationId,
		String arrivalStationName,
		OffsetDateTime arrivalAt,
		int durationMinutes,
		int adultFareWon
	) {}
}

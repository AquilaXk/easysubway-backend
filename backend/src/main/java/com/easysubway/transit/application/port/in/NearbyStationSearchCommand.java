package com.easysubway.transit.application.port.in;

import com.easysubway.transit.domain.InvalidStationSearchException;
import java.math.BigDecimal;

public record NearbyStationSearchCommand(
	BigDecimal latitude,
	BigDecimal longitude,
	int radiusMeters,
	int limit
) {

	private static final int DEFAULT_RADIUS_METERS = 2_000;
	private static final int DEFAULT_LIMIT = 10;
	private static final int MAX_RADIUS_METERS = 50_000;
	private static final int MAX_LIMIT = 50;

	public static NearbyStationSearchCommand of(
		BigDecimal latitude,
		BigDecimal longitude,
		Integer radiusMeters,
		Integer limit
	) {
		return new NearbyStationSearchCommand(
			latitude,
			longitude,
			radiusMeters == null ? DEFAULT_RADIUS_METERS : radiusMeters,
			limit == null ? DEFAULT_LIMIT : limit
		);
	}

	public NearbyStationSearchCommand {
		validateLatitude(latitude);
		validateLongitude(longitude);
		validateRadius(radiusMeters);
		validateLimit(limit);
	}

	private static void validateLatitude(BigDecimal latitude) {
		if (latitude == null) {
			throw new InvalidStationSearchException("위도가 필요합니다.");
		}
		if (latitude.compareTo(BigDecimal.valueOf(-90)) < 0 || latitude.compareTo(BigDecimal.valueOf(90)) > 0) {
			throw new InvalidStationSearchException("위도는 -90부터 90 사이여야 합니다.");
		}
	}

	private static void validateLongitude(BigDecimal longitude) {
		if (longitude == null) {
			throw new InvalidStationSearchException("경도가 필요합니다.");
		}
		if (longitude.compareTo(BigDecimal.valueOf(-180)) < 0 || longitude.compareTo(BigDecimal.valueOf(180)) > 0) {
			throw new InvalidStationSearchException("경도는 -180부터 180 사이여야 합니다.");
		}
	}

	private static void validateRadius(int radiusMeters) {
		if (radiusMeters <= 0 || radiusMeters > MAX_RADIUS_METERS) {
			throw new InvalidStationSearchException("조회 반경은 1m부터 50000m 사이여야 합니다.");
		}
	}

	private static void validateLimit(int limit) {
		if (limit <= 0 || limit > MAX_LIMIT) {
			throw new InvalidStationSearchException("조회 개수는 1개부터 50개 사이여야 합니다.");
		}
	}
}

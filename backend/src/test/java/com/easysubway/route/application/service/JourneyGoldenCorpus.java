package com.easysubway.route.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 대표 질의 코퍼스. 질의마다 "왜 대표인가"를 {@link Tag}로 붙이고, 태그가 사실인지는 실제 결과로 검증한다
 * ({@code JourneyGoldenRegressionTest}).
 */
record JourneyGoldenCorpus(int schemaVersion, List<Query> queries) {

	static final String RESOURCE = "journey-golden/v1/queries.json";
	private static final int SCHEMA_VERSION = 1;

	/** 대표 질의가 되는 이유. 의미는 {@link JourneyGoldenTags}가 결과로 확인한다. */
	enum Tag {
		/** 환승 없이 한 노선으로 간다. */
		DIRECT_RIDE,
		/** 환승 한 번. */
		TRANSFER_ONE,
		/** 환승 두 번 이상. */
		TRANSFER_MULTI,
		/** 계단 없음이 확정된 환승 동선(엘리베이터)만으로 경로가 나온다. */
		ELEVATOR_ONLY,
		/** 보행 프로필마다 결과가 다르다. */
		PROFILE_DIVERGENCE,
		/** 계단 상태가 확정되지 않아 계단 없는 프리셋은 경로를 내지 않는다(fail-closed). */
		STAIR_STATE_UNKNOWN,
		/** 엘리베이터 중단으로 계단 없는 프리셋은 경로를 낼 수 없다. */
		FACILITY_OUTAGE_BLOCKED,
		/** 시설 상태가 낡아 계단 없는 프리셋은 명시적 오류가 된다. */
		FACILITY_STATUS_ERROR,
		/** 그 시각에 어떤 프리셋으로도 갈 수 없다. */
		NO_SERVICE,
		/** 기존 대표 질의(프로필 탐색 정책 테스트, 운영 범위 벤치마크 코퍼스)의 출발·도착 쌍을 재사용한다. */
		EXISTING_REPRESENTATIVE
	}

	/** 시설 가동 시나리오. 없으면 시설 정보는 신선하고 차단이 없다. */
	record Facility(Mode mode, List<String> stationIds) {
		enum Mode {
			/** 해당 역의 환승에서 계단 없는 동선(엘리베이터)이 모두 중단됐다. */
			BLOCK_STEP_FREE_PATHWAYS,
			/** 시설 상태 관측이 낡았는데 계단 없는 요청은 상태를 필수로 요구한다. */
			STALE_REQUIRED
		}

		Facility {
			Objects.requireNonNull(mode, "mode");
			stationIds = stationIds == null ? List.of() : List.copyOf(stationIds);
		}
	}

	record Query(
		String id,
		String fixture,
		String origin,
		String destination,
		String departureLocalTime,
		List<Tag> tags,
		Facility facility
	) {
		Query {
			Objects.requireNonNull(id, "id");
			Objects.requireNonNull(fixture, "fixture");
			Objects.requireNonNull(origin, "origin");
			Objects.requireNonNull(destination, "destination");
			Objects.requireNonNull(departureLocalTime, "departureLocalTime");
			tags = List.copyOf(tags);
		}

		/** 출발·도착 쌍의 식별. 시설 시나리오만 다른 질의는 같은 쌍이다. */
		String pair() {
			return fixture + " " + origin + " " + destination;
		}
	}

	static JourneyGoldenCorpus load() {
		try (InputStream stream = Objects.requireNonNull(
			JourneyGoldenCorpus.class.getClassLoader().getResourceAsStream(RESOURCE), RESOURCE)) {
			return new ObjectMapper().readValue(stream, JourneyGoldenCorpus.class);
		} catch (IOException exception) {
			throw new UncheckedIOException(exception);
		}
	}

	JourneyGoldenCorpus {
		if (schemaVersion != SCHEMA_VERSION) throw new IllegalArgumentException("unsupported golden corpus schema: " + schemaVersion);
		queries = List.copyOf(queries);
		Set<String> ids = new HashSet<>();
		for (Query query : queries) {
			if (!ids.add(query.id())) throw new IllegalArgumentException("duplicate golden query id: " + query.id());
			if (query.tags().isEmpty()) throw new IllegalArgumentException("golden query has no tag: " + query.id());
		}
	}

	long pairCount() {
		return queries.stream().map(Query::pair).distinct().count();
	}
}

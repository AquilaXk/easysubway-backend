package com.easysubway.route.application.service;

import com.easysubway.journey.application.JourneyAlternatives;
import com.easysubway.route.application.service.JourneyGoldenCorpus.Facility;
import com.easysubway.route.application.service.JourneyGoldenCorpus.Query;
import com.easysubway.route.application.service.JourneyGoldenCorpus.Tag;
import com.easysubway.route.application.service.JourneyGoldenRow.Status;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 대표성 태그가 사실인지 운영이 실제로 낸 결과로 확인한다. 태그가 코퍼스에만 적힌 주장으로 남아 낡는 것을 막는다.
 *
 * <p>확인은 golden 행이 담은 운영 값(상태, 대안, 운영의 {@code stairFree} 상태, 접근성 코드)만 읽는다. 조건을 풀어 다시
 * 돌린 추론은 쓰지 않는다. 시설 차단의 원인은 차단이 없는 짝 질의의 행과 견줘 확인한다.</p>
 */
final class JourneyGoldenTags {

	private static final String VERIFIED_ACCESS = "VERIFIED";
	private static final String STAIRS_ACCESS = "STAIRS_INCLUDED";
	private static final String BENCHMARK_CORPUS = "journey-benchmark/v1/current-production-scope-corpus.json";
	private static final Set<String> EXISTING_PAIRS = loadExistingPairs();

	private JourneyGoldenTags() {
	}

	/** 코퍼스 전체와 그 질의별 결과(프리셋 순서). */
	record Results(List<Query> queries, Map<String, List<JourneyGoldenRow>> rowsByQuery) {

		List<JourneyGoldenRow> rows(Query query) {
			return rowsByQuery.get(query.id());
		}

		/** 같은 출발·도착·시각이고 시설 시나리오가 없는 짝 질의의 결과. */
		Optional<List<JourneyGoldenRow>> baselineRows(Query query) {
			return queries.stream()
				.filter(other -> other.facility() == null && other.pair().equals(query.pair())
					&& other.departureLocalTime().equals(query.departureLocalTime()))
				.findFirst().map(this::rows);
		}
	}

	static boolean holds(Tag tag, Query query, Results results) {
		List<JourneyGoldenRow> rows = results.rows(query);
		return switch (tag) {
			case DIRECT_RIDE -> standardTransfers(rows) == 0;
			case TRANSFER_ONE -> standardTransfers(rows) == 1;
			case TRANSFER_MULTI -> standardTransfers(rows) >= 2;
			case ELEVATOR_ONLY -> elevatorOnly(row(rows, JourneyGoldenPreset.NO_STAIRS));
			case PROFILE_DIVERGENCE -> rows.stream().map(JourneyGoldenTags::outcome).distinct().count() > 1;
			case STAIR_STATE_UNKNOWN -> standardStairFree(rows, JourneyAlternatives.StairFreeStatus.UNDETERMINED)
				&& strictRows(rows).anyMatch(row -> row.status() == Status.NO_ROUTE);
			case STAIRS_CONFIRMED -> standardStairFree(rows, JourneyAlternatives.StairFreeStatus.NOT_FOUND)
				&& row(rows, JourneyGoldenPreset.STANDARD).alternatives().stream()
					.allMatch(alternative -> STAIRS_ACCESS.equals(alternative.access()))
				&& strictRows(rows).anyMatch(row -> row.status() == Status.NO_ROUTE);
			case FACILITY_OUTAGE_NO_ROUTE -> facilityMode(query, Facility.Mode.BLOCK_STEP_FREE_PATHWAYS)
				&& strictRows(rows).anyMatch(row -> row.status() == Status.NO_ROUTE)
				&& results.baselineRows(query).map(JourneyGoldenTags::strictFound).orElse(false);
			case FACILITY_STATUS_ERROR -> facilityMode(query, Facility.Mode.STALE_REQUIRED)
				&& strictRows(rows).allMatch(row -> row.status() == Status.ERROR);
			case NO_ROUTE_ANY_PRESET -> rows.stream().allMatch(row -> row.status() == Status.NO_ROUTE);
			case EXISTING_REPRESENTATIVE -> EXISTING_PAIRS.contains(query.origin() + " " + query.destination());
		};
	}

	/** 태그 목록 중 사실이 아닌 것. */
	static List<Tag> violated(Query query, Results results) {
		return query.tags().stream().filter(tag -> !holds(tag, query, results)).toList();
	}

	/** 기존 대표 질의의 출발·도착 쌍. 수도권 대표 프로필 질의와 운영 범위 벤치마크 코퍼스를 합친다. */
	private static Set<String> loadExistingPairs() {
		Set<String> pairs = new HashSet<>();
		CapitalRepresentativeCases.cases()
			.forEach(existing -> pairs.add(existing.origin() + " " + existing.destination()));
		try (InputStream stream = Objects.requireNonNull(
			JourneyGoldenTags.class.getClassLoader().getResourceAsStream(BENCHMARK_CORPUS), BENCHMARK_CORPUS)) {
			new ObjectMapper().readTree(stream).path("cases").forEach(benchmark -> pairs.add(
				benchmark.path("originStationId").asText() + " " + benchmark.path("destinationStationId").asText()));
		} catch (IOException exception) {
			throw new UncheckedIOException(exception);
		}
		return pairs;
	}

	private static int standardTransfers(List<JourneyGoldenRow> rows) {
		JourneyGoldenRow standard = row(rows, JourneyGoldenPreset.STANDARD);
		return standard.status() == Status.FOUND ? standard.alternatives().getFirst().transfers() : -1;
	}

	private static boolean standardStairFree(List<JourneyGoldenRow> rows, JourneyAlternatives.StairFreeStatus status) {
		JourneyGoldenRow standard = row(rows, JourneyGoldenPreset.STANDARD);
		return standard.status() == Status.FOUND && standard.detail().startsWith("stairFree=" + status + " ");
	}

	private static boolean elevatorOnly(JourneyGoldenRow noStairs) {
		if (noStairs.status() != Status.FOUND) return false;
		var best = noStairs.alternatives().getFirst();
		return best.transfers() >= 1 && VERIFIED_ACCESS.equals(best.access());
	}

	private static boolean strictFound(List<JourneyGoldenRow> rows) {
		return strictRows(rows).allMatch(row -> row.status() == Status.FOUND);
	}

	private static String outcome(JourneyGoldenRow row) {
		return row.status() + (row.alternatives().isEmpty() ? "" : row.alternatives().getFirst().render());
	}

	private static boolean facilityMode(Query query, Facility.Mode mode) {
		return query.facility() != null && query.facility().mode() == mode;
	}

	private static Stream<JourneyGoldenRow> strictRows(List<JourneyGoldenRow> rows) {
		return rows.stream().filter(row -> row.preset().requiresStepFree());
	}

	private static JourneyGoldenRow row(List<JourneyGoldenRow> rows, JourneyGoldenPreset preset) {
		return rows.stream().filter(row -> row.preset() == preset).findFirst().orElseThrow();
	}
}

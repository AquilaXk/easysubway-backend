package com.easysubway.route.application.service;

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
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 대표성 태그가 사실인지 실제 결과로 확인한다. 태그가 코퍼스에만 적힌 주장으로 남아 낡는 것을 막는다.
 */
final class JourneyGoldenTags {

	private static final String VERIFIED_ACCESS = "VERIFIED";
	private static final String BENCHMARK_CORPUS = "journey-benchmark/v1/current-production-scope-corpus.json";

	private static final Set<String> EXISTING_PAIRS = loadExistingPairs();

	private JourneyGoldenTags() {
	}

	/** {@code rows}는 질의 하나를 프리셋 순서대로 돌린 결과다. */
	static boolean holds(Tag tag, Query query, List<JourneyGoldenRow> rows) {
		return switch (tag) {
			case DIRECT_RIDE -> standardTransfers(rows) == 0;
			case TRANSFER_ONE -> standardTransfers(rows) == 1;
			case TRANSFER_MULTI -> standardTransfers(rows) >= 2;
			case ELEVATOR_ONLY -> elevatorOnly(row(rows, JourneyGoldenPreset.NO_STAIRS));
			case PROFILE_DIVERGENCE -> rows.stream().map(JourneyGoldenTags::outcome).distinct().count() > 1;
			case STAIR_STATE_UNKNOWN -> strictRows(rows).anyMatch(row -> row.status() == Status.UNKNOWN);
			case FACILITY_OUTAGE_BLOCKED -> facilityMode(query, Facility.Mode.BLOCK_STEP_FREE_PATHWAYS)
				&& strictRows(rows).anyMatch(row -> row.status() == Status.BLOCKED);
			case FACILITY_STATUS_ERROR -> facilityMode(query, Facility.Mode.STALE_REQUIRED)
				&& strictRows(rows).allMatch(row -> row.status() == Status.ERROR);
			case NO_SERVICE -> rows.stream().allMatch(row -> row.status() == Status.UNSUPPORTED);
			case EXISTING_REPRESENTATIVE -> EXISTING_PAIRS.contains(query.origin() + " " + query.destination());
		};
	}

	/** 기존 대표 질의의 출발·도착 쌍. 프로필 탐색 정책 테스트의 질의와 운영 범위 벤치마크 코퍼스를 합친다. */
	private static Set<String> loadExistingPairs() {
		Set<String> pairs = new HashSet<>();
		ProfileSearchWithinResourcePolicyTest.cases()
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

	/** 태그 목록 중 사실이 아닌 것. */
	static List<Tag> violated(Query query, List<JourneyGoldenRow> rows) {
		return query.tags().stream().filter(tag -> !holds(tag, query, rows)).toList();
	}

	private static int standardTransfers(List<JourneyGoldenRow> rows) {
		JourneyGoldenRow standard = row(rows, JourneyGoldenPreset.STANDARD);
		return standard.status() == Status.FOUND ? standard.alternatives().getFirst().transfers() : -1;
	}

	private static boolean elevatorOnly(JourneyGoldenRow noStairs) {
		if (noStairs.status() != Status.FOUND) return false;
		var best = noStairs.alternatives().getFirst();
		return best.transfers() >= 1 && VERIFIED_ACCESS.equals(best.access());
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

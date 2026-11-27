package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 전국 O-D 운임 64-bit 워드 패킹 스토어의 무손실 인코딩·디코딩 및 Map 인터페이스 호환성 단위 테스트.
 */
class BitPackedOdFareStoreTest {

	@Test
	@DisplayName("일반적인 10원 단위 10230원 이하 운임은 64-bit 워드로 무손실 압축되고 O(1) 무할당 조회된다")
	void encodesAndDecodesRegularQuotesBitPacked() {
		List<String> stations = List.of("station-a", "station-b", "station-c");
		OfficialFareQuote quote = new OfficialFareQuote(
			"station-a", "station-b",
			"kric-fare-v1", "snap-20261116", "ledger-hash-1",
			1400, 1500, 800, 900, 500, 500
		);

		Map<String, OfficialFareQuote> map = Map.of(
			OfficialFareQuote.fareKey("station-a", "station-b"), quote
		);

		BitPackedOdFareStore store = BitPackedOdFareStore.from(stations, map);

		assertThat(store.size()).isEqualTo(1);
		assertThat(store.isEmpty()).isFalse();
		assertThat(store.containsKey(OfficialFareQuote.fareKey("station-a", "station-b"))).isTrue();
		assertThat(store.getQuote("station-a", "station-b")).isEqualTo(quote);
		assertThat(store.get(OfficialFareQuote.fareKey("station-a", "station-b"))).isEqualTo(quote);
		assertThat(store).isEqualTo(map);
		assertThat(store.hashCode()).isEqualTo(map.hashCode());

		// 미존재 조회
		assertThat(store.getQuote("station-a", "station-c")).isNull();
		assertThat(store.get("non-existent-key")).isNull();
	}

	@Test
	@DisplayName("10원 단위가 아니거나 10230원을 초과하는 특례 운임은 overflow 맵으로 자동 격리되어 100% 무손실 보존된다")
	void handlesIrregularAndOversizedFaresLosslesslyViaOverflow() {
		List<String> stations = List.of("station-a", "station-b", "station-c");

		// 10원 단위 아님 (1405원)
		OfficialFareQuote nonTenUnit = new OfficialFareQuote(
			"station-a", "station-b",
			"kric-fare-v1", "snap-1", "hash-1",
			1405, 1500, 800, 900, 500, 500
		);
		// 10230원 초과 (15000원)
		OfficialFareQuote oversized = new OfficialFareQuote(
			"station-b", "station-c",
			"kric-fare-v1", "snap-1", "hash-1",
			15000, 15000, 8000, 8000, 4000, 4000
		);

		Map<String, OfficialFareQuote> map = new HashMap<>();
		map.put(OfficialFareQuote.fareKey("station-a", "station-b"), nonTenUnit);
		map.put(OfficialFareQuote.fareKey("station-b", "station-c"), oversized);

		BitPackedOdFareStore store = BitPackedOdFareStore.from(stations, map);

		assertThat(store.size()).isEqualTo(2);
		assertThat(store.getQuote("station-a", "station-b")).isEqualTo(nonTenUnit);
		assertThat(store.getQuote("station-b", "station-c")).isEqualTo(oversized);
		assertThat(store.get(OfficialFareQuote.fareKey("station-a", "station-b"))).isEqualTo(nonTenUnit);
		assertThat(store.get(OfficialFareQuote.fareKey("station-b", "station-c"))).isEqualTo(oversized);
	}

	@Test
	@DisplayName("불변 Map 뷰·메타데이터 카탈로그 상한 초과 격리·경계 입력 처리가 정확히 동작한다")
	void enforcesImmutableMapContractAndMetadataOverflow() {
		Map<String, OfficialFareQuote> manyMeta = new HashMap<>();
		for (int i = 0; i < 10; i++) {
			String origin = "s-" + i;
			String dest = "s-" + (i + 1);
			manyMeta.put(OfficialFareQuote.fareKey(origin, dest), new OfficialFareQuote(
				origin, dest, "src-" + i, "snap-" + i, "hash-" + i,
				1400, 1500, 800, 900, 500, 500
			));
		}
		BitPackedOdFareStore store = BitPackedOdFareStore.from(List.of("s-0", "", "s-0"), manyMeta);

		assertThat(store).hasSize(10);
		assertThat(store).isEqualTo(manyMeta);
		assertThat(store.equals(store)).isTrue();
		assertThat(store.equals("not-a-map")).isFalse();
		assertThat(store.containsKey(123)).isFalse();
		assertThat(store.get(123)).isNull();
		assertThat(store.get("^@")).isNull();
		assertThat(store.get("s-0^@")).isNull();
		assertThat(store.getQuote(null, "s-1")).isNull();
		assertThat(store.getQuote("s-0", null)).isNull();
		assertThat(store.getQuote(" ", "s-1")).isNull();
		assertThat(store.getQuote("s-0", " ")).isNull();
		assertThat(store.getQuote("s-0", "s-0")).isNull();
		assertThat(store.keySet()).containsAll(manyMeta.keySet());
		assertThat(store.values()).hasSize(10);
		assertThat(store.entrySet()).hasSize(10);
		assertThat(store.containsValue(manyMeta.values().iterator().next())).isTrue();
		assertThat(store.toString()).contains("s-0^@s-1");

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.put("k", null))
			.isInstanceOf(UnsupportedOperationException.class);
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.remove("k"))
			.isInstanceOf(UnsupportedOperationException.class);
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.putAll(Map.of()))
			.isInstanceOf(UnsupportedOperationException.class);
		org.assertj.core.api.Assertions.assertThatThrownBy(store::clear)
			.isInstanceOf(UnsupportedOperationException.class);

		BitPackedOdFareStore empty = BitPackedOdFareStore.from(null, Map.of());
		assertThat(empty.isEmpty()).isTrue();
		assertThat(empty.getQuote("a", "b")).isNull();
	}

	@Test
	@DisplayName("Builder 직접 적재는 packed·비정규 overflow·미등록 역 모두 from()과 동일하며 중복 키를 거부한다")
	void builderDirectPackingMatchesFromAndRejectsDuplicatesAcrossOverflow() {
		BitPackedOdFareStore built = BitPackedOdFareStore.builder(List.of("s-1", "s-2", "s-3"))
			.add("s-1", "s-2", "kric", "snap-1", "h1", 1400, 1500, 800, 900, 500, 500)
			.add("s-2", "s-3", "kric", "snap-1", "h1", 1405, 1500, 800, 900, 500, 500)
			.add("s-1", "s-external", "kric", "snap-2", "h2", 2000, 2100, 1200, 1300, 700, 700)
			.build();

		assertThat(built).hasSize(3);
		assertThat(built.getQuote("s-1", "s-2").gnrlCardFare()).isEqualTo(1400);
		assertThat(built.getQuote("s-2", "s-3").gnrlCardFare()).isEqualTo(1405);
		assertThat(built.getQuote("s-1", "s-external").gnrlCardFare()).isEqualTo(2000);
		assertThat(built).isEqualTo(Map.of(
			OfficialFareQuote.fareKey("s-1", "s-2"), built.getQuote("s-1", "s-2"),
			OfficialFareQuote.fareKey("s-2", "s-3"), built.getQuote("s-2", "s-3"),
			OfficialFareQuote.fareKey("s-1", "s-external"), built.getQuote("s-1", "s-external")
		));

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> BitPackedOdFareStore.builder(List.of("s-1", "s-2"))
			.add("s-1", "s-2", "kric", "snap-1", "h1", 1400, 1500, 800, 900, 500, 500)
			.add("s-1", "s-2", "kric", "snap-1", "h1", 1405, 1500, 800, 900, 500, 500))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("duplicate official_od_fare_quotes key");

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> BitPackedOdFareStore.builder(List.of("s-1", "s-2"))
			.add("s-1", "s-2", "kric", "snap-1", "h1", 1405, 1500, 800, 900, 500, 500)
			.add("s-1", "s-2", "kric", "snap-1", "h1", 1400, 1500, 800, 900, 500, 500))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("duplicate official_od_fare_quotes key");
	}

	@Test
	@DisplayName("Builder 경계·메타데이터 재사용/한도·행렬 크기 초과·부분 누락 요금을 모두 정확히 처리한다")
	void builderCoversEmptyLargeMatrixMetaCacheAndPackValidationBranches() {
		// 1) 빈 역 목록 -> matrixEnabled=false -> overflow 경로
		BitPackedOdFareStore emptyMatrix = BitPackedOdFareStore.builder(List.of())
			.add("x-1", "x-2", "kric", "s1", "h1", 1400, 1500, 800, 900, 500, 500)
			.build();
		assertThat(emptyMatrix.getQuote("x-1", "x-2").gnrlCardFare()).isEqualTo(1400);
		assertThat(emptyMatrix.containsKey(OfficialFareQuote.fareKey("x-1", "missing"))).isFalse();

		// 2) MAX_MATRIX_STATIONS(10,000) 초과 -> matrixEnabled=false
		List<String> tooManyStations = java.util.stream.IntStream.range(0, 10_001)
			.mapToObj(i -> "st-" + i)
			.toList();
		BitPackedOdFareStore largeMatrix = BitPackedOdFareStore.builder(tooManyStations)
			.add("st-0", "st-1", "kric", "s1", "h1", 1400, 1500, 800, 900, 500, 500)
			.build();
		assertThat(largeMatrix.getQuote("st-0", "st-1").gnrlCardFare()).isEqualTo(1400);

		// 3) 메타데이터 캐시 히트(lastMeta 불일치 필드별 분기) 및 MAX_METADATA_COMBINATIONS(8) 초과 + overflow->packed 중복 거부
		var builder = BitPackedOdFareStore.builder(List.of(
			"s-0", "s-1", "s-2", "s-3", "s-4", "s-5", "s-6", "s-7", "s-8", "s-9", "s-10"));
		for (int i = 0; i < 8; i++) {
			builder.add("s-" + i, "s-" + (i + 1), "src-" + i, "snap-" + i, "h-" + i, 1400, 1500, 800, 900, 500, 500);
		}
		// 동일 sourceId/snapshotId지만 다른 ledgerHash -> lastMeta miss
		// 동일 lastMeta 재사용 -> lastMeta hit
		// 기존 metaMap 재조회 -> existing != null hit
		// 9번째 조합 -> metaMap 가득 참 -> overflow로 격리
		builder
			.add("s-0", "s-2", "src-7", "snap-7", "h-7", 1400, 1500, 800, 900, 500, 500)
			.add("s-0", "s-3", "src-0", "snap-0", "h-0", 1400, 1500, 800, 900, 500, 500)
			.add("s-0", "s-4", "src-0", "snap-0", "h-other", 1400, 1500, 800, 900, 500, 500)
			.add("s-0", "s-5", "src-0", "snap-other", "h-0", 1400, 1500, 800, 900, 500, 500)
			.add("s-0", "s-6", "src-0", "snap-0", "h-0", 1400, 1500, 800, 900, 500, 500)
			// 각각 다른 운임 필드가 pack 불가 조건에 걸리는 경우
			.add("s-1", "s-3", "src-0", "snap-0", "h-0", 1400, 1505, 800, 900, 500, 500)
			.add("s-1", "s-4", "src-0", "snap-0", "h-0", 1400, 1500, 805, 900, 500, 500)
			.add("s-1", "s-5", "src-0", "snap-0", "h-0", 1400, 1500, 800, 10240, 500, 500)
			.add("s-1", "s-6", "src-0", "snap-0", "h-0", 1400, 1500, 800, 900, 505, 500)
			.add("s-1", "s-7", "src-0", "snap-0", "h-0", 1400, 1500, 800, 900, 500, 10240)
			.add("s-1", "s-8", "src-9", "snap-9", "h-9", 1400, 1500, 800, 900, 500, 500);

		// packed 키 중복 거부
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> builder.add(
			"s-0", "s-1", "src-0", "snap-0", "h-0", 1400, 1500, 800, 900, 500, 500))
			.isInstanceOf(IllegalArgumentException.class);
		// overflow 키에 packed 가능한 조합 중복 적재 거부
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> builder.add(
			"s-1", "s-8", "src-0", "snap-0", "h-0", 1400, 1500, 800, 900, 500, 500))
			.isInstanceOf(IllegalArgumentException.class);
		// overflow 키에 overflow 조합 중복 적재 거부 + gnrlCard unpackable
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> builder.add(
			"s-1", "s-8", "src-9", "snap-9", "h-9", 1405, 1500, 800, 900, 500, 500))
			.isInstanceOf(IllegalArgumentException.class);
		// origin/dest 한쪽만 미존재인 add & getQuote
		builder.add("s-0", "unknown-dest", "src-0", "snap-0", "h-0", 1400, 1500, 800, 900, 500, 500);
		builder.add("unknown-origin", "s-0", "src-0", "snap-0", "h-0", 1400, 1500, 800, 900, 500, 500);
		BitPackedOdFareStore built = builder.build();
		assertThat(built.getQuote("s-0", "missing-dest")).isNull();
		assertThat(built.getQuote("s-1", "s-8").snapshotId()).isEqualTo("snap-9");

		// from()에서 null value quote 무시 및 동일 key matrix 중복 방지 확인
		Map<String, OfficialFareQuote> withNull = new HashMap<>();
		withNull.put("a^@b", null);
		withNull.put("a^@c", new OfficialFareQuote("a", "c", "src", "snap", "h", 1400, 1500, 800, 900, 500, 500));
		BitPackedOdFareStore fromWithNull = BitPackedOdFareStore.from(List.of("a", "b", "c"), withNull);
		assertThat(fromWithNull).hasSize(1);
	}
}

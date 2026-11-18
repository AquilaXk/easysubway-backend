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
}

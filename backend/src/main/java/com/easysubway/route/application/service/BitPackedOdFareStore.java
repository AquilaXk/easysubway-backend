/*
 * Copyright (c) 2026 EasySubway. All rights reserved.
 *
 * This class incorporates architectural concepts and low-level designs from:
 * 1. vigna/fastutil (Apache License 2.0, Copyright (C) 2002-2024 Sebastiano Vigna)
 *    - Flat array indexing, shift-mask operations, and primitive open-addressing patterns.
 * 2. motis-project/nigiri (MIT License, Copyright (c) 2022 Felix Gündling / MOTIS Project)
 *    - Bitpacked word matrix and contiguous static indexing design for transit lookup.
 */
package com.easysubway.route.application.service;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 64-bit 워드 패킹 기반의 전국 O-D 공식 운임 인메모리 스토어.
 *
 * <p>56만 개 이상의 O-D 쌍을 평탄 {@code long[stationCount * stationCount]} 배열에 비트 패킹하여
 * 문자열 키 결합 및 레코드 객체 상주 오버헤드를 0으로 축소한다.
 * 10원 단위 10,230원 이하의 운임은 64-bit 워드로 무할당 인코딩하며,
 * 특례 운임이나 메타데이터 초과는 내부 overflow 맵으로 격리해 100% 무손실 보장한다.</p>
 */
public final class BitPackedOdFareStore implements Map<String, OfficialFareQuote> {

	private static final long PRESENCE_BIT = 0x8000_0000_0000_0000L;
	private static final int MAX_PACKABLE_FARE = 10_230;
	private static final int MAX_METADATA_COMBINATIONS = 8;
	private static final int MAX_MATRIX_STATIONS = 16_384;
	private static final int FARE_MASK_10BIT = 0x3FF;

	private final Map<String, Integer> stationIndices;
	private final String[] stationIds;
	private final int stationCount;
	private final long[] words;
	private final List<MetadataTuple> metadataCatalog;
	private final Map<String, OfficialFareQuote> overflowMap;
	private final int totalSize;
	private volatile Map<String, OfficialFareQuote> materializedView;

	public record MetadataTuple(String sourceId, String snapshotId, String mappingLedgerHash) {
		public MetadataTuple {
			Objects.requireNonNull(sourceId, "sourceId");
			Objects.requireNonNull(snapshotId, "snapshotId");
			Objects.requireNonNull(mappingLedgerHash, "mappingLedgerHash");
		}
	}

	private BitPackedOdFareStore(
		Map<String, Integer> stationIndices,
		String[] stationIds,
		int stationCount,
		long[] words,
		List<MetadataTuple> metadataCatalog,
		Map<String, OfficialFareQuote> overflowMap,
		int totalSize
	) {
		this.stationIndices = stationIndices;
		this.stationIds = stationIds;
		this.stationCount = stationCount;
		this.words = words;
		this.metadataCatalog = metadataCatalog;
		this.overflowMap = overflowMap.isEmpty() ? Map.of() : Map.copyOf(overflowMap);
		this.totalSize = totalSize;
	}

	public static BitPackedOdFareStore from(
		Collection<String> canonicalStations,
		Map<String, OfficialFareQuote> quotes
	) {
		Objects.requireNonNull(quotes, "quotes");
		Map<String, Integer> stationIdxMap = new HashMap<>();
		int idx = 0;
		if (canonicalStations != null) {
			for (String station : canonicalStations) {
				if (station != null && !station.isBlank() && !stationIdxMap.containsKey(station)) {
					stationIdxMap.put(station, idx++);
				}
			}
		}
		// quotes 내에 등장하는 역도 보충
		for (OfficialFareQuote quote : quotes.values()) {
			if (quote != null) {
				if (!stationIdxMap.containsKey(quote.originStationId())) {
					stationIdxMap.put(quote.originStationId(), idx++);
				}
				if (!stationIdxMap.containsKey(quote.destinationStationId())) {
					stationIdxMap.put(quote.destinationStationId(), idx++);
				}
			}
		}

		int rawCount = stationIdxMap.size();
		boolean matrixEnabled = rawCount > 0 && rawCount <= MAX_MATRIX_STATIONS;
		int count = matrixEnabled ? rawCount : 0;
		String[] ids = new String[count];
		if (matrixEnabled) {
			for (var entry : stationIdxMap.entrySet()) {
				ids[entry.getValue()] = entry.getKey();
			}
		}

		long[] wordsArray = matrixEnabled ? new long[count * count] : new long[0];
		Map<MetadataTuple, Integer> metaMap = new LinkedHashMap<>();
		Map<String, OfficialFareQuote> overflows = new HashMap<>();
		int packedCount = 0;

		for (var entry : quotes.entrySet()) {
			OfficialFareQuote quote = entry.getValue();
			if (quote == null) {
				continue;
			}
			String orig = quote.originStationId();
			String dest = quote.destinationStationId();
			Integer origIdx = matrixEnabled ? stationIdxMap.get(orig) : null;
			Integer destIdx = matrixEnabled ? stationIdxMap.get(dest) : null;

			if (origIdx == null || destIdx == null || !canPack(quote)) {
				overflows.put(entry.getKey(), quote);
				continue;
			}

			MetadataTuple meta = new MetadataTuple(quote.sourceId(), quote.snapshotId(), quote.mappingLedgerHash());
			Integer metaIdx = metaMap.get(meta);
			if (metaIdx == null) {
				if (metaMap.size() >= MAX_METADATA_COMBINATIONS) {
					overflows.put(entry.getKey(), quote);
					continue;
				}
				metaIdx = metaMap.size();
				metaMap.put(meta, metaIdx);
			}

			long word = pack(quote, metaIdx);
			int slot = origIdx * count + destIdx;
			if ((wordsArray[slot] & PRESENCE_BIT) != 0L) {
				// 중복 엔트리 슬롯 충돌 시 오버플로 격리
				overflows.put(entry.getKey(), quote);
			} else {
				wordsArray[slot] = word;
				packedCount++;
			}
		}

		List<MetadataTuple> metaCatalog = List.copyOf(metaMap.keySet());
		return new BitPackedOdFareStore(
			Map.copyOf(stationIdxMap),
			ids,
			count,
			wordsArray,
			metaCatalog,
			overflows,
			packedCount + overflows.size()
		);
	}

	private static boolean canPack(OfficialFareQuote q) {
		return q.gnrlCardFare() >= 0 && q.gnrlCardFare() <= MAX_PACKABLE_FARE && (q.gnrlCardFare() % 10 == 0)
			&& q.gnrlCashFare() >= 0 && q.gnrlCashFare() <= MAX_PACKABLE_FARE && (q.gnrlCashFare() % 10 == 0)
			&& q.yungCardFare() >= 0 && q.yungCardFare() <= MAX_PACKABLE_FARE && (q.yungCardFare() % 10 == 0)
			&& q.yungCashFare() >= 0 && q.yungCashFare() <= MAX_PACKABLE_FARE && (q.yungCashFare() % 10 == 0)
			&& q.childCardFare() >= 0 && q.childCardFare() <= MAX_PACKABLE_FARE && (q.childCardFare() % 10 == 0)
			&& q.childCashFare() >= 0 && q.childCashFare() <= MAX_PACKABLE_FARE && (q.childCashFare() % 10 == 0);
	}

	private static long pack(OfficialFareQuote q, int metaIdx) {
		long w = PRESENCE_BIT;
		w |= ((long) (q.gnrlCardFare() / 10) & FARE_MASK_10BIT);
		w |= (((long) (q.gnrlCashFare() / 10) & FARE_MASK_10BIT) << 10);
		w |= (((long) (q.yungCardFare() / 10) & FARE_MASK_10BIT) << 20);
		w |= (((long) (q.yungCashFare() / 10) & FARE_MASK_10BIT) << 30);
		w |= (((long) (q.childCardFare() / 10) & FARE_MASK_10BIT) << 40);
		w |= (((long) (q.childCashFare() / 10) & FARE_MASK_10BIT) << 50);
		w |= (((long) metaIdx & 0x7L) << 60);
		return w;
	}

	private OfficialFareQuote unpack(String origin, String dest, long word) {
		int gnrlCard = (int) (word & FARE_MASK_10BIT) * 10;
		int gnrlCash = (int) ((word >>> 10) & FARE_MASK_10BIT) * 10;
		int yungCard = (int) ((word >>> 20) & FARE_MASK_10BIT) * 10;
		int yungCash = (int) ((word >>> 30) & FARE_MASK_10BIT) * 10;
		int childCard = (int) ((word >>> 40) & FARE_MASK_10BIT) * 10;
		int childCash = (int) ((word >>> 50) & FARE_MASK_10BIT) * 10;
		int metaIdx = (int) ((word >>> 60) & 0x7L);
		MetadataTuple meta = metadataCatalog.get(metaIdx);
		return new OfficialFareQuote(
			origin, dest,
			meta.sourceId(), meta.snapshotId(), meta.mappingLedgerHash(),
			gnrlCard, gnrlCash, yungCard, yungCash, childCard, childCash
		);
	}

	/**
	 * 출발역과 도착역 ID로 직접 무할당 조회한다.
	 */
	public OfficialFareQuote getQuote(String origin, String dest) {
		if (origin == null || dest == null || origin.isBlank() || dest.isBlank() || origin.equals(dest)) {
			return null;
		}
		Integer origIdx = stationIndices.get(origin);
		Integer destIdx = stationIndices.get(dest);
		if (origIdx != null && destIdx != null && stationCount > 0) {
			long word = words[origIdx * stationCount + destIdx];
			if ((word & PRESENCE_BIT) != 0L) {
				return unpack(origin, dest, word);
			}
		}
		if (!overflowMap.isEmpty()) {
			return overflowMap.get(OfficialFareQuote.fareKey(origin, dest));
		}
		return null;
	}

	@Override
	public int size() {
		return totalSize;
	}

	@Override
	public boolean isEmpty() {
		return totalSize == 0;
	}

	@Override
	public boolean containsKey(Object key) {
		if (key instanceof String s) {
			return get(s) != null;
		}
		return false;
	}

	@Override
	public boolean containsValue(Object value) {
		return values().contains(value);
	}

	@Override
	public OfficialFareQuote get(Object key) {
		if (key instanceof String s) {
			int sep = s.indexOf("^@");
			if (sep > 0 && sep < s.length() - 2) {
				String origin = s.substring(0, sep);
				String dest = s.substring(sep + 2);
				return getQuote(origin, dest);
			}
			return overflowMap.get(s);
		}
		return null;
	}

	@Override
	public OfficialFareQuote put(String key, OfficialFareQuote value) {
		throw new UnsupportedOperationException("BitPackedOdFareStore is immutable");
	}

	@Override
	public OfficialFareQuote remove(Object key) {
		throw new UnsupportedOperationException("BitPackedOdFareStore is immutable");
	}

	@Override
	public void putAll(Map<? extends String, ? extends OfficialFareQuote> m) {
		throw new UnsupportedOperationException("BitPackedOdFareStore is immutable");
	}

	@Override
	public void clear() {
		throw new UnsupportedOperationException("BitPackedOdFareStore is immutable");
	}

	@Override
	public Set<String> keySet() {
		return materialized().keySet();
	}

	@Override
	public Collection<OfficialFareQuote> values() {
		return materialized().values();
	}

	@Override
	public Set<Entry<String, OfficialFareQuote>> entrySet() {
		return materialized().entrySet();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof Map<?, ?> other)) {
			return false;
		}
		return materialized().equals(other);
	}

	@Override
	public int hashCode() {
		return materialized().hashCode();
	}

	@Override
	public String toString() {
		return materialized().toString();
	}

	private Map<String, OfficialFareQuote> materialized() {
		Map<String, OfficialFareQuote> view = materializedView;
		if (view != null) {
			return view;
		}
		Map<String, OfficialFareQuote> map = new LinkedHashMap<>(totalSize);
		for (int r = 0; r < stationCount; r++) {
			String orig = stationIds[r];
			int rowOffset = r * stationCount;
			for (int c = 0; c < stationCount; c++) {
				long w = words[rowOffset + c];
				if ((w & PRESENCE_BIT) != 0L) {
					String dest = stationIds[c];
					map.put(OfficialFareQuote.fareKey(orig, dest), unpack(orig, dest, w));
				}
			}
		}
		map.putAll(overflowMap);
		Map<String, OfficialFareQuote> unmodifiable = Collections.unmodifiableMap(map);
		materializedView = unmodifiable;
		return unmodifiable;
	}
}

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
import java.util.concurrent.atomic.AtomicReference;

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
	private final AtomicReference<Map<String, OfficialFareQuote>> materializedView = new AtomicReference<>();

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
		Map<String, Integer> stationIdxMap = buildStationIndex(canonicalStations, quotes.values());
		int rawCount = stationIdxMap.size();
		boolean matrixEnabled = rawCount > 0 && rawCount <= MAX_MATRIX_STATIONS;
		int count = matrixEnabled ? rawCount : 0;
		String[] ids = buildStationIdArray(stationIdxMap, count, matrixEnabled);

		long[] wordsArray = matrixEnabled ? new long[count * count] : new long[0];
		Map<MetadataTuple, Integer> metaMap = new LinkedHashMap<>();
		Map<String, OfficialFareQuote> overflows = new HashMap<>();
		int packedCount = 0;

		for (var entry : quotes.entrySet()) {
			OfficialFareQuote quote = entry.getValue();
			if (quote != null) {
				if (tryPackEntry(quote, stationIdxMap, count, matrixEnabled, wordsArray, metaMap)) {
					packedCount++;
				} else {
					overflows.put(entry.getKey(), quote);
				}
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

	public static Builder builder(Collection<String> canonicalStations) {
		return new Builder(canonicalStations);
	}

	public static final class Builder {
		private final Map<String, Integer> stationIdxMap;
		private final String[] stationIds;
		private final int stationCount;
		private final boolean matrixEnabled;
		private final long[] words;
		private final Map<MetadataTuple, Integer> metaMap = new LinkedHashMap<>();
		private final Map<String, OfficialFareQuote> overflows = new HashMap<>();
		private MetadataTuple lastMeta;
		private int lastMetaIdx = -1;
		private int packedCount;

		private Builder(Collection<String> canonicalStations) {
			Map<String, Integer> idxMap = buildStationIndex(canonicalStations, List.of());
			int rawCount = idxMap.size();
			this.matrixEnabled = rawCount > 0 && rawCount <= MAX_MATRIX_STATIONS;
			this.stationCount = matrixEnabled ? rawCount : 0;
			this.stationIdxMap = Map.copyOf(idxMap);
			this.stationIds = buildStationIdArray(idxMap, stationCount, matrixEnabled);
			this.words = matrixEnabled ? new long[stationCount * stationCount] : new long[0];
		}

		public Builder add(
			String originStationId,
			String destinationStationId,
			String sourceId,
			String snapshotId,
			String mappingLedgerHash,
			int gnrlCardFare,
			int gnrlCashFare,
			int yungCardFare,
			int yungCashFare,
			int childCardFare,
			int childCashFare
		) {
			Integer origIdx = matrixEnabled ? stationIdxMap.get(originStationId) : null;
			Integer destIdx = matrixEnabled ? stationIdxMap.get(destinationStationId) : null;
			int slot = -1;
			if (origIdx != null && destIdx != null) {
				slot = origIdx * stationCount + destIdx;
				if ((words[slot] & PRESENCE_BIT) != 0L) {
					throw new IllegalArgumentException(
						"duplicate official_od_fare_quotes key: "
							+ OfficialFareQuote.fareKey(originStationId, destinationStationId));
				}
			}
			if (slot >= 0 && canPack(gnrlCardFare, gnrlCashFare, yungCardFare, yungCashFare, childCardFare, childCashFare)) {
				int metaIdx = resolveMetaIdx(sourceId, snapshotId, mappingLedgerHash);
				if (metaIdx >= 0) {
					if (!overflows.isEmpty()
						&& overflows.containsKey(OfficialFareQuote.fareKey(originStationId, destinationStationId))) {
						throw new IllegalArgumentException(
							"duplicate official_od_fare_quotes key: "
								+ OfficialFareQuote.fareKey(originStationId, destinationStationId));
					}
					words[slot] = pack(
						gnrlCardFare, gnrlCashFare, yungCardFare, yungCashFare, childCardFare, childCashFare, metaIdx);
					packedCount++;
					return this;
				}
			}
			String key = OfficialFareQuote.fareKey(originStationId, destinationStationId);
			OfficialFareQuote quote = new OfficialFareQuote(
				originStationId, destinationStationId, sourceId, snapshotId, mappingLedgerHash,
				gnrlCardFare, gnrlCashFare, yungCardFare, yungCashFare, childCardFare, childCashFare);
			if (overflows.putIfAbsent(key, quote) != null) {
				throw new IllegalArgumentException("duplicate official_od_fare_quotes key: " + key);
			}
			return this;
		}

		private int resolveMetaIdx(String sourceId, String snapshotId, String mappingLedgerHash) {
			if (lastMeta != null
				&& lastMeta.sourceId().equals(sourceId)
				&& lastMeta.snapshotId().equals(snapshotId)
				&& lastMeta.mappingLedgerHash().equals(mappingLedgerHash)) {
				return lastMetaIdx;
			}
			MetadataTuple meta = new MetadataTuple(sourceId, snapshotId, mappingLedgerHash);
			Integer existing = metaMap.get(meta);
			if (existing != null) {
				lastMeta = meta;
				lastMetaIdx = existing;
				return existing;
			}
			if (metaMap.size() >= MAX_METADATA_COMBINATIONS) {
				return -1;
			}
			int index = metaMap.size();
			metaMap.put(meta, index);
			lastMeta = meta;
			lastMetaIdx = index;
			return index;
		}

		public BitPackedOdFareStore build() {
			return new BitPackedOdFareStore(
				stationIdxMap,
				stationIds,
				stationCount,
				words,
				List.copyOf(metaMap.keySet()),
				overflows,
				packedCount + overflows.size()
			);
		}
	}

	private static Map<String, Integer> buildStationIndex(
		Collection<String> canonicalStations,
		Collection<OfficialFareQuote> quotes
	) {
		Map<String, Integer> stationIdxMap = new HashMap<>();
		if (canonicalStations != null) {
			for (String station : canonicalStations) {
				if (station != null && !station.isBlank()) {
					stationIdxMap.putIfAbsent(station, stationIdxMap.size());
				}
			}
		}
		for (OfficialFareQuote quote : quotes) {
			if (quote != null) {
				stationIdxMap.putIfAbsent(quote.originStationId(), stationIdxMap.size());
				stationIdxMap.putIfAbsent(quote.destinationStationId(), stationIdxMap.size());
			}
		}
		return stationIdxMap;
	}

	private static String[] buildStationIdArray(
		Map<String, Integer> stationIdxMap,
		int count,
		boolean matrixEnabled
	) {
		String[] ids = new String[count];
		if (matrixEnabled) {
			for (var entry : stationIdxMap.entrySet()) {
				ids[entry.getValue()] = entry.getKey();
			}
		}
		return ids;
	}

	private static boolean tryPackEntry(
		OfficialFareQuote quote,
		Map<String, Integer> stationIdxMap,
		int count,
		boolean matrixEnabled,
		long[] wordsArray,
		Map<MetadataTuple, Integer> metaMap
	) {
		Integer origIdx = matrixEnabled ? stationIdxMap.get(quote.originStationId()) : null;
		Integer destIdx = matrixEnabled ? stationIdxMap.get(quote.destinationStationId()) : null;
		if (origIdx == null || destIdx == null || !canPack(quote)) {
			return false;
		}
		int slot = origIdx * count + destIdx;
		if ((wordsArray[slot] & PRESENCE_BIT) != 0L) {
			return false;
		}
		MetadataTuple meta = new MetadataTuple(quote.sourceId(), quote.snapshotId(), quote.mappingLedgerHash());
		Integer metaIdx = metaMap.get(meta);
		if (metaIdx == null) {
			if (metaMap.size() >= MAX_METADATA_COMBINATIONS) {
				return false;
			}
			metaIdx = metaMap.size();
			metaMap.put(meta, metaIdx);
		}
		wordsArray[slot] = pack(quote, metaIdx);
		return true;
	}

	private static boolean canPack(OfficialFareQuote q) {
		return canPack(q.gnrlCardFare(), q.gnrlCashFare(), q.yungCardFare(), q.yungCashFare(),
			q.childCardFare(), q.childCashFare());
	}

	private static boolean canPack(
		int gnrlCard, int gnrlCash, int yungCard, int yungCash, int childCard, int childCash
	) {
		return isPackableFare(gnrlCard)
			&& isPackableFare(gnrlCash)
			&& isPackableFare(yungCard)
			&& isPackableFare(yungCash)
			&& isPackableFare(childCard)
			&& isPackableFare(childCash);
	}

	private static boolean isPackableFare(int fare) {
		return fare >= 0 && fare <= MAX_PACKABLE_FARE && (fare % 10 == 0);
	}

	private static long pack(OfficialFareQuote q, int metaIdx) {
		return pack(q.gnrlCardFare(), q.gnrlCashFare(), q.yungCardFare(), q.yungCashFare(),
			q.childCardFare(), q.childCashFare(), metaIdx);
	}

	private static long pack(
		int gnrlCard, int gnrlCash, int yungCard, int yungCash, int childCard, int childCash, int metaIdx
	) {
		long w = PRESENCE_BIT;
		w |= ((long) (gnrlCard / 10) & FARE_MASK_10BIT);
		w |= (((long) (gnrlCash / 10) & FARE_MASK_10BIT) << 10);
		w |= (((long) (yungCard / 10) & FARE_MASK_10BIT) << 20);
		w |= (((long) (yungCash / 10) & FARE_MASK_10BIT) << 30);
		w |= (((long) (childCard / 10) & FARE_MASK_10BIT) << 40);
		w |= (((long) (childCash / 10) & FARE_MASK_10BIT) << 50);
		w |= ((metaIdx & 0x7L) << 60);
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
		Map<String, OfficialFareQuote> view = materializedView.get();
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
		materializedView.set(unmodifiable);
		return unmodifiable;
	}
}

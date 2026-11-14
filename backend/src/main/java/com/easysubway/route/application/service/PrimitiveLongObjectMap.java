package com.easysubway.route.application.service;

import java.util.Arrays;
import java.util.Objects;

/**
 * Open-addressed hash map keyed by non-negative packed primitive {@code long} state keys.
 * Uses {@link #EMPTY_KEY} ({@code -1L}) as the sentinel for vacant slots to avoid boxed {@code Long}
 * and composite key record allocations in RAPTOR hot loops.
 */
final class PrimitiveLongObjectMap<V> {

	static final long EMPTY_KEY = -1L;
	private static final int DEFAULT_INITIAL_CAPACITY = 16;
	private static final int MAX_CAPACITY = 1 << 30;

	private long[] keys;
	private Object[] values;
	private int mask;
	private int size;
	private int resizeThreshold;

	PrimitiveLongObjectMap() {
		this(DEFAULT_INITIAL_CAPACITY);
	}

	PrimitiveLongObjectMap(int expectedEntries) {
		if (expectedEntries < 0) {
			throw new IllegalArgumentException("expectedEntries must be non-negative: " + expectedEntries);
		}
		long desired = Math.max(DEFAULT_INITIAL_CAPACITY, (long) expectedEntries + (expectedEntries >>> 1) + 1L);
		int tableCap = tableSizeFor(desired);
		this.keys = new long[tableCap];
		Arrays.fill(this.keys, EMPTY_KEY);
		this.values = new Object[tableCap];
		this.mask = tableCap - 1;
		this.resizeThreshold = thresholdFor(tableCap);
	}

	V get(long key) {
		requireValidKey(key);
		int slot = mix(key) & mask;
		while (true) {
			long k = keys[slot];
			if (k == key) {
				@SuppressWarnings("unchecked")
				V found = (V) values[slot];
				return found;
			}
			if (k == EMPTY_KEY) {
				return null;
			}
			slot = (slot + 1) & mask;
		}
	}

	void put(long key, V value) {
		requireValidKey(key);
		Objects.requireNonNull(value, "value must not be null");
		if (size >= resizeThreshold) {
			rehash();
		}
		int slot = mix(key) & mask;
		while (true) {
			long k = keys[slot];
			if (k == EMPTY_KEY) {
				keys[slot] = key;
				values[slot] = value;
				size += 1;
				return;
			}
			if (k == key) {
				values[slot] = value;
				return;
			}
			slot = (slot + 1) & mask;
		}
	}

	int capacity() {
		return keys.length;
	}

	long keyAt(int slot) {
		return keys[slot];
	}

	@SuppressWarnings("unchecked")
	V valueAt(int slot) {
		return (V) values[slot];
	}

	private void rehash() {
		int oldCap = keys.length;
		if (oldCap >= MAX_CAPACITY) {
			throw new IllegalStateException("PrimitiveLongObjectMap capacity limit reached: " + oldCap);
		}
		int newCap = oldCap << 1;
		long[] newKeys = new long[newCap];
		Arrays.fill(newKeys, EMPTY_KEY);
		Object[] newValues = new Object[newCap];
		int newMask = newCap - 1;
		for (int i = 0; i < oldCap; i += 1) {
			long k = keys[i];
			if (k != EMPTY_KEY) {
				int slot = mix(k) & newMask;
				while (newKeys[slot] != EMPTY_KEY) {
					slot = (slot + 1) & newMask;
				}
				newKeys[slot] = k;
				newValues[slot] = values[i];
			}
		}
		this.keys = newKeys;
		this.values = newValues;
		this.mask = newMask;
		this.resizeThreshold = thresholdFor(newCap);
	}

	private static void requireValidKey(long key) {
		if (key == EMPTY_KEY) {
			throw new IllegalArgumentException("key must not equal EMPTY_KEY (-1L)");
		}
	}

	private static int mix(long key) {
		long h = key * 0x9E3779B97F4A7C15L;
		return (int) (h ^ (h >>> 32));
	}

	private static int tableSizeFor(long target) {
		if (target >= MAX_CAPACITY) {
			return MAX_CAPACITY;
		}
		int clamped = (int) Math.max(DEFAULT_INITIAL_CAPACITY, target);
		int cap = Integer.highestOneBit(clamped - 1) << 1;
		return Math.min(MAX_CAPACITY, Math.max(DEFAULT_INITIAL_CAPACITY, cap));
	}

	private static int thresholdFor(int capacity) {
		return (capacity * 2) / 3;
	}
}

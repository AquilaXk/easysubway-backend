package com.easysubway.journey.application;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * #469: 출발 시각 고정 검색의 결과 구성 규칙과 그 결과를 설명하는 값.
 *
 * <p>후보(파레토 여정)에서 최대 {@code alternativeCount}개를 고른다. 빠른 경로(도착 최소), 환승 적은 경로(승차 수 최소,
 * 다음 도착), 계단 없는 경로(계단 환승이 없는 후보 중 도착 최소) 순으로 담고(계단 없는 경로를 앞에 두는 경우는 빠른 →
 * 계단 없는 → 환승 적은), 이미 고른 후보는 건너뛴 뒤 남는 자리를 나머지 후보로 도착 순 채운다. 결과는 도착 순이다.
 * 계단 없는 후보가 없으면 그 묶음을 만들지 않는다.</p>
 */
public final class JourneyAlternatives {

	private JourneyAlternatives() {
	}

	/** 여정이 대표하는 결과 묶음. */
	public enum Category {
		FASTEST,
		FEWEST_TRANSFERS,
		STAIR_FREE
	}

	/**
	 * 계단 없는 경로 묶음의 결과.
	 * <ul>
	 *   <li>{@code INCLUDED}: 결과에 계단 없는 여정이 있다.</li>
	 *   <li>{@code OMITTED}: 계단 없는 여정을 찾았지만 대안 수가 모자라 앞 묶음(빠른·환승 적은)에 자리를 내줬다.</li>
	 *   <li>{@code NOT_FOUND}: 검증 동선으로 계단 없는 여정을 찾지 못했고, 반환한 여정의 계단 환승에도 근거가
	 *   검증되지 않은 계단 없는 동선이 없다.</li>
	 *   <li>{@code UNDETERMINED}: 계단 없는 여정을 찾지 못했고, 반환한 여정의 계단 환승 중 계단 없는 동선이 있지만 근거가
	 *   검증되지 않아 쓰지 못한 곳이 있다. 계단 없이 갈 수 있는지 확정할 수 없다.</li>
	 * </ul>
	 */
	public enum StairFreeStatus {
		INCLUDED,
		OMITTED,
		NOT_FOUND,
		UNDETERMINED
	}

	/** 요청 시점 시설 가동 정보(막힌 동선 제외)를 탐색에 적용했는지. */
	public enum FacilityStatus {
		APPLIED,
		UNOBSERVED
	}

	/** 검색 응답 수준의 계단 없는 경로 결과. */
	public record StairFreeAlternative(StairFreeStatus status, FacilityStatus facilityStatus) {
		public StairFreeAlternative {
			Objects.requireNonNull(status, "status");
			Objects.requireNonNull(facilityStatus, "facilityStatus");
		}
	}

	/**
	 * 계단 없는 경로 묶음 결과 판정. {@code anyStairFree}: 고른 여정 중 계단 없는 여정이 있음, {@code omitted}: 계단 없는
	 * 대표가 자리를 받지 못함, {@code anyUnconfirmedStairFreeTransfer}: 고른 여정의 계단 환승 중 근거 없는 계단 없는 동선이
	 * 있는 곳이 있음.
	 */
	public static StairFreeStatus stairFreeStatus(
		boolean anyStairFree, boolean omitted, boolean anyUnconfirmedStairFreeTransfer
	) {
		if (anyStairFree) return StairFreeStatus.INCLUDED;
		if (omitted) return StairFreeStatus.OMITTED;
		return anyUnconfirmedStairFreeTransfer ? StairFreeStatus.UNDETERMINED : StairFreeStatus.NOT_FOUND;
	}

	/** 고른 후보(도착 순)와 후보마다 대표하는 묶음, 계단 없는 대표 후보가 자리를 받지 못했는지. */
	public record Selection<T>(List<T> items, List<Set<Category>> categories, boolean stairFreeOmitted) {
		public Selection {
			items = List.copyOf(Objects.requireNonNull(items, "items"));
			categories = Objects.requireNonNull(categories, "categories").stream()
				.map(set -> set.isEmpty() ? Set.<Category>of() : Set.copyOf(EnumSet.copyOf(set))).toList();
			if (items.size() != categories.size()) {
				throw new IllegalArgumentException("categories must match items");
			}
		}

		public static <T> Selection<T> empty() {
			return new Selection<>(List.of(), List.of(), false);
		}
	}

	/**
	 * 결과 구성 규칙. {@code arrivalOrder}는 도착(같으면 승차 수) 순서, {@code boardings}는 승차 수,
	 * {@code stairFree}는 계단 환승이 없는지다. 후보 자신이 같은 객체인지로 중복을 판정한다.
	 */
	public static <T> Selection<T> compose(
		List<T> candidates,
		int alternativeCount,
		boolean stairFreeBeforeFewestTransfers,
		Comparator<? super T> arrivalOrder,
		ToIntFunction<? super T> boardings,
		Predicate<? super T> stairFree
	) {
		Objects.requireNonNull(candidates, "candidates");
		Objects.requireNonNull(arrivalOrder, "arrivalOrder");
		Objects.requireNonNull(boardings, "boardings");
		Objects.requireNonNull(stairFree, "stairFree");
		if (alternativeCount < 1) throw new IllegalArgumentException("alternativeCount must be positive");
		if (candidates.isEmpty()) return Selection.empty();
		List<T> byArrival = new ArrayList<>(candidates);
		byArrival.sort(arrivalOrder);
		T fastest = byArrival.getFirst();
		Comparator<T> fewestOrder = Comparator.<T>comparingInt(boardings).thenComparing(arrivalOrder);
		T fewestTransfers = byArrival.stream().min(fewestOrder).orElseThrow();
		T stairFreeRepresentative = byArrival.stream().filter(stairFree).findFirst().orElse(null);

		List<T> kept = new ArrayList<>(Math.min(alternativeCount, byArrival.size()));
		if (byArrival.size() <= alternativeCount) {
			kept.addAll(byArrival);
		} else {
			List<T> representatives = stairFreeBeforeFewestTransfers
				? Arrays.asList(fastest, stairFreeRepresentative, fewestTransfers)
				: Arrays.asList(fastest, fewestTransfers, stairFreeRepresentative);
			for (T representative : representatives) {
				if (representative != null && kept.size() < alternativeCount && !containsSame(kept, representative)) {
					kept.add(representative);
				}
			}
			for (T candidate : byArrival) {
				if (kept.size() >= alternativeCount) break;
				if (!containsSame(kept, candidate)) kept.add(candidate);
			}
			kept.sort(arrivalOrder);
		}
		List<Set<Category>> categories = new ArrayList<>(kept.size());
		for (T item : kept) {
			EnumSet<Category> roles = EnumSet.noneOf(Category.class);
			if (item == fastest) roles.add(Category.FASTEST);
			if (item == fewestTransfers) roles.add(Category.FEWEST_TRANSFERS);
			if (item == stairFreeRepresentative) roles.add(Category.STAIR_FREE);
			categories.add(roles);
		}
		boolean omitted = stairFreeRepresentative != null && !containsSame(kept, stairFreeRepresentative);
		return new Selection<>(kept, categories, omitted);
	}

	private static <T> boolean containsSame(List<T> items, T candidate) {
		for (T item : items) {
			if (item == candidate) return true;
		}
		return false;
	}
}

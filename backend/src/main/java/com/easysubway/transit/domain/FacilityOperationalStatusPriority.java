package com.easysubway.transit.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * 원천 수집 관측과 관리자 확인 사이의 우선순위(#419 QA 결정 추가 3).
 *
 * <p>원천({@code getFcElvtr})은 행마다 관측 시각을 주지 않고 매 수집마다 같은 값을 되풀이하므로, 원천 값이 바뀌지 않은
 * 되풀이는 새 정보가 아니다. 그래서 관리자 확인과 비교하는 원천의 관측 시각은 "원천 값이 바뀐 것이 관측된 시각"이다.
 * <ul>
 *   <li>관리자 확인이 원천의 마지막 값 변화보다 최근이면(원천이 확인 당시와 같은 코드를 되풀이하면) 관리자 확인을 유지한다.</li>
 *   <li>반대로 관리자 확인 시각보다 뒤에 원천 값이 바뀐 것이 관측되면 원천 관측이 더 최근이므로 원천 값으로 바꾼다.</li>
 *   <li>값 변화가 관리자 확인 시각 이전(같은 시각 포함)에 관측됐으면 관리자 확인을 유지하고 원천 코드만 기준값으로 기록한다.</li>
 * </ul>
 */
public final class FacilityOperationalStatusPriority {

	public enum FeedAction {
		/** 상태 행이 없어 원천 관측을 새로 쓴다. */
		INSERT,
		/** 원천 관측으로 상태·출처·원천 코드·관측 시각을 바꾼다. */
		OVERWRITE,
		/** 관리자 확인을 유지하고 원천 코드만 기준값으로 기록한다. */
		RECORD_SOURCE_CODE,
		/** 아무것도 바꾸지 않는다. */
		KEEP
	}

	private FacilityOperationalStatusPriority() {
	}

	public static FeedAction resolveFeed(FacilityOperationalStatus existing, String sourceCode, Instant observedAt) {
		Objects.requireNonNull(sourceCode, "sourceCode");
		Objects.requireNonNull(observedAt, "observedAt");
		if (existing == null) {
			return FeedAction.INSERT;
		}
		if (existing.source() == FacilityStatusSource.SEOUL_METRO_FEED) {
			return observedAt.isBefore(existing.observedAt()) ? FeedAction.KEEP : FeedAction.OVERWRITE;
		}
		if (sourceCode.equals(existing.sourceCode())) {
			return FeedAction.KEEP;
		}
		if (existing.sourceCode() != null && observedAt.isAfter(existing.observedAt())) {
			return FeedAction.OVERWRITE;
		}
		return FeedAction.RECORD_SOURCE_CODE;
	}
}

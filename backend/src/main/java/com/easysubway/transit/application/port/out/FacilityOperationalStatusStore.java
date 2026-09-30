package com.easysubway.transit.application.port.out;

import com.easysubway.transit.domain.FacilityOperationalState;
import com.easysubway.transit.domain.FacilityOperationalStatus;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 마스터 데이터와 분리된 시설 운영 상태 저장소(#419 QA 결정 추가 3). 운영 마스터 데이터는 데이터팩에서 오는 읽기 전용이므로
 * 가동 상태는 {@code facility_operational_status}·{@code facility_status_feed_heartbeat}에만 쓴다.
 */
public interface FacilityOperationalStatusStore {

	/** 서울교통공사 엘리베이터 가동 원천({@code getFcElvtr})의 심장박동 키. */
	String SEOUL_METRO_ELEVATOR_FEED = "SEOUL_METRO_ELEVATOR";

	List<FacilityOperationalStatus> loadStatuses();

	/** 원천 수집이 마지막으로 성공(상태 반영까지 커밋)한 시각. 한 번도 성공하지 않았으면 비어 있다. */
	Optional<Instant> lastSuccessfulCollectionAt(String feed);

	/**
	 * 한 회차 수집 결과를 우선순위 규칙대로 반영하고, 같은 트랜잭션에서 심장박동을 {@code observedAt}으로 옮긴다.
	 * 반영 중 하나라도 실패하면 상태와 심장박동을 모두 되돌린다.
	 *
	 * <p>{@code observations}는 모든 페이지를 받고 형식 검사를 통과한 전체 수집 결과여야 한다. 여기에 없는 시설(원천에서
	 * 빠졌거나 {@code D} 삭제·식별 불가·알 수 없는 코드가 된 시설)의 {@code SEOUL_METRO_FEED} 행은 마지막 값을 현재 상태처럼
	 * 남기지 않도록 같은 트랜잭션에서 지운다. {@code ADMIN_VERIFIED} 행은 관리자가 확인한 기록이라 지우지 않고
	 * {@link FeedApplyResult#adminVerifiedAbsent()}로 센다. 수집 실패·일부 페이지만 받은 회차는 이 메서드를 부르지 않으므로
	 * 아무 행도 지우지 않는다.
	 */
	FeedApplyResult applyFeedCollection(String feed, List<FeedObservation> observations, Instant observedAt);

	/**
	 * 관리자가 확인한 상태를 {@code ADMIN_VERIFIED}로 기록한다. {@code verifiedAt}보다 새 관측이 이미 있으면 기록하지 않고
	 * {@code false}를 돌려준다.
	 */
	boolean recordAdminVerified(String facilityId, FacilityOperationalState state, Instant verifiedAt);

	record FeedObservation(String facilityId, FacilityOperationalState state, String sourceCode) {

		public FeedObservation {
			Objects.requireNonNull(facilityId, "facilityId");
			Objects.requireNonNull(state, "state");
			Objects.requireNonNull(sourceCode, "sourceCode");
		}
	}

	/**
	 * @param written 새로 쓰거나 원천 값으로 바꾼 시설 수
	 * @param keptAdminVerified 관리자 확인이 더 최근이라 원천 값으로 바꾸지 않은 시설 수
	 * @param removed 이번 회차 관측에 없어 지운 원천({@code SEOUL_METRO_FEED}) 행 수
	 * @param adminVerifiedAbsent 이번 회차 관측에 없지만 관리자 확인이라 남긴 행 수
	 */
	record FeedApplyResult(int written, int keptAdminVerified, int removed, int adminVerifiedAbsent) {
	}
}

package com.easysubway.transit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.transit.domain.FacilityOperationalStatusPriority.FeedAction;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("시설 운영 상태 우선순위(원천 수집 vs 관리자 확인)")
class FacilityOperationalStatusPriorityTest {

	private static final String ID = "smrt-elev:0201:2:1번 출입구";
	private static final Instant T0 = Instant.parse("2026-09-30T01:00:00Z");
	private static final Instant ADMIN_AT = Instant.parse("2026-09-30T01:00:30Z");
	private static final Instant T1 = Instant.parse("2026-09-30T01:01:00Z");

	@Test
	@DisplayName("상태가 없는 시설은 원천 관측을 새로 쓴다")
	void insertsWhenNoStatusExists() {
		assertThat(FacilityOperationalStatusPriority.resolveFeed(null, "M", T0)).isEqualTo(FeedAction.INSERT);
	}

	@Test
	@DisplayName("원천 상태는 같은 시각 이후의 원천 관측으로 갱신하고, 더 오래된 관측은 버린다")
	void feedStatusFollowsNewerFeedObservation() {
		var feed = feed(FacilityOperationalState.OPERATING, "M", T0);

		assertThat(FacilityOperationalStatusPriority.resolveFeed(feed, "M", T1)).isEqualTo(FeedAction.OVERWRITE);
		assertThat(FacilityOperationalStatusPriority.resolveFeed(feed, "S", T1)).isEqualTo(FeedAction.OVERWRITE);
		assertThat(FacilityOperationalStatusPriority.resolveFeed(feed, "S", T0)).isEqualTo(FeedAction.OVERWRITE);
		assertThat(FacilityOperationalStatusPriority.resolveFeed(feed, "S", T0.minusSeconds(1))).isEqualTo(FeedAction.KEEP);
	}

	@Test
	@DisplayName("관리자가 방금 고장 확인한 시설은 원천이 이전과 같은 '사용가능'을 계속 보내도 덮어쓰지 않는다")
	void adminOutageIsKeptWhileFeedRepeatsOperating() {
		var admin = admin(FacilityOperationalState.OUT_OF_SERVICE, "M", ADMIN_AT);

		assertThat(FacilityOperationalStatusPriority.resolveFeed(admin, "M", T1)).isEqualTo(FeedAction.KEEP);
	}

	@Test
	@DisplayName("관리자가 복구 확인한 시설은 원천이 이전과 같은 '보수중'을 계속 보내도 덮어쓰지 않는다")
	void adminRecoveryIsKeptWhileFeedRepeatsOutage() {
		var admin = admin(FacilityOperationalState.OPERATING, "S", ADMIN_AT);

		assertThat(FacilityOperationalStatusPriority.resolveFeed(admin, "S", T1)).isEqualTo(FeedAction.KEEP);
	}

	@Test
	@DisplayName("반대 방향: 관리자 확인 뒤에 원천 값이 바뀐 관측은 관리자 확인보다 최근이므로 원천 값으로 바꾼다")
	void feedChangeObservedAfterAdminVerificationOverwrites() {
		var adminOutage = admin(FacilityOperationalState.OUT_OF_SERVICE, "S", ADMIN_AT);
		var adminRecovery = admin(FacilityOperationalState.OPERATING, "S", ADMIN_AT);

		assertThat(FacilityOperationalStatusPriority.resolveFeed(adminOutage, "M", T1)).isEqualTo(FeedAction.OVERWRITE);
		assertThat(FacilityOperationalStatusPriority.resolveFeed(adminRecovery, "T", T1)).isEqualTo(FeedAction.OVERWRITE);
	}

	@Test
	@DisplayName("원천 값 변화가 관리자 확인 시각 이전(같은 시각 포함)에 관측됐으면 관리자 확인을 유지하고 원천 코드만 기록한다")
	void feedChangeObservedNotAfterAdminVerificationOnlyRecordsSourceCode() {
		var admin = admin(FacilityOperationalState.OUT_OF_SERVICE, "M", ADMIN_AT);

		assertThat(FacilityOperationalStatusPriority.resolveFeed(admin, "S", ADMIN_AT)).isEqualTo(FeedAction.RECORD_SOURCE_CODE);
		assertThat(FacilityOperationalStatusPriority.resolveFeed(admin, "S", T0)).isEqualTo(FeedAction.RECORD_SOURCE_CODE);
	}

	@Test
	@DisplayName("원천 관측 전에 관리자 확인이 먼저 있었으면 첫 원천 코드는 기준값으로만 기록한다")
	void firstFeedCodeAfterAdminOnlyRecordIsBaseline() {
		var admin = admin(FacilityOperationalState.OUT_OF_SERVICE, null, ADMIN_AT);

		assertThat(FacilityOperationalStatusPriority.resolveFeed(admin, "M", T1)).isEqualTo(FeedAction.RECORD_SOURCE_CODE);
	}

	@Test
	@DisplayName("원천 코드와 관측 시각은 비울 수 없다")
	void rejectsMissingInputs() {
		assertThatThrownBy(() -> FacilityOperationalStatusPriority.resolveFeed(null, null, T0))
			.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> FacilityOperationalStatusPriority.resolveFeed(null, "M", null))
			.isInstanceOf(NullPointerException.class);
	}

	@Test
	@DisplayName("운영 상태 행은 필수 값을 요구한다")
	void statusRowRequiresMandatoryValues() {
		assertThatThrownBy(() -> new FacilityOperationalStatus(null, FacilityOperationalState.OPERATING,
			FacilityStatusSource.SEOUL_METRO_FEED, "M", T0, T0)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new FacilityOperationalStatus(ID, null,
			FacilityStatusSource.SEOUL_METRO_FEED, "M", T0, T0)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new FacilityOperationalStatus(ID, FacilityOperationalState.OPERATING,
			null, "M", T0, T0)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new FacilityOperationalStatus(ID, FacilityOperationalState.OPERATING,
			FacilityStatusSource.SEOUL_METRO_FEED, "M", null, T0)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new FacilityOperationalStatus(ID, FacilityOperationalState.OPERATING,
			FacilityStatusSource.SEOUL_METRO_FEED, "M", T0, null)).isInstanceOf(NullPointerException.class);
	}

	private static FacilityOperationalStatus feed(FacilityOperationalState state, String code, Instant observedAt) {
		return new FacilityOperationalStatus(ID, state, FacilityStatusSource.SEOUL_METRO_FEED, code, observedAt, observedAt);
	}

	private static FacilityOperationalStatus admin(FacilityOperationalState state, String code, Instant observedAt) {
		return new FacilityOperationalStatus(ID, state, FacilityStatusSource.ADMIN_VERIFIED, code, observedAt, observedAt);
	}
}

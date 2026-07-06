package com.easysubway.datapack.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DatapackReleaseRequest 도메인")
class DatapackReleaseRequestTest {

	private static final String SHA = "a".repeat(64);
	private static final LocalDateTime T0 = LocalDateTime.parse("2026-07-06T00:00:00");

	private DatapackReleaseRequest requested(String requester) {
		return DatapackReleaseRequest.requested(
			"appr-1", "cand-1", "scope-1", "staging", SHA, SHA, SHA, requester, T0);
	}

	@Test
	@DisplayName("requested 팩토리는 status=REQUESTED, approvedBy=null")
	void requestedFactory() {
		var r = requested("alice");
		assertThat(r.status()).isEqualTo(DatapackReleaseRequestStatus.REQUESTED);
		assertThat(r.approvedBy()).isNull();
		assertThat(r.approvedAt()).isNull();
	}

	@Test
	@DisplayName("승인자는 요청자와 달라야 한다")
	void approveRejectsSameActor() {
		assertThatThrownBy(() -> requested("alice").approve("alice", T0))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("approvedBy");
	}

	@Test
	@DisplayName("승인은 status=APPROVED로 전이하고 approvedBy/at를 채운다")
	void approveTransitions() {
		var approved = requested("alice").approve("bob", T0);
		assertThat(approved.status()).isEqualTo(DatapackReleaseRequestStatus.APPROVED);
		assertThat(approved.approvedBy()).isEqualTo("bob");
		assertThat(approved.approvedAt()).isEqualTo(T0);
	}

	@Test
	@DisplayName("이미 승인된 요청은 재승인 불가")
	void approveOnlyFromRequested() {
		var approved = requested("alice").approve("bob", T0);
		assertThatThrownBy(() -> approved.approve("carol", T0))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("대문자 sha는 소문자로 정규화된다")
	void normalizesSha() {
		var r = DatapackReleaseRequest.requested(
			"appr-1", "cand-1", "scope-1", "staging",
			"A".repeat(64), SHA, SHA, "alice", T0);
		assertThat(r.buildSpecSha256()).isEqualTo("a".repeat(64));
	}

	@Test
	@DisplayName("상태 전이 규칙: REQUESTED→APPROVED 허용, REQUESTED→PUBLISHED 불가")
	void transitionRules() {
		assertThat(DatapackReleaseRequestStatus.REQUESTED
			.canTransitionTo(DatapackReleaseRequestStatus.APPROVED)).isTrue();
		assertThat(DatapackReleaseRequestStatus.REQUESTED
			.canTransitionTo(DatapackReleaseRequestStatus.PUBLISHED)).isFalse();
	}
}

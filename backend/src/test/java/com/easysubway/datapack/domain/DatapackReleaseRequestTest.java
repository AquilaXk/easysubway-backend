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
	private static final LocalDateTime AT = LocalDateTime.of(2026, 7, 6, 12, 0);

	private static DatapackReleaseRequest requestIn(DatapackReleaseRequestStatus status) {
		String sha = "a".repeat(64);
		return new DatapackReleaseRequest("rr-1", "cand-1", "scope-1", "production",
			sha, sha, sha, "alice", "bob", status, "rr-1", null, AT, AT, AT, null, null);
	}

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

	@Test
	@DisplayName("markDispatched는 APPROVED→DISPATCHED로 전이하고 workflowRunUrl·idempotencyKey를 세팅한다")
	void markDispatchedTransitions() {
		var dispatched = requested("alice").approve("bob", T0)
			.markDispatched("https://gh/run/1", "appr-1", T0);
		assertThat(dispatched.status()).isEqualTo(DatapackReleaseRequestStatus.DISPATCHED);
		assertThat(dispatched.workflowRunUrl()).isEqualTo("https://gh/run/1");
		assertThat(dispatched.dispatchIdempotencyKey()).isEqualTo("appr-1");
		assertThat(dispatched.approvedBy()).isEqualTo("bob");
	}

	@Test
	@DisplayName("markDispatchFailed는 DISPATCH_FAILED로 전이하고, 거기서 markDispatched 재시도가 허용된다")
	void markDispatchFailedThenRetry() {
		var failed = requested("alice").approve("bob", T0).markDispatchFailed("appr-1", T0);
		assertThat(failed.status()).isEqualTo(DatapackReleaseRequestStatus.DISPATCH_FAILED);

		var retried = failed.markDispatched("https://gh/run/2", "appr-1", T0);
		assertThat(retried.status()).isEqualTo(DatapackReleaseRequestStatus.DISPATCHED);
		assertThat(retried.workflowRunUrl()).isEqualTo("https://gh/run/2");
	}

	@Test
	@DisplayName("REQUESTED에서 markDispatched는 상태 위반으로 거부한다")
	void markDispatchedRejectsFromRequested() {
		assertThatThrownBy(() -> requested("alice").markDispatched("https://gh/run/1", "appr-1", T0))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("markPublished는 DISPATCHED·APPROVED에서 PUBLISHED로 전이하고 workflowRunUrl을 세팅한다")
	void markPublishedTransitions() {
		var dispatched = requestIn(DatapackReleaseRequestStatus.DISPATCHED);
		var published = dispatched.markPublished("https://run/1", AT);
		assertThat(published.status()).isEqualTo(DatapackReleaseRequestStatus.PUBLISHED);
		assertThat(published.workflowRunUrl()).isEqualTo("https://run/1");

		var approved = requestIn(DatapackReleaseRequestStatus.APPROVED);
		assertThat(approved.markPublished("https://run/2", AT).status())
			.isEqualTo(DatapackReleaseRequestStatus.PUBLISHED);
	}

	@Test
	@DisplayName("markFailed는 FAILED로 전이하고 promoteDetail에 사유를 남긴다")
	void markFailedTransitions() {
		var failed = requestIn(DatapackReleaseRequestStatus.DISPATCHED).markFailed("publish BLOCKED_EXTERNAL", AT);
		assertThat(failed.status()).isEqualTo(DatapackReleaseRequestStatus.FAILED);
		assertThat(failed.promoteDetail()).contains("BLOCKED_EXTERNAL");
	}

	@Test
	@DisplayName("withPromoteOutcome는 상태를 바꾸지 않고 promoteOutcome·promoteDetail만 기록한다")
	void withPromoteOutcomeKeepsStatus() {
		var published = requestIn(DatapackReleaseRequestStatus.DISPATCHED).markPublished("https://run/1", AT);
		var recorded = published.withPromoteOutcome("REJECTED", "evidence bundle not registered");
		assertThat(recorded.status()).isEqualTo(DatapackReleaseRequestStatus.PUBLISHED);
		assertThat(recorded.promoteOutcome()).isEqualTo("REJECTED");
		assertThat(recorded.promoteDetail()).contains("evidence bundle");
	}
}

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
	@DisplayName("이력 행 호환을 위해 유지되는 dispatch 진입 전이는 여전히 허용된다(REQUESTED→DISPATCHED는 불가)")
	void dispatchEntryTransitionsRemainAllowed() {
		assertThat(DatapackReleaseRequestStatus.APPROVED
			.canTransitionTo(DatapackReleaseRequestStatus.DISPATCHED)).isTrue();
		assertThat(DatapackReleaseRequestStatus.APPROVED
			.canTransitionTo(DatapackReleaseRequestStatus.DISPATCH_FAILED)).isTrue();
		assertThat(DatapackReleaseRequestStatus.DISPATCH_FAILED
			.canTransitionTo(DatapackReleaseRequestStatus.DISPATCHED)).isTrue();
		assertThat(DatapackReleaseRequestStatus.REQUESTED
			.canTransitionTo(DatapackReleaseRequestStatus.DISPATCHED)).isFalse();
	}

	@Test
	@DisplayName("DISPATCH_FAILED 이력 행은 수동 게시 결과로 PUBLISHED·FAILED까지 종결할 수 있다")
	void dispatchFailedCanReachTerminal() {
		var failed = requestIn(DatapackReleaseRequestStatus.DISPATCH_FAILED);

		assertThat(failed.markPublished("https://run/3", AT).status())
			.isEqualTo(DatapackReleaseRequestStatus.PUBLISHED);
		assertThat(failed.markFailed("publish BLOCKED_EXTERNAL", AT).status())
			.isEqualTo(DatapackReleaseRequestStatus.FAILED);
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

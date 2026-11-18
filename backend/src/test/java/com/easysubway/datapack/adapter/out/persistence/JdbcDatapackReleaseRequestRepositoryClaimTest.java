package com.easysubway.datapack.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.datapack.domain.DatapackReleaseRequest;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@DisplayName("JdbcDatapackReleaseRequestRepository claim")
class JdbcDatapackReleaseRequestRepositoryClaimTest {

	private static final String SHA = "a".repeat(64);
	private static final LocalDateTime T0 = LocalDateTime.parse("2026-07-06T00:00:00");

	@Test
	@DisplayName("select 뒤 다른 worker가 먼저 claim하면 loser는 request를 반환하지 않는다")
	void skipsCandidateWhenAnotherWorkerWinsAfterSelection() {
		var candidate = DatapackReleaseRequest.requested(
			"appr-1", "cand-1", "scope-1", "production",
			SHA, SHA, SHA, "alice", T0).approve("bob", T0);
		var losingJdbc = new JdbcTemplate() {
			@Override
			@SuppressWarnings("unchecked")
			public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
				return List.of((T) candidate);
			}

			@Override
			public int update(String sql, Object... args) {
				return 0;
			}
		};
		var repository = new JdbcDatapackReleaseRequestRepository(losingJdbc);

		assertThat(repository.claimReconciliationDue(
			T0.plusMinutes(10), T0.plusMinutes(10), T0.plusMinutes(20), 100)).isEmpty();
	}

	@Test
	@DisplayName("다건 후보 배치 선점 시 갱신 성공(1) 및 SUCCESS_NO_INFO(-2)만 반환하고 경합 패배(0)는 제외한다")
	void claimsMultiCandidateBatchPreservingWinnerAndSuccessNoInfoOnly() {
		var first = DatapackReleaseRequest.requested(
			"appr-1", "cand-1", "scope-1", "production", SHA, SHA, SHA, "alice", T0).approve("bob", T0);
		var second = DatapackReleaseRequest.requested(
			"appr-2", "cand-2", "scope-1", "production", SHA, SHA, SHA, "alice", T0).approve("bob", T0);
		var third = DatapackReleaseRequest.requested(
			"appr-3", "cand-3", "scope-1", "production", SHA, SHA, SHA, "alice", T0).approve("bob", T0);
		var batchJdbc = new JdbcTemplate() {
			@Override
			@SuppressWarnings("unchecked")
			public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
				return List.of((T) first, (T) second, (T) third);
			}

			@Override
			public int[] batchUpdate(String sql, List<Object[]> batchArgs) {
				assertThat(batchArgs).hasSize(3);
				return new int[] { 1, 0, -2 };
			}
		};
		var repository = new JdbcDatapackReleaseRequestRepository(batchJdbc);

		assertThat(repository.claimReconciliationDue(
			T0.plusMinutes(10), T0.plusMinutes(10), T0.plusMinutes(20), 100))
			.containsExactly(first, third);
	}
}

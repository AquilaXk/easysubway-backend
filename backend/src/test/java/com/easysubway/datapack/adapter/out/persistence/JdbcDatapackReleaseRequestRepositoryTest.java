package com.easysubway.datapack.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.datapack.application.port.out.DatapackReleaseRequestRepository;
import com.easysubway.datapack.domain.DatapackReleaseRequest;
import com.easysubway.datapack.domain.DatapackReleaseRequestStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@DisplayName("JdbcDatapackReleaseRequestRepository")
class JdbcDatapackReleaseRequestRepositoryTest {

	private static final String SHA = "a".repeat(64);
	private static final LocalDateTime T0 = LocalDateTime.parse("2026-07-06T00:00:00");

	@Autowired
	private DatapackReleaseRequestRepository repository;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM datapack_release_request");
	}

	@Test
	@DisplayName("save 후 findByApprovalId가 모든 필드를 왕복한다")
	void savesAndReads() {
		repository.save(DatapackReleaseRequest.requested(
			"appr-1", "cand-1", "scope-1", "staging", SHA, SHA, SHA, "alice", T0));

		var found = repository.findByApprovalId("appr-1").orElseThrow();
		assertThat(found.candidateId()).isEqualTo("cand-1");
		assertThat(found.status()).isEqualTo(DatapackReleaseRequestStatus.REQUESTED);
		assertThat(found.approvedBy()).isNull();
	}

	@Test
	@DisplayName("save는 approval_id 기준 upsert (승인 결과 반영)")
	void upserts() {
		repository.save(DatapackReleaseRequest.requested(
			"appr-1", "cand-1", "scope-1", "staging", SHA, SHA, SHA, "alice", T0));
		repository.save(repository.findByApprovalId("appr-1").orElseThrow().approve("bob", T0));

		var found = repository.findByApprovalId("appr-1").orElseThrow();
		assertThat(found.status()).isEqualTo(DatapackReleaseRequestStatus.APPROVED);
		assertThat(found.approvedBy()).isEqualTo("bob");
	}

	@Test
	@DisplayName("없는 approvalId는 empty")
	void emptyWhenMissing() {
		assertThat(repository.findByApprovalId("nope")).isEmpty();
	}
}

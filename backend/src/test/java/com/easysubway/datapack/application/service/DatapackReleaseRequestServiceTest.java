package com.easysubway.datapack.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.datapack.application.port.out.DatapackWorkflowDispatchPort;
import com.easysubway.datapack.application.service.DatapackReleaseRequestService.CreateReleaseRequestCommand;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@DisplayName("DatapackReleaseRequestService")
class DatapackReleaseRequestServiceTest {

	private static final String SHA = "a".repeat(64);

	@Autowired
	private DatapackReleaseRequestService service;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private RecordingDispatchPort dispatchPort;
	@Autowired
	private TxRollbackHelper txRollbackHelper;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM datapack_release_request");
		dispatchPort.reset();
	}

	private CreateReleaseRequestCommand cmd(String requester) {
		return new CreateReleaseRequestCommand("cand-1", "scope-1", "staging", SHA, SHA, SHA, requester);
	}

	@Test
	@DisplayName("create는 REQUESTED 요청을 저장하고 approvalId를 발급한다")
	void createsRequest() {
		var created = service.create(cmd("alice"));
		assertThat(created.approvalId()).startsWith("release-request-");
		assertThat(created.requestedBy()).isEqualTo("alice");
		assertThat(created.approvedBy()).isNull();
	}

	@Test
	@DisplayName("승인자=요청자이면 거부")
	void approveRejectsSameActor() {
		var created = service.create(cmd("alice"));
		assertThatThrownBy(() -> service.approve(created.approvalId(), "alice"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("approvedBy");
	}

	@Test
	@DisplayName("findApproved는 미승인·종결 상태=empty, APPROVED와 dispatch 계열 이력 행=present")
	void findApprovedServesApprovedAndDispatched() {
		// 미승인 → empty
		var dormant = service.create(cmd("alice"));
		assertThat(service.findApproved(dormant.approvalId())).isEmpty();

		// 승인 → APPROVED → present
		service.approve(dormant.approvalId(), "bob");
		assertThat(service.findApproved(dormant.approvalId())).isPresent();

		// 과거 자동 dispatch가 남긴 DISPATCHED 이력 행 → 여전히 present(워크플로가 페이로드를 fetch)
		var dispatched = service.create(cmd("alice"));
		service.approve(dispatched.approvalId(), "bob");
		setStatus(dispatched.approvalId(), "DISPATCHED");
		assertThat(service.findApproved(dispatched.approvalId())).isPresent();

		// DISPATCH_FAILED 이력 행 → present(재시도 수단이 사라졌으므로 수동 게시로 종결할 수 있어야 함)
		var failed = service.create(cmd("alice"));
		service.approve(failed.approvalId(), "bob");
		setStatus(failed.approvalId(), "DISPATCH_FAILED");
		assertThat(service.findApproved(failed.approvalId())).isPresent();

		// 종결 상태 → empty(더 진행할 게시가 없음)
		var published = service.create(cmd("alice"));
		service.approve(published.approvalId(), "bob");
		setStatus(published.approvalId(), "PUBLISHED");
		assertThat(service.findApproved(published.approvalId())).isEmpty();
	}

	@Test
	@DisplayName("승인은 APPROVED 기록까지만 하고 release workflow를 dispatch하지 않는다")
	void approveDoesNotDispatch() {
		var created = service.create(cmd("alice"));

		var approved = service.approve(created.approvalId(), "bob");

		assertThat(approved.status().name()).isEqualTo("APPROVED");
		assertThat(status(created.approvalId())).isEqualTo("APPROVED");
		assertThat(dispatchIdempotencyKey(created.approvalId())).isNull();
		assertThat(dispatchPort.commands()).isEmpty();
	}

	@Test
	@DisplayName("승인 트랜잭션이 롤백되면 REQUESTED로 남는다")
	void rollbackKeepsRequested() {
		var created = service.create(cmd("alice"));

		assertThatThrownBy(() -> txRollbackHelper.approveThenFail(created.approvalId(), "bob"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("boom");

		assertThat(dispatchPort.commands()).isEmpty();
		assertThat(status(created.approvalId())).isEqualTo("REQUESTED");
	}

	private String status(String approvalId) {
		return jdbcTemplate.queryForObject(
			"SELECT status FROM datapack_release_request WHERE approval_id = ?", String.class, approvalId);
	}

	private String dispatchIdempotencyKey(String approvalId) {
		return jdbcTemplate.queryForObject(
			"SELECT dispatch_idempotency_key FROM datapack_release_request WHERE approval_id = ?",
			String.class, approvalId);
	}

	// backend가 더 이상 만들지 않는 dispatch 계열 상태(이력 행)를 read 경로 검증용으로 재현한다.
	private void setStatus(String approvalId, String status) {
		jdbcTemplate.update(
			"UPDATE datapack_release_request SET status = ? WHERE approval_id = ?", status, approvalId);
	}

	@TestConfiguration
	static class DispatchStubConfiguration {

		@Bean
		@Primary
		RecordingDispatchPort recordingDispatchPort() {
			return new RecordingDispatchPort();
		}

		@Bean
		TxRollbackHelper txRollbackHelper(DatapackReleaseRequestService service) {
			return new TxRollbackHelper(service);
		}
	}

	/** dispatch 호출을 기록하는 포트 테스트 이중 — 승인 경로가 다시 발화하면 commands가 비지 않는다. */
	static class RecordingDispatchPort implements DatapackWorkflowDispatchPort {

		private final List<DispatchCommand> commands = new ArrayList<>();

		@Override
		public DispatchResult dispatch(DispatchCommand command) {
			commands.add(command);
			return DispatchResult.skippedResult();
		}

		List<DispatchCommand> commands() {
			return commands;
		}

		void reset() {
			commands.clear();
		}
	}

	/** approve 후 예외를 던져 트랜잭션을 롤백시키는 헬퍼. */
	static class TxRollbackHelper {

		private final DatapackReleaseRequestService service;

		TxRollbackHelper(DatapackReleaseRequestService service) {
			this.service = service;
		}

		@Transactional
		public void approveThenFail(String approvalId, String approver) {
			service.approve(approvalId, approver);
			throw new IllegalStateException("boom");
		}
	}
}

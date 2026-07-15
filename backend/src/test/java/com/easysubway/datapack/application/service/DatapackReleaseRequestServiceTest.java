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
	@DisplayName("findApproved는 미승인=empty, APPROVED(dormant)·DISPATCHED=present, DISPATCH_FAILED=empty")
	void findApprovedServesApprovedAndDispatched() {
		// 미승인 → empty
		var dormant = service.create(cmd("alice"));
		assertThat(service.findApproved(dormant.approvalId())).isEmpty();

		// dormant 승인(skip) → APPROVED 유지 → present
		dispatchPort.willReturn(DatapackWorkflowDispatchPort.DispatchResult.skippedResult());
		service.approve(dormant.approvalId(), "bob");
		assertThat(service.findApproved(dormant.approvalId())).isPresent();

		// 자동 dispatch 성공 → DISPATCHED → 여전히 present(트리거된 워크플로가 페이로드를 fetch해야 함)
		var dispatched = service.create(cmd("alice"));
		dispatchPort.willReturn(DatapackWorkflowDispatchPort.DispatchResult.succeeded("stub ok"));
		service.approve(dispatched.approvalId(), "bob");
		assertThat(status(dispatched.approvalId())).isEqualTo("DISPATCHED");
		assertThat(service.findApproved(dispatched.approvalId())).isPresent();

		// dispatch 실패 → DISPATCH_FAILED → empty(미발화이므로 서빙 안 함)
		var failed = service.create(cmd("alice"));
		dispatchPort.willReturn(DatapackWorkflowDispatchPort.DispatchResult.failed("HTTP 500"));
		service.approve(failed.approvalId(), "bob");
		assertThat(status(failed.approvalId())).isEqualTo("DISPATCH_FAILED");
		assertThat(service.findApproved(failed.approvalId())).isEmpty();
	}

	@Test
	@DisplayName("승인 커밋 후 dispatch 성공이면 DISPATCHED로 전이하고 4필드 command를 보낸다")
	void approveDispatchesAfterCommit() {
		var created = service.create(cmd("alice"));
		dispatchPort.willReturn(DatapackWorkflowDispatchPort.DispatchResult.succeeded("stub ok"));

		service.approve(created.approvalId(), "bob");

		assertThat(status(created.approvalId())).isEqualTo("DISPATCHED");
		assertThat(dispatchIdempotencyKey(created.approvalId())).isEqualTo(created.approvalId());
		assertThat(dispatchPort.commands()).hasSize(1);
		var command = dispatchPort.commands().getFirst();
		assertThat(command.targetChannel()).isEqualTo("staging");
		assertThat(command.releaseRequestId()).isEqualTo(created.approvalId());
		assertThat(command.buildSpecPath()).isEqualTo("tools/datapack/fixtures/candidate-build-spec.json");
	}

	@Test
	@DisplayName("dispatch 실패면 DISPATCH_FAILED로 전이한다")
	void approveMarksDispatchFailedOnFailure() {
		var created = service.create(cmd("alice"));
		dispatchPort.willReturn(DatapackWorkflowDispatchPort.DispatchResult.failed("HTTP 500"));

		service.approve(created.approvalId(), "bob");

		assertThat(status(created.approvalId())).isEqualTo("DISPATCH_FAILED");
	}

	@Test
	@DisplayName("dispatch skip(토큰 미설정)이면 APPROVED 상태를 유지한다")
	void approveKeepsApprovedWhenDispatchSkipped() {
		var created = service.create(cmd("alice"));
		dispatchPort.willReturn(DatapackWorkflowDispatchPort.DispatchResult.skippedResult());

		service.approve(created.approvalId(), "bob");

		assertThat(status(created.approvalId())).isEqualTo("APPROVED");
		assertThat(dispatchPort.commands()).hasSize(1);
	}

	@Test
	@DisplayName("승인 트랜잭션이 롤백되면 dispatch를 호출하지 않는다")
	void rollbackSuppressesDispatch() {
		var created = service.create(cmd("alice"));

		assertThatThrownBy(() -> txRollbackHelper.approveThenFail(created.approvalId(), "bob"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("boom");

		assertThat(dispatchPort.commands()).isEmpty();
		assertThat(status(created.approvalId())).isEqualTo("REQUESTED");
	}

	@Test
	@DisplayName("retryDispatch는 DISPATCH_FAILED를 재시도해 성공 시 DISPATCHED로 전이한다")
	void retryDispatchRecoversFailure() {
		var created = service.create(cmd("alice"));
		dispatchPort.willReturn(DatapackWorkflowDispatchPort.DispatchResult.failed("HTTP 500"));
		service.approve(created.approvalId(), "bob");
		assertThat(status(created.approvalId())).isEqualTo("DISPATCH_FAILED");

		dispatchPort.willReturn(DatapackWorkflowDispatchPort.DispatchResult.succeeded("stub ok"));
		var retried = service.retryDispatch(created.approvalId());

		assertThat(retried.status().name()).isEqualTo("DISPATCHED");
		assertThat(status(created.approvalId())).isEqualTo("DISPATCHED");
	}

	@Test
	@DisplayName("retryDispatch는 DISPATCH_FAILED가 아니면 거절한다")
	void retryDispatchRejectsNonFailedState() {
		var created = service.create(cmd("alice"));
		dispatchPort.willReturn(DatapackWorkflowDispatchPort.DispatchResult.succeeded("stub ok"));
		service.approve(created.approvalId(), "bob");

		assertThatThrownBy(() -> service.retryDispatch(created.approvalId()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("DISPATCH_FAILED");
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

	/** 명령을 기록하고 다음 결과를 지정 가능한 dispatch 포트 테스트 이중. */
	static class RecordingDispatchPort implements DatapackWorkflowDispatchPort {

		private final List<DispatchCommand> commands = new ArrayList<>();
		private volatile DispatchResult nextResult = DispatchResult.succeeded("stub ok");

		@Override
		public DispatchResult dispatch(DispatchCommand command) {
			commands.add(command);
			return nextResult;
		}

		void willReturn(DispatchResult result) {
			this.nextResult = result;
		}

		List<DispatchCommand> commands() {
			return commands;
		}

		void reset() {
			commands.clear();
			nextResult = DispatchResult.succeeded("stub ok");
		}
	}

	/** approve 후 예외를 던져 트랜잭션을 롤백시키는 헬퍼(afterCommit 미발화 검증용). */
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

package com.easysubway.datapack.application.service;

import com.easysubway.datapack.application.port.out.DatapackReleaseRequestRepository;
import com.easysubway.datapack.application.port.out.DatapackWorkflowDispatchPort;
import com.easysubway.datapack.application.port.out.DatapackWorkflowDispatchPort.DispatchCommand;
import com.easysubway.datapack.application.port.out.DatapackWorkflowDispatchPort.DispatchResult;
import com.easysubway.datapack.domain.DatapackReleaseRequest;
import com.easysubway.datapack.domain.DatapackReleaseRequestStatus;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class DatapackReleaseRequestService {

	private static final Logger log = LoggerFactory.getLogger(DatapackReleaseRequestService.class);

	private final DatapackReleaseRequestRepository repository;
	private final DatapackWorkflowDispatchPort dispatchPort;
	private final ObjectProvider<DatapackReleaseRequestService> selfProvider;
	private final Clock clock;
	private final String buildSpecPath;

	public DatapackReleaseRequestService(
		DatapackReleaseRequestRepository repository,
		DatapackWorkflowDispatchPort dispatchPort,
		ObjectProvider<DatapackReleaseRequestService> selfProvider,
		ObjectProvider<Clock> clockProvider,
		@Value("${easysubway.datapack.build-spec-path:tools/datapack/fixtures/candidate-build-spec.json}")
		String buildSpecPath
	) {
		this.repository = repository;
		this.dispatchPort = dispatchPort;
		this.selfProvider = selfProvider;
		this.clock = clockProvider.getIfAvailable(Clock::systemDefaultZone);
		this.buildSpecPath = buildSpecPath;
	}

	@Transactional
	public DatapackReleaseRequest create(CreateReleaseRequestCommand command) {
		command.validate();
		var now = LocalDateTime.now(clock);
		var request = DatapackReleaseRequest.requested(
			"release-request-" + UUID.randomUUID(),
			command.candidateId(), command.scopeId(), command.targetChannel(),
			command.buildSpecSha256(), command.sourceSnapshotSetHash(), command.approvedLedgerHash(),
			command.requestedBy(), now);
		repository.save(request);
		return request;
	}

	@Transactional
	public DatapackReleaseRequest approve(String approvalId, String approver) {
		var request = repository.findByApprovalId(approvalId)
			.orElseThrow(() -> new IllegalArgumentException("release request not found: " + approvalId));
		var approved = request.approve(approver, LocalDateTime.now(clock));
		repository.save(approved);
		registerDispatchAfterCommit(approved);
		return approved;
	}

	/**
	 * DISPATCH_FAILED 상태의 승인 건을 재시도해 workflow_dispatch를 다시 트리거한다.
	 * 승인과 달리 사용자 트리거이므로 즉시(동기) 실행한다.
	 */
	public DatapackReleaseRequest retryDispatch(String approvalId) {
		var request = repository.findByApprovalId(approvalId)
			.orElseThrow(() -> new IllegalArgumentException("release request not found: " + approvalId));
		if (request.status() != DatapackReleaseRequestStatus.DISPATCH_FAILED) {
			throw new IllegalStateException(
				"retry is only allowed from DISPATCH_FAILED but was: " + request.status());
		}
		dispatchAndPersist(approvalId, toDispatchCommand(request));
		return repository.findByApprovalId(approvalId)
			.orElseThrow(() -> new IllegalArgumentException("release request not found: " + approvalId));
	}

	// 승인 트랜잭션이 커밋된 뒤에만 dispatch한다 — 롤백된 승인은 발화하지 않는다.
	private void registerDispatchAfterCommit(DatapackReleaseRequest approved) {
		var approvalId = approved.approvalId();
		var command = toDispatchCommand(approved);
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					// 승인은 이미 커밋됐다 — afterCommit 예외를 approve() 호출자로 전파하면(500) 승인
					// 실패로 오인된다. dispatch/persist 실패는 여기서 격리·로깅하고 DISPATCH_FAILED로
					// 남겨 UI 재시도 경로로 흡수한다.
					try {
						selfProvider.getObject().dispatchAndPersist(approvalId, command);
					} catch (RuntimeException e) {
						log.error("release request {} dispatch after commit failed", approvalId, e);
					}
				}
			});
		} else {
			dispatchAndPersist(approvalId, command);
		}
	}

	/**
	 * 포트로 dispatch한 뒤 결과에 따라 상태를 전이·저장한다. after-commit 콜백에서 호출되므로
	 * public(프록시 경유)이며, HTTP 호출은 트랜잭션 밖에서 수행하고 저장만 별도 트랜잭션으로 커밋한다.
	 */
	public void dispatchAndPersist(String approvalId, DispatchCommand command) {
		var result = dispatchPort.dispatch(command);
		if (result.skipped()) {
			return; // 토큰 미설정 — status APPROVED 유지, 수동 실행 필요
		}
		selfProvider.getObject().persistDispatchResult(approvalId, result);
	}

	@Transactional
	public void persistDispatchResult(String approvalId, DispatchResult result) {
		var request = repository.findByApprovalId(approvalId)
			.orElseThrow(() -> new IllegalArgumentException("release request not found: " + approvalId));
		var now = LocalDateTime.now(clock);
		if (result.ok()) {
			repository.save(request.markDispatched(null, approvalId, now));
		} else if (request.status().canTransitionTo(DatapackReleaseRequestStatus.DISPATCH_FAILED)) {
			repository.save(request.markDispatchFailed(approvalId, now));
		}
		// 이미 DISPATCH_FAILED에서 재시도가 또 실패하면 상태를 그대로 둔다(멱등).
	}

	private DispatchCommand toDispatchCommand(DatapackReleaseRequest request) {
		return new DispatchCommand(request.targetChannel(), request.approvalId(), buildSpecPath);
	}

	// 워크플로가 fetch 가능한 상태: 승인됨(APPROVED, dormant/수동) + 자동 dispatch 완료(DISPATCHED).
	// dispatch가 성공하면 status는 DISPATCHED로 전이하므로, APPROVED만 서빙하면 트리거된 워크플로가
	// 정작 자기 release request 페이로드를 404로 받는다(자동화 자기모순). DISPATCH_FAILED/FAILED 등
	// 미발화·실패 상태는 서빙 대상이 아니다.
	private static final Set<DatapackReleaseRequestStatus> SERVABLE_STATUSES =
		Set.of(DatapackReleaseRequestStatus.APPROVED, DatapackReleaseRequestStatus.DISPATCHED);

	@Transactional(readOnly = true)
	public Optional<DatapackReleaseRequest> findApproved(String approvalId) {
		return repository.findByApprovalId(approvalId)
			.filter(r -> SERVABLE_STATUSES.contains(r.status()));
	}

	public record CreateReleaseRequestCommand(
		String candidateId,
		String scopeId,
		String targetChannel,
		String buildSpecSha256,
		String sourceSnapshotSetHash,
		String approvedLedgerHash,
		String requestedBy
	) {

		private void validate() {
			requireText(candidateId, "candidateId");
			requireText(scopeId, "scopeId");
			requireText(requestedBy, "requestedBy");
			if (!Set.of("dev", "staging", "production").contains(targetChannel)) {
				throw new IllegalArgumentException("targetChannel must be dev|staging|production");
			}
			// sha 형식 검증·소문자 정규화는 도메인 record 생성자가 수행한다.
		}

		private static void requireText(String value, String name) {
			if (value == null || value.isBlank()) {
				throw new IllegalArgumentException(name + " is required");
			}
		}
	}
}

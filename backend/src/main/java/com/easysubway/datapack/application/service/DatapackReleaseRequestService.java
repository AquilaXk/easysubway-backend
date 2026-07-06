package com.easysubway.datapack.application.service;

import com.easysubway.datapack.application.port.out.DatapackReleaseRequestRepository;
import com.easysubway.datapack.domain.DatapackReleaseRequest;
import com.easysubway.datapack.domain.DatapackReleaseRequestStatus;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DatapackReleaseRequestService {

	private final DatapackReleaseRequestRepository repository;
	private final Clock clock;

	public DatapackReleaseRequestService(
		DatapackReleaseRequestRepository repository,
		ObjectProvider<Clock> clockProvider
	) {
		this.repository = repository;
		this.clock = clockProvider.getIfAvailable(Clock::systemDefaultZone);
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
		return approved;
	}

	@Transactional(readOnly = true)
	public Optional<DatapackReleaseRequest> findApproved(String approvalId) {
		// APPROVED 상태에서만 서빙한다. approvedBy는 승인 이후 상태(DISPATCHED/FAILED 등)에도 남으므로
		// approvedBy 기준으로 넓히면 실패한 release request가 승인된 것처럼 서빙될 수 있다.
		return repository.findByApprovalId(approvalId)
			.filter(r -> r.status() == DatapackReleaseRequestStatus.APPROVED);
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

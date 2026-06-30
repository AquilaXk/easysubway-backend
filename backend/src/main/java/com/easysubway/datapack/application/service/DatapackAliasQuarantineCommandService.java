package com.easysubway.datapack.application.service;

import com.easysubway.datapack.adapter.out.persistence.JdbcAliasQuarantineQueueRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DatapackAliasQuarantineCommandService {

	private final JdbcAliasQuarantineQueueRepository repository;
	private final Clock clock;

	public DatapackAliasQuarantineCommandService(
		JdbcAliasQuarantineQueueRepository repository,
		ObjectProvider<Clock> clockProvider
	) {
		this.repository = repository;
		this.clock = clockProvider.getIfAvailable(Clock::systemDefaultZone);
	}

	@Transactional
	public void reviewAlias(String aliasId, AliasReviewCommand command) {
		command.validate();
		int updated = repository.reviewAlias(aliasId, command.approvalStatus(), command.reviewedBy(), LocalDateTime.now(clock));
		if (updated != 1) {
			throw new IllegalArgumentException("pending alias approval not found: " + aliasId);
		}
	}

	@Transactional
	public void resolveQuarantine(String recordId, QuarantineResolutionCommand command) {
		command.validate();
		var resolvedAt = LocalDateTime.now(clock);
		int updated = repository.resolveQuarantine(recordId, command.resolvedBy(), resolvedAt);
		if (updated != 1) {
			throw new IllegalArgumentException("open quarantine record not found: " + recordId);
		}
		repository.insertQuarantineResolution(
			"quarantine-resolution-" + UUID.randomUUID(),
			recordId,
			command.resolutionStatus(),
			command.resolutionReason(),
			command.resolvedBy(),
			resolvedAt,
			blankToNull(command.canonicalEntityType()),
			blankToNull(command.canonicalEntityId()),
			blankToNull(command.evidenceHash())
		);
	}

	public record AliasReviewCommand(
		String approvalStatus,
		String reviewedBy,
		String reason,
		String idempotencyKey
	) {

		private void validate() {
			if (!"APPROVED".equals(approvalStatus) && !"REJECTED".equals(approvalStatus)) {
				throw new IllegalArgumentException("approvalStatus must be APPROVED or REJECTED");
			}
			requireText(reviewedBy, "reviewedBy");
			requireText(reason, "reason");
			requireText(idempotencyKey, "idempotencyKey");
		}
	}

	public record QuarantineResolutionCommand(
		String resolutionStatus,
		String resolutionReason,
		String resolvedBy,
		String canonicalEntityType,
		String canonicalEntityId,
		String evidenceHash,
		String idempotencyKey
	) {

		private void validate() {
			requireText(resolutionStatus, "resolutionStatus");
			requireText(resolutionReason, "resolutionReason");
			requireText(resolvedBy, "resolvedBy");
			requireText(idempotencyKey, "idempotencyKey");
		}
	}

	private static void requireText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " is required");
		}
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}
}

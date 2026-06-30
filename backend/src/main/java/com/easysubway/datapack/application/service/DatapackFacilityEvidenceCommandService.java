package com.easysubway.datapack.application.service;

import com.easysubway.datapack.adapter.out.persistence.JdbcFacilityEvidenceMatrixRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DatapackFacilityEvidenceCommandService {

	private final JdbcFacilityEvidenceMatrixRepository repository;

	public DatapackFacilityEvidenceCommandService(JdbcFacilityEvidenceMatrixRepository repository) {
		this.repository = repository;
	}

	@Transactional
	public void reviewEvidence(String evidenceId, FacilityEvidenceReviewCommand command) {
		command.validate();
		int updated = repository.reviewEvidence(
			evidenceId,
			command.strictRouteEligible(),
			command.strictRouteEligibleReason(),
			command.conflictStatus()
		);
		if (updated != 1) {
			throw new IllegalArgumentException("facility evidence not found: " + evidenceId);
		}
	}

	public record FacilityEvidenceReviewCommand(
		boolean strictRouteEligible,
		String strictRouteEligibleReason,
		String conflictStatus,
		String reason,
		String idempotencyKey
	) {

		private void validate() {
			if (!"NONE".equals(conflictStatus) && !"RESOLVED".equals(conflictStatus) && !"UNRESOLVED".equals(conflictStatus)) {
				throw new IllegalArgumentException("conflictStatus is invalid");
			}
			if (strictRouteEligible && strictRouteEligibleReason != null && !strictRouteEligibleReason.isBlank()) {
				throw new IllegalArgumentException("strictRouteEligibleReason must be blank when strict route is eligible");
			}
			requireText(reason, "reason");
			requireText(idempotencyKey, "idempotencyKey");
		}
	}

	private static void requireText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " is required");
		}
	}
}

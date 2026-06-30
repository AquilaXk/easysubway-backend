package com.easysubway.datapack.application.service;

import com.easysubway.datapack.adapter.out.persistence.JdbcDatapackCandidateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DatapackCandidateCommandService {

	private final JdbcDatapackCandidateRepository repository;

	public DatapackCandidateCommandService(JdbcDatapackCandidateRepository repository) {
		this.repository = repository;
	}

	@Transactional
	public void rerunGates(String candidateId, CandidateGateRerunCommand command) {
		command.validate();
		int updated = repository.rerunGates(candidateId);
		if (updated != 1) {
			throw new IllegalArgumentException("candidate not found: " + candidateId);
		}
	}

	public record CandidateGateRerunCommand(String reason, String idempotencyKey) {

		private void validate() {
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

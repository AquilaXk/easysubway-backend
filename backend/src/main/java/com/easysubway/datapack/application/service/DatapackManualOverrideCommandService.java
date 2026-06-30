package com.easysubway.datapack.application.service;

import com.easysubway.datapack.adapter.out.persistence.JdbcManualOverrideLedgerRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DatapackManualOverrideCommandService {

	private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-fA-F]{64}");

	private final JdbcManualOverrideLedgerRepository repository;
	private final Clock clock;

	public DatapackManualOverrideCommandService(
		JdbcManualOverrideLedgerRepository repository,
		ObjectProvider<Clock> clockProvider
	) {
		this.repository = repository;
		this.clock = clockProvider.getIfAvailable(Clock::systemDefaultZone);
	}

	@Transactional
	public void request(ManualOverrideRequestCommand command) {
		command.validate();
		repository.insertRequest(
			command.id(),
			command.entityType(),
			command.entityId(),
			command.fieldName(),
			blankToNull(command.beforeValue()),
			command.afterValue(),
			command.reasonCode(),
			command.reason(),
			command.evidenceUri(),
			command.evidenceHash(),
			command.requestedBy(),
			command.strictRouteEligible(),
			command.effectiveFrom(),
			command.expiresAt(),
			LocalDateTime.now(clock)
		);
	}

	@Transactional
	public void approve(String overrideId, ManualOverrideDecisionCommand command) {
		command.validate();
		int updated = repository.approve(overrideId, command.actor(), LocalDateTime.now(clock));
		if (updated != 1) {
			throw new IllegalArgumentException("pending manual override cannot be approved: " + overrideId);
		}
	}

	@Transactional
	public void expire(String overrideId, ManualOverrideDecisionCommand command) {
		command.validate();
		int updated = repository.expire(overrideId);
		if (updated != 1) {
			throw new IllegalArgumentException("manual override cannot be expired: " + overrideId);
		}
	}

	public record ManualOverrideRequestCommand(
		String id,
		String entityType,
		String entityId,
		String fieldName,
		String beforeValue,
		String afterValue,
		String reasonCode,
		String reason,
		String evidenceUri,
		String evidenceHash,
		String requestedBy,
		boolean strictRouteEligible,
		LocalDateTime effectiveFrom,
		LocalDateTime expiresAt,
		String idempotencyKey
	) {

		private void validate() {
			requireText(id, "id");
			requireText(entityType, "entityType");
			requireText(entityId, "entityId");
			requireText(fieldName, "fieldName");
			requireText(afterValue, "afterValue");
			requireText(reasonCode, "reasonCode");
			requireText(reason, "reason");
			requireText(evidenceUri, "evidenceUri");
			requireSha(evidenceHash, "evidenceHash");
			requireText(requestedBy, "requestedBy");
			requireText(idempotencyKey, "idempotencyKey");
			if (effectiveFrom == null || expiresAt == null || !expiresAt.isAfter(effectiveFrom)) {
				throw new IllegalArgumentException("expiresAt must be after effectiveFrom");
			}
		}
	}

	public record ManualOverrideDecisionCommand(String actor, String reason, String idempotencyKey) {

		private void validate() {
			requireText(actor, "actor");
			requireText(reason, "reason");
			requireText(idempotencyKey, "idempotencyKey");
		}
	}

	private static void requireText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " is required");
		}
	}

	private static void requireSha(String value, String field) {
		requireText(value, field);
		if (!SHA256_HEX.matcher(value).matches()) {
			throw new IllegalArgumentException(field + " must be a sha256 hex string");
		}
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}
}

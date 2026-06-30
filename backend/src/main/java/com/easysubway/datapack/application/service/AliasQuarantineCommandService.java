package com.easysubway.datapack.application.service;

import com.easysubway.datapack.adapter.out.persistence.JdbcAliasQuarantineQueueRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AliasQuarantineCommandService {

	private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-fA-F]{64}");
	private static final Set<String> RESOLUTION_STATUSES = Set.of(
		"ACCEPTED",
		"REJECTED",
		"ALIAS_APPROVED",
		"SOURCE_FIXED",
		"IGNORED"
	);

	private final JdbcAliasQuarantineQueueRepository repository;
	private final Clock clock;

	public AliasQuarantineCommandService(
		JdbcAliasQuarantineQueueRepository repository,
		ObjectProvider<Clock> clockProvider
	) {
		this.repository = repository;
		this.clock = clockProvider.getIfAvailable(Clock::systemDefaultZone);
	}

	@Transactional
	public void approveAlias(String aliasId, String reviewedBy) {
		requireText(aliasId, "aliasId");
		requireText(reviewedBy, "reviewedBy");
		repository.updateAliasStatus(aliasId, "APPROVED", reviewedBy, LocalDateTime.now(clock));
	}

	@Transactional
	public void rejectAlias(String aliasId, String reviewedBy) {
		requireText(aliasId, "aliasId");
		requireText(reviewedBy, "reviewedBy");
		repository.updateAliasStatus(aliasId, "REJECTED", reviewedBy, LocalDateTime.now(clock));
	}

	@Transactional
	public void resolveQuarantine(QuarantineResolutionCommand command) {
		command.validate();
		LocalDateTime resolvedAt = LocalDateTime.now(clock);
		repository.resolveQuarantine(command.quarantineId(), command.resolvedBy(), resolvedAt);
		repository.insertQuarantineResolution(
			"quarantine-resolution-" + UUID.randomUUID(),
			command.quarantineId(),
			command.resolutionStatus(),
			command.resolutionReason(),
			command.resolvedBy(),
			resolvedAt,
			blankToNull(command.canonicalEntityType()),
			blankToNull(command.canonicalEntityId()),
			blankToNull(command.evidenceHash())
		);
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	private static void requireText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " is required");
		}
	}

	public record QuarantineResolutionCommand(
		String quarantineId,
		String resolutionStatus,
		String resolutionReason,
		String resolvedBy,
		String canonicalEntityType,
		String canonicalEntityId,
		String evidenceHash
	) {

		private void validate() {
			requireText(quarantineId, "quarantineId");
			requireText(resolutionStatus, "resolutionStatus");
			requireText(resolutionReason, "resolutionReason");
			requireText(resolvedBy, "resolvedBy");
			if (!RESOLUTION_STATUSES.contains(resolutionStatus)) {
				throw new IllegalArgumentException("unsupported quarantine resolution status: " + resolutionStatus);
			}
			if ("ALIAS_APPROVED".equals(resolutionStatus)) {
				requireText(canonicalEntityType, "canonicalEntityType");
				requireText(canonicalEntityId, "canonicalEntityId");
			}
			if (evidenceHash != null && !evidenceHash.isBlank() && !SHA256_HEX.matcher(evidenceHash).matches()) {
				throw new IllegalArgumentException("evidenceHash must be a sha256 hex value");
			}
		}
	}
}

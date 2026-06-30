package com.easysubway.datapack.application.service;

import com.easysubway.datapack.adapter.out.persistence.JdbcDataSourceSnapshotRepository;
import com.easysubway.datapack.adapter.out.persistence.JdbcDataSourceSnapshotRepository.SourceSnapshotEventRow;
import com.easysubway.datapack.domain.DataSourceSnapshot;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DatapackSourceSnapshotCommandService {

	private final JdbcDataSourceSnapshotRepository snapshotRepository;
	private final TransactionTemplate eventInsertTransaction;
	private final Clock clock;

	public DatapackSourceSnapshotCommandService(
		JdbcDataSourceSnapshotRepository snapshotRepository,
		PlatformTransactionManager transactionManager,
		ObjectProvider<Clock> clockProvider
	) {
		this.snapshotRepository = snapshotRepository;
		this.eventInsertTransaction = new TransactionTemplate(transactionManager);
		this.eventInsertTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
		this.clock = clockProvider.getIfAvailable(Clock::systemDefaultZone);
	}

	@Transactional
	public String createLockedSnapshot(SourceSnapshotCommand command) {
		command.validate();
		DataSourceSnapshot snapshot = snapshotFrom(command);
		var existingEvent = snapshotRepository
			.findEventByIdempotencyKey(command.sourceId(), command.idempotencyKey());
		if (existingEvent.isPresent()) {
			ensureSameIdempotentRequest(command, snapshot, existingEvent.get());
			return existingEvent.get().snapshotId();
		}
		String snapshotId = snapshotRepository.saveSnapshot(snapshot).snapshotId();
		try {
			insertEvent(command, snapshotId);
		} catch (DuplicateKeyException exception) {
			SourceSnapshotEventRow replayedEvent = snapshotRepository
				.findEventByIdempotencyKey(command.sourceId(), command.idempotencyKey())
				.orElseThrow(() -> exception);
			ensureSameIdempotentRequest(command, snapshot, replayedEvent);
			return replayedEvent.snapshotId();
		}
		return snapshotId;
	}

	private void insertEvent(SourceSnapshotCommand command, String snapshotId) {
		eventInsertTransaction.executeWithoutResult(status -> snapshotRepository.insertEvent(
			"source-snapshot-event-" + UUID.randomUUID(),
			command.sourceId(),
			snapshotId,
			"CREATE_LOCKED",
			"PASS",
			command.requestedBy(),
			command.reason(),
			command.idempotencyKey(),
			LocalDateTime.now(clock)
		));
	}

	private static DataSourceSnapshot snapshotFrom(SourceSnapshotCommand command) {
		return new DataSourceSnapshot(
			command.snapshotId(),
			command.sourceId(),
			command.provider(),
			command.retrievedAt(),
			command.sourceUpdatedAt(),
			command.rowCount(),
			command.rawSha256(),
			command.rawObjectUri(),
			command.redactedRequestFingerprint(),
			command.schemaFingerprint(),
			"LOCKED",
			command.schemaStatus(),
			command.licenseStatus(),
			command.fetchStatus(),
			command.redistributionAllowed(),
			command.credentialRedacted(),
			command.previousSnapshotId(),
			command.diffSummary(),
			command.freshnessExpiresAt(),
			command.rawRetentionExpiresAt()
		);
	}

	private void ensureSameIdempotentRequest(
		SourceSnapshotCommand command,
		DataSourceSnapshot snapshot,
		SourceSnapshotEventRow event
	) {
		if (!command.snapshotId().equals(event.snapshotId())
			|| !command.requestedBy().equals(event.requestedBy())
			|| !command.reason().equals(event.reason())
			|| snapshotRepository.loadSnapshot(event.snapshotId())
				.filter(snapshot::equals)
				.isEmpty()) {
			throw new IllegalArgumentException(
				"idempotency key already belongs to a different source snapshot operation");
		}
	}

	public record SourceSnapshotCommand(
		String snapshotId,
		String sourceId,
		String provider,
		LocalDateTime retrievedAt,
		LocalDateTime sourceUpdatedAt,
		int rowCount,
		String rawSha256,
		String rawObjectUri,
		String redactedRequestFingerprint,
		String schemaFingerprint,
		String schemaStatus,
		String licenseStatus,
		String fetchStatus,
		boolean redistributionAllowed,
		boolean credentialRedacted,
		String previousSnapshotId,
		String diffSummary,
		LocalDateTime freshnessExpiresAt,
		LocalDateTime rawRetentionExpiresAt,
		String requestedBy,
		String reason,
		String idempotencyKey
	) {

		private void validate() {
			requireText(requestedBy, "requestedBy");
			requireText(reason, "reason");
			requireText(idempotencyKey, "idempotencyKey");
		}

		private static void requireText(String value, String field) {
			if (value == null || value.isBlank()) {
				throw new IllegalArgumentException(field + " is required");
			}
		}
	}
}

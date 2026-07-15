package com.easysubway.datapack.application.service;

import com.easysubway.datapack.adapter.out.persistence.JdbcDataSourceSnapshotRepository;
import com.easysubway.datapack.adapter.out.persistence.JdbcDataSourceSnapshotRepository.SourceSnapshotEventRow;
import com.easysubway.datapack.domain.DataSourceSnapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
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
	private final ObjectMapper objectMapper;
	private final DatapackSourceGovernancePolicy governancePolicy;

	public DatapackSourceSnapshotCommandService(
		JdbcDataSourceSnapshotRepository snapshotRepository,
		PlatformTransactionManager transactionManager,
		ObjectProvider<Clock> clockProvider,
		ObjectMapper objectMapper,
		DatapackSourceGovernancePolicy governancePolicy
	) {
		this.snapshotRepository = snapshotRepository;
		this.eventInsertTransaction = new TransactionTemplate(transactionManager);
		this.eventInsertTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
		this.clock = clockProvider.getIfAvailable(Clock::systemDefaultZone);
		this.objectMapper = objectMapper;
		this.governancePolicy = governancePolicy;
	}

	@Transactional
	public String createLockedSnapshot(SourceSnapshotCommand command) {
		command.validateReplayIdentity();
		var existingEvent = snapshotRepository
			.findEventByIdempotencyKey(command.sourceId(), command.idempotencyKey());
		if (existingEvent.isPresent()) {
			ensureSameIdempotentRequest(command, existingEvent.get());
			return existingEvent.get().snapshotId();
		}
		command.validateGovernanceBinding();
		var policyBinding = governancePolicy.requireBinding(
			command.sourceId(),
			command.retrievedAt(),
			command.freshnessBasisAt(),
			command.providerValidUntil(),
			command.freshnessExpiresAt(),
			command.rawRetentionExpiresAt(),
			command.governancePolicyVersion(),
			command.governancePolicySha256()
		);
		DataSourceSnapshot snapshot = snapshotFrom(command, policyBinding);
		snapshotRepository.lockSourceLineage(command.sourceId());
		var lockedEvent = snapshotRepository
			.findEventByIdempotencyKey(command.sourceId(), command.idempotencyKey());
		if (lockedEvent.isPresent()) {
			ensureSameIdempotentRequest(command, lockedEvent.get());
			return lockedEvent.get().snapshotId();
		}
		var existingSnapshot = snapshotRepository.loadSnapshot(snapshot.snapshotId());
		if (existingSnapshot.isPresent()) {
			throw new IllegalArgumentException("snapshot ID already exists without this idempotency key");
		}
		validateLineage(snapshot);
		String snapshotId = snapshotRepository.saveSnapshot(snapshot).snapshotId();
		try {
			insertEvent(command, snapshotId);
		} catch (DuplicateKeyException exception) {
			SourceSnapshotEventRow replayedEvent = snapshotRepository
				.findEventByIdempotencyKey(command.sourceId(), command.idempotencyKey())
				.orElseThrow(() -> exception);
			ensureSameIdempotentRequest(command, replayedEvent);
			return replayedEvent.snapshotId();
		}
		return snapshotId;
	}

	private void validateLineage(DataSourceSnapshot snapshot) {
		var head = snapshotRepository.findHeadBySourceIdForUpdate(snapshot.sourceId());
		if (head.isEmpty()) {
			if (snapshot.previousSnapshotId() != null || snapshot.diffSummary() != null || snapshot.diffSummaryJson() != null) {
				throw new IllegalArgumentException("SOURCE_LINEAGE_BROKEN: first snapshot must be a root");
			}
			return;
		}
		DataSourceSnapshot previous = head.get();
		if (!previous.snapshotId().equals(snapshot.previousSnapshotId())
			|| !snapshot.retrievedAt().isAfter(previous.retrievedAt())) {
			throw new IllegalArgumentException("SOURCE_LINEAGE_BROKEN: previous snapshot must be the current source head");
		}
		if (snapshot.diffSummary() == null || snapshot.diffSummaryJson() == null) {
			throw new IllegalArgumentException("SOURCE_DIFF_MISSING: later snapshot requires producer diff");
		}
		validateDiff(previous, snapshot);
	}

	private void validateDiff(DataSourceSnapshot previous, DataSourceSnapshot snapshot) {
		try {
			JsonNode diff = objectMapper.readTree(snapshot.diffSummaryJson());
			boolean rawHashChanged = !previous.rawSha256().equals(snapshot.rawSha256());
			boolean schemaHashChanged = !previous.schemaFingerprint().equals(snapshot.schemaFingerprint());
			boolean requestHashChanged = !previous.redactedRequestFingerprint().equals(snapshot.redactedRequestFingerprint());
			boolean sourceUpdatedAtChanged = !Objects.equals(previous.sourceUpdatedAt(), snapshot.sourceUpdatedAt());
			int rowDelta = snapshot.rowCount() - previous.rowCount();
			int coverageDelta = snapshot.coverageCount() - previous.coverageCount();
			boolean changed = rawHashChanged || schemaHashChanged || requestHashChanged || sourceUpdatedAtChanged
				|| rowDelta != 0 || coverageDelta != 0;
			if (!diff.isObject()
				|| diff.size() != 7
				|| !textEquals(diff, "status", changed ? "CHANGED" : "NO_CHANGE")
				|| !booleanEquals(diff, "rawHashChanged", rawHashChanged)
				|| !booleanEquals(diff, "schemaHashChanged", schemaHashChanged)
				|| !booleanEquals(diff, "requestHashChanged", requestHashChanged)
				|| !booleanEquals(diff, "sourceUpdatedAtChanged", sourceUpdatedAtChanged)
				|| !integerEquals(diff, "rowDelta", rowDelta)
				|| !integerEquals(diff, "coverageDelta", coverageDelta)) {
				throw new IllegalArgumentException("SOURCE_DIFF_MISSING: diff does not match previous source head");
			}
		} catch (JsonProcessingException exception) {
			throw new IllegalArgumentException("SOURCE_DIFF_MISSING: diff must be valid JSON", exception);
		}
	}

	private static boolean textEquals(JsonNode node, String field, String expected) {
		return node.path(field).isTextual() && expected.equals(node.path(field).textValue());
	}

	private static boolean booleanEquals(JsonNode node, String field, boolean expected) {
		return node.path(field).isBoolean() && node.path(field).booleanValue() == expected;
	}

	private static boolean integerEquals(JsonNode node, String field, int expected) {
		return node.path(field).isIntegralNumber() && node.path(field).canConvertToInt()
			&& node.path(field).intValue() == expected;
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

	private static DataSourceSnapshot snapshotFrom(
		SourceSnapshotCommand command,
		DatapackSourceGovernancePolicy.Binding policyBinding
	) {
		return snapshotFrom(
			command,
			policyBinding.freshnessBasisAt(),
			policyBinding.providerValidUntil(),
			policyBinding.freshnessExpiresAt(),
			policyBinding.rawRetentionExpiresAt(),
			policyBinding.version(),
			policyBinding.sha256()
		);
	}

	private static DataSourceSnapshot snapshotFrom(
		SourceSnapshotCommand command,
		LocalDateTime freshnessBasisAt,
		LocalDateTime providerValidUntil,
		LocalDateTime freshnessExpiresAt,
		LocalDateTime rawRetentionExpiresAt,
		String governancePolicyVersion,
		String governancePolicySha256
	) {
		return snapshotFrom(
			command,
			command.coverageCount(),
			command.diffSummaryJson(),
			freshnessBasisAt,
			providerValidUntil,
			freshnessExpiresAt,
			rawRetentionExpiresAt,
			governancePolicyVersion,
			governancePolicySha256
		);
	}

	private static DataSourceSnapshot snapshotFrom(
		SourceSnapshotCommand command,
		int coverageCount,
		String diffSummaryJson,
		LocalDateTime freshnessBasisAt,
		LocalDateTime providerValidUntil,
		LocalDateTime freshnessExpiresAt,
		LocalDateTime rawRetentionExpiresAt,
		String governancePolicyVersion,
		String governancePolicySha256
	) {
		return new DataSourceSnapshot(
			command.snapshotId(),
			command.sourceId(),
			command.provider(),
			command.retrievedAt(),
			command.sourceUpdatedAt(),
			freshnessBasisAt,
			providerValidUntil,
			command.rowCount(),
			coverageCount,
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
			diffSummaryJson,
			freshnessExpiresAt,
			rawRetentionExpiresAt,
			governancePolicyVersion,
			governancePolicySha256
		);
	}

	private void ensureSameIdempotentRequest(
		SourceSnapshotCommand command,
		SourceSnapshotEventRow event
	) {
		var storedSnapshot = snapshotRepository.loadSnapshot(event.snapshotId());
		if (!command.snapshotId().equals(event.snapshotId())
			|| !command.requestedBy().equals(event.requestedBy())
			|| !command.reason().equals(event.reason())
			|| storedSnapshot.filter(snapshot -> replaySnapshot(command, snapshot).equals(snapshot)).isEmpty()) {
			throw new IllegalArgumentException(
				"idempotency key already belongs to a different source snapshot operation");
		}
	}

	private static DataSourceSnapshot replaySnapshot(SourceSnapshotCommand command, DataSourceSnapshot storedSnapshot) {
		boolean legacy = storedSnapshot.governancePolicyVersion() == null
			&& storedSnapshot.governancePolicySha256() == null;
		return snapshotFrom(
			command,
			legacy ? storedSnapshot.coverageCount() : command.coverageCount(),
			legacy ? storedSnapshot.diffSummaryJson() : command.diffSummaryJson(),
			legacy
				? storedSnapshot.freshnessBasisAt()
				: command.freshnessBasisAt() == null ? command.retrievedAt() : command.freshnessBasisAt(),
			legacy ? storedSnapshot.providerValidUntil() : command.providerValidUntil(),
			command.freshnessExpiresAt(),
			command.rawRetentionExpiresAt(),
			command.governancePolicyVersion(),
			command.governancePolicySha256()
		);
	}

	public record SourceSnapshotCommand(
		String snapshotId,
		String sourceId,
		String provider,
		LocalDateTime retrievedAt,
		LocalDateTime sourceUpdatedAt,
		LocalDateTime freshnessBasisAt,
		LocalDateTime providerValidUntil,
		int rowCount,
		int coverageCount,
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
		String diffSummaryJson,
		LocalDateTime freshnessExpiresAt,
		LocalDateTime rawRetentionExpiresAt,
		String governancePolicyVersion,
		String governancePolicySha256,
		String requestedBy,
		String reason,
		String idempotencyKey
	) {

		private void validateReplayIdentity() {
			requireText(requestedBy, "requestedBy");
			requireText(reason, "reason");
			requireText(idempotencyKey, "idempotencyKey");
		}

		private void validateGovernanceBinding() {
			requireText(governancePolicyVersion, "governancePolicyVersion");
			requireText(governancePolicySha256, "governancePolicySha256");
		}

		private static void requireText(String value, String field) {
			if (value == null || value.isBlank()) {
				throw new IllegalArgumentException(field + " is required");
			}
		}
	}
}

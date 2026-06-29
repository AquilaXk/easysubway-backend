package com.easysubway.datapack.application.service;

import com.easysubway.datapack.application.port.out.DatapackReleaseChannelCommandPort;
import com.easysubway.datapack.application.port.out.DatapackReleaseChannelCommandPort.ReleaseChannelEvent;
import com.easysubway.datapack.application.port.out.DatapackReleaseChannelCommandPort.ReleaseChannelState;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DatapackReleaseChannelCommandService {

	private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-fA-F]{64}");

	private final DatapackReleaseChannelCommandPort repository;
	private final Clock clock;

	public DatapackReleaseChannelCommandService(
		DatapackReleaseChannelCommandPort repository,
		ObjectProvider<Clock> clockProvider
	) {
		this.repository = repository;
		this.clock = clockProvider.getIfAvailable(Clock::systemDefaultZone);
	}

	@Transactional
	public ReleaseChannelOperationResult promote(ReleaseChannelCommand command) {
		return apply("PROMOTE", command);
	}

	@Transactional
	public ReleaseChannelOperationResult rollback(ReleaseChannelCommand command) {
		return apply("ROLLBACK", command);
	}

	private ReleaseChannelOperationResult apply(String operationType, ReleaseChannelCommand command) {
		command.validate();
		var existingEvent = repository.findEventByIdempotencyKey(command.channel(), command.idempotencyKey());
		if (existingEvent.isPresent()) {
			ensureSameIdempotentRequest(operationType, command, existingEvent.get());
			return ReleaseChannelOperationResult.from(existingEvent.get(), true);
		}

		var channel = repository.lockChannel(command.channel())
			.orElseThrow(() -> new IllegalArgumentException("release channel not found: " + command.channel()));
		existingEvent = repository.findEventByIdempotencyKey(command.channel(), command.idempotencyKey());
		if (existingEvent.isPresent()) {
			ensureSameIdempotentRequest(operationType, command, existingEvent.get());
			return ReleaseChannelOperationResult.from(existingEvent.get(), true);
		}
		ensureNoPendingOperation(channel);
		ensureCurrentPointerMatches(channel, command);
		ensureNextCandidateExists(command);
		if ("ROLLBACK".equals(operationType)) {
			ensureRollbackTarget(channel, command);
		}

		var now = LocalDateTime.now(clock);
		var eventId = "release-channel-event-" + UUID.randomUUID();
		repository.updateChannel(
			command.channel(),
			command.nextCandidateId(),
			command.nextManifestSha256(),
			channel.candidateId(),
			channel.manifestSha256(),
			operationType,
			command.requestedBy(),
			command.approvedBy(),
			command.reason(),
			command.idempotencyKey(),
			now
		);
		repository.insertEvent(
			eventId,
			command.channel(),
			channel.candidateId(),
			command.nextCandidateId(),
			channel.manifestSha256(),
			command.nextManifestSha256(),
			operationType,
			command.requestedBy(),
			command.approvedBy(),
			command.reason(),
			command.idempotencyKey(),
			command.workflowRunUrl(),
			now
		);
		return new ReleaseChannelOperationResult(
			eventId,
			command.channel(),
			channel.candidateId(),
			command.nextCandidateId(),
			operationType,
			false
		);
	}

	private void ensureNoPendingOperation(ReleaseChannelState channel) {
		if ("PENDING".equals(channel.lastOperationStatus())) {
			throw new IllegalStateException("release channel " + channel.channel()
				+ " already has a pending release operation");
		}
	}

	private void ensureSameIdempotentRequest(
		String operationType,
		ReleaseChannelCommand command,
		ReleaseChannelEvent event
	) {
		if (!operationType.equals(event.operationType())
			|| !command.previousCandidateId().equals(event.previousCandidateId())
			|| !command.nextCandidateId().equals(event.nextCandidateId())
			|| !command.previousManifestSha256().equals(event.previousManifestSha256())
			|| !command.nextManifestSha256().equals(event.nextManifestSha256())
			|| !command.requestedBy().equals(event.requestedBy())
			|| !command.approvedBy().equals(event.approvedBy())
			|| !command.reason().equals(event.reason())
			|| !command.workflowRunUrl().equals(event.workflowRunUrl())) {
			throw new IllegalArgumentException(
				"idempotency key already belongs to a different release operation");
		}
	}

	private void ensureCurrentPointerMatches(ReleaseChannelState channel, ReleaseChannelCommand command) {
		if (!channel.candidateId().equals(command.previousCandidateId())
			|| !channel.manifestSha256().equals(command.previousManifestSha256())) {
			throw new IllegalArgumentException("previous candidate or manifest hash does not match current channel");
		}
	}

	private void ensureNextCandidateExists(ReleaseChannelCommand command) {
		if (!repository.candidateHasManifest(command.nextCandidateId(), command.nextManifestSha256())) {
			throw new IllegalArgumentException("next candidate manifest hash does not match a known candidate");
		}
	}

	private void ensureRollbackTarget(ReleaseChannelState channel, ReleaseChannelCommand command) {
		if (!channel.rollbackAvailable()
			|| channel.previousStableCandidateId() == null
			|| channel.previousManifestSha256() == null
			|| !channel.previousStableCandidateId().equals(command.nextCandidateId())
			|| !channel.previousManifestSha256().equals(command.nextManifestSha256())) {
			throw new IllegalArgumentException("rollback target must be the previous stable candidate");
		}
	}

	public record ReleaseChannelCommand(
		String channel,
		String previousCandidateId,
		String nextCandidateId,
		String previousManifestSha256,
		String nextManifestSha256,
		String requestedBy,
		String approvedBy,
		String reason,
		String idempotencyKey,
		String workflowRunUrl
	) {

		private void validate() {
			requireText(channel, "channel");
			requireText(previousCandidateId, "previousCandidateId");
			requireText(nextCandidateId, "nextCandidateId");
			requireSha(previousManifestSha256, "previousManifestSha256");
			requireSha(nextManifestSha256, "nextManifestSha256");
			requireText(requestedBy, "requestedBy");
			requireText(approvedBy, "approvedBy");
			if (requestedBy.equals(approvedBy)) {
				throw new IllegalArgumentException("approvedBy must be different from requestedBy");
			}
			requireText(reason, "reason");
			requireText(idempotencyKey, "idempotencyKey");
			requireText(workflowRunUrl, "workflowRunUrl");
		}

		private static void requireText(String value, String name) {
			if (value == null || value.isBlank()) {
				throw new IllegalArgumentException(name + " is required");
			}
		}

		private static void requireSha(String value, String name) {
			requireText(value, name);
			if (!SHA256_HEX.matcher(value).matches()) {
				throw new IllegalArgumentException(name + " must be a sha256 hex string");
			}
		}
	}

	public record ReleaseChannelOperationResult(
		String eventId,
		String channel,
		String previousCandidateId,
		String nextCandidateId,
		String operationType,
		boolean idempotentReplay
	) {

		private static ReleaseChannelOperationResult from(ReleaseChannelEvent event, boolean idempotentReplay) {
			return new ReleaseChannelOperationResult(
				event.id(),
				event.channel(),
				event.previousCandidateId(),
				event.nextCandidateId(),
				event.operationType(),
				idempotentReplay
			);
		}
	}
}

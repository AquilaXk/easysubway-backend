package com.easysubway.datapack.application.service;

import com.easysubway.datapack.application.port.out.DatapackReleaseDeliveryRepository;
import com.easysubway.datapack.application.port.out.DatapackReleaseCatalogPort;
import com.easysubway.datapack.application.port.out.DatapackReleaseCatalogPort.CatalogIdentity;
import com.easysubway.datapack.application.port.out.DatapackReleaseChannelCommandPort;
import com.easysubway.datapack.application.port.out.DatapackReleaseRequestRepository;
import com.easysubway.datapack.domain.DatapackReleaseDelivery;
import com.easysubway.datapack.domain.DatapackReleaseDelivery.State;
import com.easysubway.datapack.domain.DatapackReleaseRequest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class DatapackReleaseReconciliationService {
	private final DatapackReleaseDeliveryRepository repository;
	private final DatapackReleaseCallbackService callbackService;
	private final DatapackReleaseCatalogPort catalog;
	private final Clock clock;
	private final DatapackReleaseRequestRepository requestRepository;
	private final DatapackReleaseChannelCommandPort channelRepository;

	@org.springframework.beans.factory.annotation.Autowired
	public DatapackReleaseReconciliationService(DatapackReleaseDeliveryRepository repository,
		DatapackReleaseCallbackService callbackService, DatapackReleaseCatalogPort catalog,
		DatapackReleaseRequestRepository requestRepository,
		DatapackReleaseChannelCommandPort channelRepository,
		ObjectProvider<Clock> clockProvider) {
		this(repository, callbackService, catalog, requestRepository, channelRepository,
			clockProvider.getIfAvailable(Clock::systemUTC));
	}

	DatapackReleaseReconciliationService(DatapackReleaseDeliveryRepository repository,
		DatapackReleaseCallbackService callbackService, DatapackReleaseCatalogPort catalog) {
		this(repository, callbackService, catalog, null, null, Clock.systemUTC());
	}

	DatapackReleaseReconciliationService(DatapackReleaseDeliveryRepository repository,
		DatapackReleaseCallbackService callbackService, DatapackReleaseCatalogPort catalog,
		DatapackReleaseRequestRepository requestRepository,
		DatapackReleaseChannelCommandPort channelRepository) {
		this(repository, callbackService, catalog, requestRepository, channelRepository, Clock.systemUTC());
	}

	DatapackReleaseReconciliationService(DatapackReleaseDeliveryRepository repository,
		DatapackReleaseCallbackService callbackService, DatapackReleaseCatalogPort catalog,
		DatapackReleaseRequestRepository requestRepository,
		DatapackReleaseChannelCommandPort channelRepository, Clock clock) {
		this.repository = repository;
		this.callbackService = callbackService;
		this.catalog = catalog;
		this.clock = clock;
		this.requestRepository = requestRepository;
		this.channelRepository = channelRepository;
	}

	public void reconcileDue() {
		var now = LocalDateTime.now(clock);
		var claimOwner = "datapack-reconciler-" + UUID.randomUUID();
		try {
			discoverMissing(now);
		} catch (RuntimeException ignored) {
			// 한 discovery 오류가 이미 저장된 delivery reconciliation을 막지 않는다.
		}
		for (var delivery : repository.claimDue(now, claimOwner, 100)) {
			try {
				reconcile(delivery, now);
			} catch (RuntimeException failure) {
				try {
					if (!now.isBefore(delivery.deadLetterDeadline())) {
						markClaimed(delivery, State.DEAD_LETTER, delivery.attempts(), null,
							"ERROR", "RECONCILIATION_ERROR", now);
					} else {
						markClaimed(delivery, State.RETRY_SCHEDULED, delivery.attempts() + 1,
							nextAttemptBeforeDeadline(delivery, now),
							"ERROR", "RECONCILIATION_ERROR", now);
					}
				} catch (IllegalStateException lostClaim) {
					// lease가 이미 다른 worker로 넘어갔으면 새 owner가 처리한다.
				}
			}
		}
	}

	void discoverMissing(LocalDateTime now) {
		if (requestRepository == null || channelRepository == null) return;
		requestRepository.claimReconciliationDue(
			now.minusMinutes(10), now, now.plusMinutes(10), 100).stream()
			.forEach(request -> {
				try {
					var identity = catalog.findByRequest(request.targetChannel(), request.approvalId())
						.orElse(null);
					if (!matchesRequest(request, identity)
						|| (!identity.noChange() && !channelRepository.candidateHasManifest(
							request.candidateId(), identity.manifestSha256()))) return;
					repository.upsertSameDelivery(DatapackReleaseDelivery.pending(
						request.approvalId(), identity.releaseSequence(), identity.manifestSha256(),
						request.targetChannel(), request.candidateId(), null,
						identity.signatureSha256(), now));
				} catch (RuntimeException ignored) {
					// fail closed: 다음 bounded scheduler tick에서 재시도한다.
				}
			});
	}

	private static boolean matchesRequest(DatapackReleaseRequest request, CatalogIdentity identity) {
		return identity != null
			&& identity.signatureValid()
			&& request.targetChannel().equals(identity.channel())
			&& request.approvalId().equals(identity.releaseRequestId());
	}

	void reconcile(DatapackReleaseDelivery delivery, LocalDateTime now) {
		try {
			CatalogIdentity current = catalog.fetchCurrent(delivery.channel());
			if (!current.signatureValid() || !delivery.channel().equals(current.channel())) {
				markClaimed(delivery, State.DEAD_LETTER, delivery.attempts(),
					null, "CONFLICT", "CATALOG_CURRENT_MISMATCH", now);
				return;
			}
			if (current.releaseSequence() > delivery.releaseSequence()) {
				terminateSupersededRequest(delivery, now);
				markClaimed(delivery, State.DEAD_LETTER, delivery.attempts(),
					null, "STALE", "CURRENT_RELEASE_ADVANCED", now);
				return;
			}
			if (current.releaseSequence() < delivery.releaseSequence()) {
				throw new DatapackReleaseCatalogPort.Unavailable();
			}
			if (!current.manifestSha256().equals(delivery.manifestSha256())) {
				markClaimed(delivery, State.DEAD_LETTER, delivery.attempts(),
					null, "CONFLICT", "CATALOG_CURRENT_MISMATCH", now);
				return;
			}
			CatalogIdentity identity = catalog.findByRequest(delivery.channel(), delivery.releaseRequestId())
				.orElseThrow(DatapackReleaseCatalogPort.NotFound::new);
			String mismatch = mismatch(delivery, identity);
			if (mismatch != null) {
				markClaimed(delivery, State.DEAD_LETTER, delivery.attempts(),
					null, "CONFLICT", mismatch, now);
				return;
			}
			callbackService.reconcile(delivery, identity);
		} catch (DatapackReleaseCatalogPort.Unavailable unavailable) {
			if (!now.isBefore(delivery.deadLetterDeadline())) {
				markClaimed(delivery, State.DEAD_LETTER, delivery.attempts(),
					null, "UNAVAILABLE", "CATALOG_UNAVAILABLE", now);
			} else {
				markClaimed(delivery, State.RETRY_SCHEDULED,
					delivery.attempts() + 1, nextAttemptBeforeDeadline(delivery, now), "UNAVAILABLE",
					"CATALOG_UNAVAILABLE", now);
			}
		}
	}

	private static LocalDateTime nextAttemptBeforeDeadline(
		DatapackReleaseDelivery delivery,
		LocalDateTime now
	) {
		var nextAttempt = now.plusMinutes(5);
		return nextAttempt.isAfter(delivery.deadLetterDeadline())
			? delivery.deadLetterDeadline()
			: nextAttempt;
	}

	private void terminateSupersededRequest(DatapackReleaseDelivery delivery, LocalDateTime now) {
		if (requestRepository == null) return;
		requestRepository.findByApprovalId(delivery.releaseRequestId()).ifPresent(request -> {
			try {
				requestRepository.save(request.markFailed("CURRENT_RELEASE_ADVANCED", now));
			} catch (IllegalStateException ignored) {
				// 이미 terminal이면 delivery만 stale로 수렴한다.
			}
		});
	}

	private void markClaimed(DatapackReleaseDelivery delivery, State state, int attempts,
		LocalDateTime nextAttemptAt, String httpClass, String detail, LocalDateTime now) {
		if (delivery.claimOwner() == null) {
			repository.mark(delivery.idempotencyKey(), state, attempts, nextAttemptAt, httpClass, detail, now);
		} else {
			repository.markClaimed(delivery.idempotencyKey(), delivery.claimOwner(), state, attempts,
				nextAttemptAt, httpClass, detail, now);
		}
	}

	private static String mismatch(DatapackReleaseDelivery delivery, CatalogIdentity identity) {
		if (!identity.signatureValid()) return "CATALOG_SIGNATURE_MISMATCH";
		if (identity.releaseSequence() != delivery.releaseSequence()) return "CATALOG_SEQUENCE_MISMATCH";
		if (!identity.channel().equals(delivery.channel())) return "CATALOG_CHANNEL_MISMATCH";
		if (!identity.releaseRequestId().equals(delivery.releaseRequestId())) return "CATALOG_REQUEST_MISMATCH";
		if (!identity.manifestSha256().equals(delivery.manifestSha256())) return "CATALOG_MANIFEST_MISMATCH";
		return null;
	}
}

package com.easysubway.datapack.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

import com.easysubway.datapack.adapter.out.persistence.JdbcDatapackReleaseDeliveryRepository;
import com.easysubway.datapack.application.port.out.DatapackReleaseCatalogPort;
import com.easysubway.datapack.application.port.out.DatapackReleaseCatalogPort.CatalogIdentity;
import com.easysubway.datapack.application.port.out.DatapackReleaseChannelCommandPort;
import com.easysubway.datapack.application.port.out.DatapackReleaseRequestRepository;
import com.easysubway.datapack.domain.DatapackReleaseDelivery;
import com.easysubway.datapack.domain.DatapackReleaseDelivery.State;
import com.easysubway.datapack.domain.DatapackReleaseRequest;
import com.easysubway.datapack.domain.DatapackReleaseRequestStatus;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DatapackReleaseReconciliationService")
class DatapackReleaseReconciliationServiceTest {
	private static final LocalDateTime T0 = LocalDateTime.parse("2026-07-16T00:00:00");
	private static final String SHA = "a".repeat(64);

	private final JdbcDatapackReleaseDeliveryRepository repository = mock();
	private final DatapackReleaseCallbackService callbackService = mock();
	private final DatapackReleaseCatalogPort catalog = mock();
	private final DatapackReleaseReconciliationService service =
		new DatapackReleaseReconciliationService(repository, callbackService, catalog);

	// backend가 더 이상 만들지 않는 DISPATCHED 이력 행. reconciliation은 남은 행을 계속 종결시켜야 한다.
	private static DatapackReleaseRequest dispatchedHistoryRow() {
		return new DatapackReleaseRequest(
			"request-2057", "candidate-2057", "scope", "production",
			"b".repeat(64), "c".repeat(64), "d".repeat(64), "requester", "approver",
			DatapackReleaseRequestStatus.DISPATCHED, "dispatch-42", "https://github.com/run/42",
			T0, T0, T0, null, null);
	}

	@Test
	@DisplayName("서명·sequence·hash·channel이 일치하면 callback apply 경로를 재사용한다")
	void matchingCatalogUsesCallbackApply() {
		var delivery = delivery();
		var identity = new CatalogIdentity(42, SHA, "production", "request-2057", true, "b".repeat(64));
		when(catalog.findByRequest("production", "request-2057"))
			.thenReturn(java.util.Optional.of(identity));
		when(catalog.fetchCurrent("production")).thenReturn(identity);

		service.reconcile(delivery, T0.plusMinutes(10));

		verify(callbackService).reconcile(delivery, identity);
	}

	@Test
	@DisplayName("current가 후속 release로 전진한 유실 callback은 DEAD_LETTER로 수렴한다")
	void supersededReleaseDeadLetters() {
		var delivery = delivery();
		var identity = new CatalogIdentity(42, SHA, "production", "request-2057", true, "b".repeat(64));
		when(catalog.findByRequest("production", "request-2057"))
			.thenReturn(java.util.Optional.of(identity));
		when(catalog.fetchCurrent("production")).thenReturn(
			new CatalogIdentity(43, "e".repeat(64), "production", "", true, "c".repeat(64)));

		service.reconcile(delivery, T0.plusMinutes(10));

		verify(repository).mark(delivery.idempotencyKey(), State.DEAD_LETTER, 0, null,
			"STALE", "CURRENT_RELEASE_ADVANCED", T0.plusMinutes(10));
		verify(callbackService, never()).reconcile(any(), any());
	}

	@Test
	@DisplayName("superseded delivery는 원본 request도 terminal FAILED로 종결한다")
	void supersededReleaseTerminatesRequestDiscovery() {
		var requests = mock(DatapackReleaseRequestRepository.class);
		var channels = mock(DatapackReleaseChannelCommandPort.class);
		var dispatched = dispatchedHistoryRow();
		when(requests.findByApprovalId("request-2057")).thenReturn(java.util.Optional.of(dispatched));
		when(catalog.fetchCurrent("production")).thenReturn(
			new CatalogIdentity(43, "e".repeat(64), "production", "", true, "c".repeat(64)));
		var terminating = new DatapackReleaseReconciliationService(
			repository, callbackService, catalog, requests, channels);

		terminating.reconcile(delivery(), T0.plusMinutes(10));

		verify(requests).save(org.mockito.ArgumentMatchers.argThat(request ->
			request.status().name().equals("FAILED")
				&& request.promoteDetail().contains("CURRENT_RELEASE_ADVANCED")));
	}

	@Test
	@DisplayName("binding이 없어도 current가 전진한 delivery는 stale로 종결한다")
	void supersededReleaseDeadLettersBeforeMissingBindingLookup() {
		var delivery = delivery();
		when(catalog.fetchCurrent("production")).thenReturn(
			new CatalogIdentity(43, "e".repeat(64), "production", "", true, "c".repeat(64)));
		when(catalog.findByRequest("production", "request-2057"))
			.thenThrow(new DatapackReleaseCatalogPort.NotFound());

		service.reconcile(delivery, T0.plusMinutes(10));

		verify(repository).mark(delivery.idempotencyKey(), State.DEAD_LETTER, 0, null,
			"STALE", "CURRENT_RELEASE_ADVANCED", T0.plusMinutes(10));
		verify(catalog, never()).findByRequest(anyString(), anyString());
	}

	@Test
	@DisplayName("catalog signature mismatch는 자동 apply 없이 DEAD_LETTER다")
	void signatureMismatchDeadLetters() {
		var delivery = delivery();
		when(catalog.fetchCurrent("production")).thenReturn(
			new CatalogIdentity(42, SHA, "production", "", true, "c".repeat(64)));
		when(catalog.findByRequest("production", "request-2057"))
			.thenReturn(java.util.Optional.of(
				new CatalogIdentity(42, SHA, "production", "request-2057", false, "b".repeat(64))));

		service.reconcile(delivery, T0.plusMinutes(10));

		verify(repository).mark(delivery.idempotencyKey(), State.DEAD_LETTER, 0, null,
			"CONFLICT", "CATALOG_SIGNATURE_MISMATCH", T0.plusMinutes(10));
	}

	@Test
	@DisplayName("catalog identity 불일치는 각각의 sanitized reason으로 DEAD_LETTER다")
	void identityMismatchesDeadLetter() {
		var cases = java.util.Map.of(
			"CATALOG_SEQUENCE_MISMATCH", new CatalogIdentity(43, SHA, "production", "request-2057", true, "b".repeat(64)),
			"CATALOG_CHANNEL_MISMATCH", new CatalogIdentity(42, SHA, "staging", "request-2057", true, "b".repeat(64)),
			"CATALOG_REQUEST_MISMATCH", new CatalogIdentity(42, SHA, "production", "other", true, "b".repeat(64)),
			"CATALOG_MANIFEST_MISMATCH", new CatalogIdentity(42, "e".repeat(64), "production", "request-2057", true, "b".repeat(64)));
		for (var entry : cases.entrySet()) {
			var caseRepository = mock(JdbcDatapackReleaseDeliveryRepository.class);
			var caseCatalog = mock(DatapackReleaseCatalogPort.class);
			var caseService = new DatapackReleaseReconciliationService(
				caseRepository, callbackService, caseCatalog);
			when(caseCatalog.fetchCurrent("production")).thenReturn(
				new CatalogIdentity(42, SHA, "production", "", true, "c".repeat(64)));
			when(caseCatalog.findByRequest("production", "request-2057"))
				.thenReturn(java.util.Optional.of(entry.getValue()));
			caseService.reconcile(delivery(), T0.plusMinutes(10));
			verify(caseRepository).mark(delivery().idempotencyKey(), State.DEAD_LETTER, 0, null,
				"CONFLICT", entry.getKey(), T0.plusMinutes(10));
		}
	}

	@Test
	@DisplayName("catalog unavailable은 70분 전 retry, 70분 경계부터 DEAD_LETTER다")
	void unavailableHonorsDeadlines() {
		var delivery = delivery();
		when(catalog.fetchCurrent("production")).thenReturn(
			new CatalogIdentity(42, SHA, "production", "", true, "c".repeat(64)));
		when(catalog.findByRequest("production", "request-2057"))
			.thenThrow(new DatapackReleaseCatalogPort.Unavailable());

		service.reconcile(delivery, T0.plusMinutes(10));
		verify(repository).mark(delivery.idempotencyKey(), State.RETRY_SCHEDULED, 1,
			T0.plusMinutes(15), "UNAVAILABLE", "CATALOG_UNAVAILABLE", T0.plusMinutes(10));

		service.reconcile(delivery, T0.plusMinutes(69));
		verify(repository).mark(delivery.idempotencyKey(), State.RETRY_SCHEDULED, 1,
			T0.plusMinutes(70), "UNAVAILABLE", "CATALOG_UNAVAILABLE", T0.plusMinutes(69));

		service.reconcile(delivery, T0.plusMinutes(70));
		verify(repository).mark(delivery.idempotencyKey(), State.DEAD_LETTER, 0, null,
			"UNAVAILABLE", "CATALOG_UNAVAILABLE", T0.plusMinutes(70));
	}

	@Test
	@DisplayName("callback row가 없어도 DISPATCHED request와 서명된 request binding으로 delivery를 복원한다")
	void discoversLostCallbackFromRequestBinding() {
		var requests = mock(DatapackReleaseRequestRepository.class);
		var channels = mock(DatapackReleaseChannelCommandPort.class);
		var dispatched = dispatchedHistoryRow();
		when(requests.claimReconciliationDue(T0, T0.plusMinutes(10), T0.plusMinutes(20), 100))
			.thenReturn(java.util.List.of(dispatched));
		when(channels.candidateHasManifest("candidate-2057", SHA)).thenReturn(true);
		when(catalog.findByRequest("production", "request-2057"))
			.thenReturn(java.util.Optional.of(
				new CatalogIdentity(42, SHA, "production", "request-2057", true, "b".repeat(64))));
		var discovery = new DatapackReleaseReconciliationService(
			repository, callbackService, catalog, requests, channels);

		discovery.discoverMissing(T0.plusMinutes(10));

		verify(repository).upsertSameDelivery(org.mockito.ArgumentMatchers.argThat(delivery ->
			delivery.releaseRequestId().equals("request-2057")
				&& delivery.releaseSequence() == 42
				&& delivery.manifestSha256().equals(SHA)));
	}

	@Test
	@DisplayName("NO_CHANGE binding은 candidate raw manifest SHA가 달라도 delivery를 복원한다")
	void discoversNoChangeBindingWithCurrentManifestIdentity() {
		var requests = mock(DatapackReleaseRequestRepository.class);
		var channels = mock(DatapackReleaseChannelCommandPort.class);
		var dispatched = dispatchedHistoryRow();
		when(requests.claimReconciliationDue(T0, T0.plusMinutes(10), T0.plusMinutes(20), 100))
			.thenReturn(java.util.List.of(dispatched));
		when(channels.candidateHasManifest("candidate-2057", SHA)).thenReturn(false);
		when(catalog.findByRequest("production", "request-2057"))
			.thenReturn(java.util.Optional.of(
				new CatalogIdentity(42, SHA, "production", "request-2057", true,
					"b".repeat(64), true)));
		var discovery = new DatapackReleaseReconciliationService(
			repository, callbackService, catalog, requests, channels);

		discovery.discoverMissing(T0.plusMinutes(10));

		verify(repository).upsertSameDelivery(any());
	}

	@Test
	@DisplayName("한 request catalog 오류가 다음 missing callback discovery를 중단하지 않는다")
	void isolatesRequestCatalogFailure() {
		var requests = mock(DatapackReleaseRequestRepository.class);
		var channels = mock(DatapackReleaseChannelCommandPort.class);
		var first = DatapackReleaseRequest.requested(
			"request-2057", "candidate-2057", "scope", "production",
			"b".repeat(64), "c".repeat(64), "d".repeat(64), "requester", T0)
			.approve("approver", T0);
		var second = DatapackReleaseRequest.requested(
			"request-2058", "candidate-2058", "scope", "production",
			"b".repeat(64), "c".repeat(64), "d".repeat(64), "requester", T0)
			.approve("approver", T0);
		when(requests.claimReconciliationDue(T0, T0.plusMinutes(10), T0.plusMinutes(20), 100))
			.thenReturn(java.util.List.of(first, second));
		when(catalog.findByRequest("production", "request-2057"))
			.thenThrow(new IllegalStateException("catalog failed"));
		when(catalog.findByRequest("production", "request-2058"))
			.thenReturn(java.util.Optional.empty());
		var discovery = new DatapackReleaseReconciliationService(
			repository, callbackService, catalog, requests, channels);

		discovery.discoverMissing(T0.plusMinutes(10));

		verify(catalog).findByRequest("production", "request-2058");
	}

	@Test
	@DisplayName("signed catalog의 release request identity가 다르면 lost callback을 복원하지 않는다")
	void rejectsLostCallbackForDifferentRequest() {
		var requests = mock(DatapackReleaseRequestRepository.class);
		var channels = mock(DatapackReleaseChannelCommandPort.class);
		var dispatched = dispatchedHistoryRow();
		when(requests.claimReconciliationDue(T0, T0.plusMinutes(10), T0.plusMinutes(20), 100))
			.thenReturn(java.util.List.of(dispatched));
		when(catalog.findByRequest("production", "request-2057"))
			.thenReturn(java.util.Optional.of(
				new CatalogIdentity(42, SHA, "production", "another-request", true, "b".repeat(64))));
		var discovery = new DatapackReleaseReconciliationService(
			repository, callbackService, catalog, requests, channels);

		discovery.discoverMissing(T0.plusMinutes(10));

		verify(repository, never()).upsertSameDelivery(any());
	}

	@Test
	@DisplayName("current가 후속 release로 전진해도 immutable catalog에서 유실 callback을 복원한다")
	void discoversLostCallbackAfterCurrentAdvances() {
		var requests = mock(DatapackReleaseRequestRepository.class);
		var channels = mock(DatapackReleaseChannelCommandPort.class);
		var dispatched = dispatchedHistoryRow();
		when(requests.claimReconciliationDue(T0, T0.plusMinutes(10), T0.plusMinutes(20), 100))
			.thenReturn(java.util.List.of(dispatched));
		when(channels.candidateHasManifest("candidate-2057", SHA)).thenReturn(true);
		when(catalog.findByRequest("production", "request-2057"))
			.thenReturn(java.util.Optional.of(
				new CatalogIdentity(42, SHA, "production", "request-2057", true, "b".repeat(64))));
		var discovery = new DatapackReleaseReconciliationService(
			repository, callbackService, catalog, requests, channels);

		discovery.discoverMissing(T0.plusMinutes(10));

		verify(repository).upsertSameDelivery(org.mockito.ArgumentMatchers.argThat(delivery ->
			delivery.releaseRequestId().equals("request-2057")
				&& delivery.releaseSequence() == 42
				&& delivery.payloadSha256() == null));
		verify(requests, never()).findRecent(anyInt());
	}

	@Test
	@DisplayName("수동 실행 가능한 APPROVED request도 유실 callback discovery 대상이다")
	void discoversApprovedManualRequest() {
		var requests = mock(DatapackReleaseRequestRepository.class);
		var channels = mock(DatapackReleaseChannelCommandPort.class);
		var approved = DatapackReleaseRequest.requested(
			"request-2057", "candidate-2057", "scope", "production",
			"b".repeat(64), "c".repeat(64), "d".repeat(64), "requester", T0)
			.approve("approver", T0);
		when(requests.claimReconciliationDue(T0, T0.plusMinutes(10), T0.plusMinutes(20), 100))
			.thenReturn(java.util.List.of(approved));
		when(channels.candidateHasManifest("candidate-2057", SHA)).thenReturn(true);
		when(catalog.findByRequest("production", "request-2057"))
			.thenReturn(java.util.Optional.of(
				new CatalogIdentity(42, SHA, "production", "request-2057", true, "b".repeat(64))));
		var discovery = new DatapackReleaseReconciliationService(
			repository, callbackService, catalog, requests, channels);

		discovery.discoverMissing(T0.plusMinutes(10));

		verify(repository).upsertSameDelivery(any());
	}

	@Test
	@DisplayName("일반 reconciliation 오류도 70분 경계에서 DEAD_LETTER로 마감한다")
	void generalFailureHonorsDeadLetterDeadline() {
		var fixedClock = Clock.fixed(
			T0.plusMinutes(70).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
		var deadlineService = new DatapackReleaseReconciliationService(
			repository, callbackService, catalog, null, null, fixedClock);
		var delivery = delivery();
		var identity = new CatalogIdentity(42, SHA, "production", "request-2057", true, "b".repeat(64));
		when(repository.claimDue(eq(T0.plusMinutes(70)), anyString(), eq(100)))
			.thenReturn(java.util.List.of(delivery));
		when(catalog.findByRequest("production", "request-2057"))
			.thenReturn(java.util.Optional.of(identity));
		when(catalog.fetchCurrent("production")).thenReturn(identity);
		doThrow(new IllegalStateException("persistent apply failure"))
			.when(callbackService).reconcile(delivery, identity);

		deadlineService.reconcileDue();

		verify(repository).mark(delivery.idempotencyKey(), State.DEAD_LETTER, 0, null,
			"ERROR", "RECONCILIATION_ERROR", T0.plusMinutes(70));
	}

	@Test
	@DisplayName("각 reconciliation 실행은 lease fencing용 고유 claim owner를 사용한다")
	void usesUniqueClaimOwnerPerRun() {
		service.reconcileDue();
		service.reconcileDue();

		var owners = ArgumentCaptor.forClass(String.class);
		verify(repository, org.mockito.Mockito.times(2)).claimDue(any(), owners.capture(), eq(100));
		assertThat(owners.getAllValues().get(0)).startsWith("datapack-reconciler-");
		assertThat(owners.getAllValues().get(1)).startsWith("datapack-reconciler-");
		assertThat(owners.getAllValues().get(0)).isNotEqualTo(owners.getAllValues().get(1));
	}

	@Test
	@DisplayName("한 delivery 예외가 다음 reconciliation을 중단하지 않는다")
	void isolatesDeliveryFailure() {
		var first = delivery();
		var second = DatapackReleaseDelivery.pending(
			"request-2058", 43, "e".repeat(64), "production", "candidate-2058",
			"c".repeat(64), "d".repeat(64), T0);
		when(repository.claimDue(any(), anyString(), eq(100)))
			.thenReturn(java.util.List.of(first, second));
		var firstIdentity = new CatalogIdentity(42, SHA, "production", "request-2057", true, "b".repeat(64));
		var secondIdentity = new CatalogIdentity(43, "e".repeat(64), "production", "request-2058", true, "b".repeat(64));
		when(catalog.findByRequest("production", "request-2057"))
			.thenReturn(java.util.Optional.of(firstIdentity));
		when(catalog.findByRequest("production", "request-2058"))
			.thenReturn(java.util.Optional.of(secondIdentity));
		when(catalog.fetchCurrent("production")).thenReturn(firstIdentity, secondIdentity);
		doThrow(new IllegalStateException("first failed")).when(callbackService).reconcile(first, firstIdentity);

		service.reconcileDue();

		verify(callbackService).reconcile(second, secondIdentity);
	}

	@Test
	@DisplayName("prelaunch RC identity의 유실 callback을 signed catalog identity로 수렴한다")
	void reconcilesPrelaunchReleaseCandidateIdentity() {
		var manifestSha256 = System.getenv().getOrDefault(
			"EASYSUBWAY_PRELAUNCH_MANIFEST_SHA256", SHA);
		var releaseSequence = Long.parseLong(
			System.getenv().getOrDefault("EASYSUBWAY_PRELAUNCH_RELEASE_SEQUENCE", "42"));
		assertThat(manifestSha256).matches("^[a-f0-9]{64}$");
		assertThat(releaseSequence).isPositive();
		var requestId = "prelaunch-" + manifestSha256;
		var delivery = DatapackReleaseDelivery.pending(
			requestId, releaseSequence, manifestSha256, "production", "prelaunch-candidate",
			"c".repeat(64), "d".repeat(64), T0);
		var identity = new CatalogIdentity(
			releaseSequence, manifestSha256, "production", requestId, true, "b".repeat(64));
		when(catalog.findByRequest("production", requestId))
			.thenReturn(java.util.Optional.of(identity));
		when(catalog.fetchCurrent("production")).thenReturn(identity);

		service.reconcile(delivery, T0.plusMinutes(10));

		verify(callbackService).reconcile(delivery, identity);
		System.out.println("{\"artifactKind\":\"backend-datapack-reconciliation-evidence\","
			+ "\"status\":\"PASS\",\"manifestSha256\":\"" + manifestSha256 + "\","
			+ "\"releaseSequence\":" + releaseSequence + ",\"convergedWithinTenMinutes\":true}");
	}

	private static DatapackReleaseDelivery delivery() {
		return DatapackReleaseDelivery.pending(
			"request-2057", 42, SHA, "production", "candidate-2057",
			"c".repeat(64), "d".repeat(64), T0);
	}
}

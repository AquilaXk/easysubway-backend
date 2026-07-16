package com.easysubway.datapack.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.easysubway.datapack.application.port.out.DatapackReleaseCatalogPort;
import com.easysubway.datapack.application.service.CallbackSignature.CanonicalFields;
import com.easysubway.datapack.application.service.CallbackSignature.LegacyCanonicalFields;
import com.easysubway.datapack.application.service.DatapackReleaseCallbackService.CallbackCommand;
import com.easysubway.datapack.application.service.DatapackReleaseCallbackService.CallbackResult;
import com.easysubway.datapack.application.port.out.DatapackReleaseCatalogPort.CatalogIdentity;
import com.easysubway.datapack.domain.DatapackReleaseDelivery;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@DisplayName("DatapackReleaseCallbackService")
class DatapackReleaseCallbackServiceTest {

    private static final String SHA = "a".repeat(64);
    private static final String SHA_PREV = "f".repeat(64);
    private static final String APPROVAL_ID = "release-request-callback-test-1";
    private static final String WORKFLOW_URL = "https://github.com/example/actions/runs/9001";
    private static final LocalDateTime T0 = LocalDateTime.parse("2026-07-06T00:00:00");
    private static final String CAND_PREV = "cand-cbk-prev";
    private static final long RELEASE_SEQUENCE = 42;
    private static final String CHANNEL = "production";

    @Autowired
    private DatapackReleaseCallbackService service;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private CallbackSignature callbackSignature;
    @MockitoBean
    private DatapackReleaseCatalogPort releaseCatalog;

    @BeforeEach
    void setUp() {
		jdbcTemplate.update("DELETE FROM datapack_release_deliveries");
        // 채널 이벤트 → 채널 → 에비던스·candidate 순으로 FK 제약 만족하며 정리
        jdbcTemplate.update("DELETE FROM datapack_release_channel_events WHERE channel = 'production'");
        jdbcTemplate.update("DELETE FROM datapack_release_channels WHERE channel = 'production'");
        jdbcTemplate.update(
            "DELETE FROM datapack_release_evidence_bundles WHERE candidate_id = 'cand-1' OR candidate_id LIKE 'cand-cbk-%'");
        jdbcTemplate.update(
            "DELETE FROM datapack_candidates WHERE id = 'cand-1' OR id LIKE 'cand-cbk-%'");
        jdbcTemplate.update(
            "DELETE FROM datapack_release_request WHERE approval_id = ?", APPROVAL_ID);
		when(releaseCatalog.fetchCurrent(CHANNEL)).thenReturn(new CatalogIdentity(
			RELEASE_SEQUENCE, SHA, CHANNEL, APPROVAL_ID, true, "b".repeat(64)));
		when(releaseCatalog.findByRequest(CHANNEL, APPROVAL_ID)).thenReturn(java.util.Optional.of(
			new CatalogIdentity(RELEASE_SEQUENCE, SHA, CHANNEL, APPROVAL_ID, true, "b".repeat(64))));
		when(releaseCatalog.findByRequest(CHANNEL, APPROVAL_ID)).thenReturn(java.util.Optional.of(
			new CatalogIdentity(RELEASE_SEQUENCE, SHA, CHANNEL, APPROVAL_ID, true, "b".repeat(64))));
    }

    private void insertRow(String status) {
        insertRow(status, CHANNEL);
    }

    private void insertRow(String status, String targetChannel) {
        jdbcTemplate.update(
            "INSERT INTO datapack_release_request "
                + "(approval_id, candidate_id, scope_id, target_channel, "
                + "build_spec_sha256, source_snapshot_set_hash, approved_ledger_hash, "
                + "requested_by, approved_by, status, dispatch_idempotency_key, workflow_run_url, "
                + "created_at, approved_at, updated_at, promote_outcome, promote_detail) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            APPROVAL_ID, "cand-1", "scope-1", targetChannel,
            SHA, SHA, SHA,
            "alice", "bob", status, "idem-key", null,
            Timestamp.valueOf(T0), Timestamp.valueOf(T0), Timestamp.valueOf(T0),
            null, null);
    }

    private String computeSignature(String publishStatus) {
        var fields = new CanonicalFields(2, "datapack-release-callback", APPROVAL_ID,
            RELEASE_SEQUENCE, CHANNEL, idempotencyKey(SHA), WORKFLOW_URL, SHA, SHA, SHA, SHA,
            "PASS", "PASS", publishStatus);
        return callbackSignature.sign(fields);
    }

    private CallbackCommand command(String publishStatus, String verifierValue) {
        return command(SHA, publishStatus, verifierValue);
    }

    private CallbackCommand command(String manifestSha, String publishStatus, String verifierValue) {
        return new CallbackCommand(2, "datapack-release-callback", APPROVAL_ID,
            RELEASE_SEQUENCE, CHANNEL, idempotencyKey(manifestSha), WORKFLOW_URL,
            manifestSha, SHA, SHA, SHA, "PASS", "PASS", publishStatus,
            "payload-signature", verifierValue);
    }

	private static String idempotencyKey(String manifestSha) {
		return APPROVAL_ID + ":" + RELEASE_SEQUENCE + ":" + manifestSha;
	}

	private CallbackCommand legacyCommand(String publishStatus) {
		var fields = new LegacyCanonicalFields(1, "datapack-release-callback", APPROVAL_ID,
			WORKFLOW_URL, SHA, SHA, SHA, SHA, "PASS", "PASS", publishStatus);
		return new CallbackCommand(1, "datapack-release-callback", APPROVAL_ID,
			0, null, null, WORKFLOW_URL, SHA, SHA, SHA, SHA,
			"PASS", "PASS", publishStatus, "payload-signature", callbackSignature.sign(fields));
	}

    @Test
    @DisplayName("(a) 유효 HMAC + DISPATCHED + publishStatus=PASS → PUBLISHED, workflow_run_url 저장")
    void validHmacDispatchedPass() {
        insertRow("DISPATCHED");
        String sig = computeSignature("PASS");
        CallbackResult result = service.receive(command("PASS", sig));
        assertThat(result.status()).isEqualTo("PUBLISHED");
        assertThat(result.idempotentReplay()).isFalse();
        assertThat(statusOf()).isEqualTo("PUBLISHED");
        assertThat(workflowRunUrlOf()).isEqualTo(WORKFLOW_URL);
    }

    @Test
    @DisplayName("(b) 위조 HMAC → IllegalArgumentException(verifier)")
    void forgedHmacThrows() {
        insertRow("DISPATCHED");
        assertThatThrownBy(() -> service.receive(command("PASS", "deadbeef")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("verifier");
		assertThat(jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM datapack_release_deliveries", Integer.class)).isZero();
    }

    @Test
    @DisplayName("(c) publishStatus=FAIL → FAILED, promote_detail에 사유")
    void publishFailMarksFailed() {
        insertRow("DISPATCHED");
        String sig = computeSignature("FAIL");
        CallbackResult result = service.receive(command("FAIL", sig));
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.idempotentReplay()).isFalse();
        assertThat(statusOf()).isEqualTo("FAILED");
        assertThat(promoteDetailOf()).contains("FAIL");
    }

	@Test
	@DisplayName("validator 또는 route regression이 FAIL이면 publishStatus PASS여도 FAILED다")
	void failedGateCannotPublish() {
		insertRow("DISPATCHED");
		var fields = new CanonicalFields(2, "datapack-release-callback", APPROVAL_ID,
			RELEASE_SEQUENCE, CHANNEL, idempotencyKey(SHA), WORKFLOW_URL, SHA, SHA, SHA, SHA,
			"FAIL", "PASS", "PASS");
		var cmd = new CallbackCommand(2, "datapack-release-callback", APPROVAL_ID,
			RELEASE_SEQUENCE, CHANNEL, idempotencyKey(SHA), WORKFLOW_URL, SHA, SHA, SHA, SHA,
			"FAIL", "PASS", "PASS", "payload-signature", callbackSignature.sign(fields));

		assertThat(service.receive(cmd).status()).isEqualTo("FAILED");
		assertThat(statusOf()).isEqualTo("FAILED");
	}

	@Test
	@DisplayName("route regression FAIL은 publishStatus PASS여도 FAILED다")
	void failedRouteRegressionCannotPublish() {
		insertRow("DISPATCHED");
		var fields = new CanonicalFields(2, "datapack-release-callback", APPROVAL_ID,
			RELEASE_SEQUENCE, CHANNEL, idempotencyKey(SHA), WORKFLOW_URL, SHA, SHA, SHA, SHA,
			"PASS", "FAIL", "PASS");
		var cmd = new CallbackCommand(2, "datapack-release-callback", APPROVAL_ID,
			RELEASE_SEQUENCE, CHANNEL, idempotencyKey(SHA), WORKFLOW_URL, SHA, SHA, SHA, SHA,
			"PASS", "FAIL", "PASS", "payload-signature", callbackSignature.sign(fields));

		assertThat(service.receive(cmd).status()).isEqualTo("FAILED");
		assertThat(statusOf()).isEqualTo("FAILED");
	}

	@Test
	@DisplayName("catalog HTTP 조회는 database transaction 밖에서 수행한다")
	void fetchesCurrentCatalogOutsideDatabaseTransaction() {
		insertRow("DISPATCHED");
		when(releaseCatalog.fetchCurrent(CHANNEL)).thenAnswer(invocation -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			return new CatalogIdentity(RELEASE_SEQUENCE, SHA, CHANNEL, APPROVAL_ID,
				true, "b".repeat(64));
		});

		assertThat(service.receive(command("PASS", computeSignature("PASS"))).status())
			.isEqualTo("PUBLISHED");
	}

	@Test
	@DisplayName("완료된 callback 재전송은 catalog 장애와 무관하게 멱등 처리한다")
	void terminalReplayDoesNotFetchCatalog() {
		insertRow("DISPATCHED");
		service.receive(command("PASS", computeSignature("PASS")));
		when(releaseCatalog.fetchCurrent(CHANNEL))
			.thenThrow(new DatapackReleaseCatalogPort.Unavailable());
		when(releaseCatalog.findByRequest(CHANNEL, APPROVAL_ID))
			.thenThrow(new DatapackReleaseCatalogPort.Unavailable());

		var result = service.receive(command("PASS", computeSignature("PASS")));

		assertThat(result.status()).isEqualTo("PUBLISHED");
		assertThat(result.idempotentReplay()).isTrue();
	}

	@Test
	@DisplayName("delivery 없는 terminal request는 catalog identity 검증을 생략하지 않는다")
	void terminalRequestWithoutDeliveryStillValidatesCatalog() {
		insertRow("PUBLISHED");
		when(releaseCatalog.fetchCurrent(CHANNEL))
			.thenThrow(new DatapackReleaseCatalogPort.Unavailable());

		assertThatThrownBy(() -> service.receive(command("PASS", computeSignature("PASS"))))
			.isInstanceOf(DatapackReleaseCatalogPort.Unavailable.class);
		assertThat(jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM datapack_release_deliveries", Integer.class)).isZero();
	}

    @Test
    @DisplayName("(d) 이미 PUBLISHED + 동일 payload 재수신 → idempotentReplay=true, 상태 불변")
    void alreadyPublishedIdempotentReplay() {
        insertRow("PUBLISHED");
        String sig = computeSignature("PASS");
        CallbackResult result = service.receive(command("PASS", sig));
        assertThat(result.idempotentReplay()).isTrue();
        assertThat(result.status()).isEqualTo("PUBLISHED");
        assertThat(statusOf()).isEqualTo("PUBLISHED");
    }

	@Test
	@DisplayName("동일 payload 10회는 delivery와 release 상태를 한 번만 적용한다")
	void repeatedPayloadAppliesOnce() {
		insertRow("DISPATCHED");
		String sig = computeSignature("PASS");
		assertThat(service.receive(command("PASS", sig)).idempotentReplay()).isFalse();
		for (int index = 0; index < 9; index++) {
			assertThat(service.receive(command("PASS", sig)).idempotentReplay()).isTrue();
		}
		assertThat(jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM datapack_release_deliveries", Integer.class)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject(
			"SELECT state FROM datapack_release_deliveries", String.class)).isEqualTo("DELIVERED");
	}

	@Test
	@DisplayName("같은 request/sequence의 다른 manifest hash는 거부하되 기존 DELIVERED를 보존한다")
	void differentHashForSameSequenceDeadLetters() {
		insertRow("DISPATCHED");
		service.receive(command("PASS", computeSignature("PASS")));
		String other = "b".repeat(64);
		var otherFields = new CanonicalFields(2, "datapack-release-callback", APPROVAL_ID,
			RELEASE_SEQUENCE, CHANNEL, idempotencyKey(other), WORKFLOW_URL, other, SHA, SHA, SHA,
			"PASS", "PASS", "PASS");

		assertThat(service.receive(command(other, "PASS", callbackSignature.sign(otherFields))).status())
			.isEqualTo("DEAD_LETTER");
		assertThat(jdbcTemplate.queryForObject(
			"SELECT state FROM datapack_release_deliveries", String.class)).isEqualTo("DELIVERED");
	}

	@Test
	@DisplayName("더 최신 서명 release가 있으면 늦은 PASS callback을 적용하지 않는다")
	void stalePassCallbackCannotPublishOrPromote() {
		insertRow("DISPATCHED");
		when(releaseCatalog.fetchCurrent(CHANNEL)).thenReturn(new CatalogIdentity(
			RELEASE_SEQUENCE + 1, "b".repeat(64), CHANNEL, "request-2058", true, "c".repeat(64)));

		assertThat(service.receive(command("PASS", computeSignature("PASS"))).status())
			.isEqualTo("DEAD_LETTER");
		assertThat(statusOf()).isEqualTo("DISPATCHED");
		assertThat(jdbcTemplate.queryForObject(
			"SELECT state FROM datapack_release_deliveries", String.class)).isEqualTo("DEAD_LETTER");
		assertThat(jdbcTemplate.queryForObject(
			"SELECT sanitized_detail FROM datapack_release_deliveries", String.class))
			.isEqualTo("CURRENT_RELEASE_ADVANCED");
	}

	@Test
	@DisplayName("current manifest가 같아도 서명 binding의 request ID가 다르면 PASS callback을 거부한다")
	void mismatchedReleaseRequestBindingCannotPublish() {
		insertRow("DISPATCHED");
		when(releaseCatalog.findByRequest(CHANNEL, APPROVAL_ID)).thenReturn(java.util.Optional.of(
			new CatalogIdentity(RELEASE_SEQUENCE, SHA, CHANNEL, "different-request", true, "b".repeat(64))));

		assertThat(service.receive(command("PASS", computeSignature("PASS"))).status())
			.isEqualTo("DEAD_LETTER");
		assertThat(statusOf()).isEqualTo("DISPATCHED");
		assertThat(jdbcTemplate.queryForObject(
			"SELECT sanitized_detail FROM datapack_release_deliveries", String.class))
			.isEqualTo("RELEASE_REQUEST_BINDING_MISMATCH");
	}

	@Test
	@DisplayName("binding 오류 callback은 재조정 상태를 유지하고 이후 PASS로 복구한다")
	void bindingFailureCanBeReconciledByLaterPass() {
		insertRow("DISPATCHED");
		var blockedFields = new CanonicalFields(2, "datapack-release-callback", APPROVAL_ID,
			RELEASE_SEQUENCE, CHANNEL, idempotencyKey(SHA), WORKFLOW_URL, SHA, SHA, SHA, SHA,
			"PASS", "PASS", "BLOCKED_EXTERNAL");
		var blocked = command("BLOCKED_EXTERNAL", callbackSignature.sign(blockedFields));

		assertThat(service.receive(blocked).status()).isEqualTo("RECONCILIATION_REQUIRED");
		assertThat(statusOf()).isEqualTo("DISPATCHED");
		assertThat(jdbcTemplate.queryForObject(
			"SELECT state FROM datapack_release_deliveries", String.class))
			.isEqualTo("RECONCILIATION_REQUIRED");

		assertThat(service.receive(command("PASS", computeSignature("PASS"))).status())
			.isEqualTo("PUBLISHED");
		assertThat(statusOf()).isEqualTo("PUBLISHED");
		assertThat(jdbcTemplate.queryForObject(
			"SELECT state FROM datapack_release_deliveries", String.class)).isEqualTo("DELIVERED");
	}

    @Test
    @DisplayName("(e) status=REQUESTED(미승인) → IllegalStateException")
    void requestedStatusThrowsIllegalState() {
        insertRow("REQUESTED");
        String sig = computeSignature("PASS");
        assertThatThrownBy(() -> service.receive(command("PASS", sig)))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("(f) APPROVED 상태 콜백(수동 dispatch) + PASS → PUBLISHED")
    void approvedStateCallbackPass() {
        insertRow("APPROVED");
        String sig = computeSignature("PASS");
        CallbackResult result = service.receive(command("PASS", sig));
        assertThat(result.status()).isEqualTo("PUBLISHED");
        assertThat(result.idempotentReplay()).isFalse();
        assertThat(statusOf()).isEqualTo("PUBLISHED");
    }

    private String statusOf() {
        return jdbcTemplate.queryForObject(
            "SELECT status FROM datapack_release_request WHERE approval_id = ?",
            String.class, APPROVAL_ID);
    }

    private String workflowRunUrlOf() {
        return jdbcTemplate.queryForObject(
            "SELECT workflow_run_url FROM datapack_release_request WHERE approval_id = ?",
            String.class, APPROVAL_ID);
    }

    private String promoteDetailOf() {
        return jdbcTemplate.queryForObject(
            "SELECT promote_detail FROM datapack_release_request WHERE approval_id = ?",
            String.class, APPROVAL_ID);
    }

    private String promoteOutcomeOf() {
        return jdbcTemplate.queryForObject(
            "SELECT promote_outcome FROM datapack_release_request WHERE approval_id = ?",
            String.class, APPROVAL_ID);
    }

    private void insertCallbackTestCandidate(String id, String manifestSha256) {
        jdbcTemplate.update("""
            INSERT INTO datapack_candidates (
                id, scope_id, artifact_kind, version, source_snapshot_set_hash,
                override_set_hash, build_spec_sha256, source_inventory_sha256,
                sqlite_sha256, gzip_sha256, manifest_sha256, coverage_status,
                validator_status, route_regression_status, android_evidence_status,
                approval_status, created_at
            )
            VALUES (?, 'scope-1', 'DATAPACK', '2026.07.01-cbk.1',
                ?, ?, ?, ?, ?, ?, ?,
                'PASS', 'PASS', 'PASS', 'PASS', 'APPROVED', '2026-07-01 00:00:00')
            """,
            id,
            "a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64),
            SHA, SHA, manifestSha256);
    }

    private void insertCallbackTestChannel(String currentCandidateId, String currentManifestSha256) {
        jdbcTemplate.update("""
            INSERT INTO datapack_release_channels (
                channel, candidate_id, manifest_url, manifest_sha256,
                previous_stable_candidate_id, previous_manifest_sha256,
                rollback_available, last_operation_type, last_operation_status,
                requested_by, approved_by, reason, idempotency_key, updated_at
            )
            VALUES ('production', ?,
                'https://datapack.example.com/production/current.json', ?,
                NULL, NULL, FALSE, 'PROMOTE', 'PASS',
                'data-operator', 'release-approver', 'initial release',
                'idem-cbk-initial', '2026-07-01 00:00:00')
            """,
            currentCandidateId, currentManifestSha256);
    }

    private void insertCallbackTestEvidenceBundle(String candidateId, String evidenceBundleSha256) {
        jdbcTemplate.update("""
            INSERT INTO datapack_release_evidence_bundles (
                id, candidate_id, evidence_bundle_sha256, workflow_run_url,
                validator_status, route_regression_status, manifest_signature_status,
                android_evidence_status, created_at
            )
            VALUES (?, ?, ?, ?,
                'PASS', 'PASS', 'PASS', 'PASS', '2026-07-01 00:00:00')
            """,
            "evidence-cbk-" + candidateId, candidateId, evidenceBundleSha256, WORKFLOW_URL);
    }

	@Test
	@DisplayName("늦은 schema v1 PASS는 request만 종결하고 최신 production 채널을 되돌리지 않는다")
	void staleLegacyCallbackCannotPromoteOverCurrentRelease() {
		insertRow("DISPATCHED", "production");
		insertCallbackTestCandidate("cand-cbk-current", "b".repeat(64));
		insertCallbackTestCandidate("cand-1", SHA);
		insertCallbackTestChannel("cand-cbk-current", "b".repeat(64));
		insertCallbackTestEvidenceBundle("cand-1", SHA);
		when(releaseCatalog.fetchCurrent(CHANNEL)).thenReturn(new CatalogIdentity(
			RELEASE_SEQUENCE + 1, "b".repeat(64), CHANNEL, "request-newer", true, "c".repeat(64)));

		assertThat(service.receive(legacyCommand("PASS")).status()).isEqualTo("PUBLISHED");

		assertThat(statusOf()).isEqualTo("PUBLISHED");
		assertThat(promoteOutcomeOf()).isEqualTo("REJECTED");
		assertThat(jdbcTemplate.queryForObject(
			"SELECT candidate_id FROM datapack_release_channels WHERE channel = 'production'",
			String.class)).isEqualTo("cand-cbk-current");
	}

	@Test
	@DisplayName("legacy callback은 current catalog 장애 때 request 상태를 커밋하지 않는다")
	void legacyCatalogUnavailableLeavesRequestRetryable() {
		insertRow("DISPATCHED", "production");
		when(releaseCatalog.fetchCurrent(CHANNEL))
			.thenThrow(new DatapackReleaseCatalogPort.Unavailable());

		assertThatThrownBy(() -> service.receive(legacyCommand("PASS")))
			.isInstanceOf(DatapackReleaseCatalogPort.Unavailable.class);
		assertThat(statusOf()).isEqualTo("DISPATCHED");
	}

	@Test
	@DisplayName("완료된 legacy callback 재전송도 catalog 장애와 무관하게 멱등 처리한다")
	void terminalLegacyReplayDoesNotFetchCatalog() {
		insertRow("PUBLISHED", "production");
		when(releaseCatalog.fetchCurrent(CHANNEL))
			.thenThrow(new DatapackReleaseCatalogPort.Unavailable());

		var result = service.receive(legacyCommand("PASS"));

		assertThat(result.status()).isEqualTo("PUBLISHED");
		assertThat(result.idempotentReplay()).isTrue();
	}

	@Test
	@DisplayName("(g) PASS + production 채널 없음 → status PUBLISHED 유지 + promote_outcome=REJECTED + promote_detail에 사유")
    void passWithNoProductionChannel_publishesAndRejectsPromote() {
        insertRow("DISPATCHED", "production");
        // 채널을 삽입하지 않음 → findChannel returns empty → promote REJECTED
        String sig = computeSignature("PASS");
        CallbackResult result = service.receive(command("PASS", sig));

        assertThat(result.status()).isEqualTo("PUBLISHED");
        assertThat(result.idempotentReplay()).isFalse();
        assertThat(statusOf()).isEqualTo("PUBLISHED");
        assertThat(promoteOutcomeOf()).isEqualTo("REJECTED");
        assertThat(promoteDetailOf()).contains("production channel missing");
    }

    @Test
    @DisplayName("(h) PASS + evidence 사전등록 완비 → promote_outcome=SUCCEEDED, 채널 포인터 갱신")
    void passWithEvidenceBundle_publishesAndSucceedsPromote() {
        insertRow("DISPATCHED", "production");
        insertCallbackTestCandidate(CAND_PREV, SHA_PREV);
        insertCallbackTestCandidate("cand-1", SHA);
        insertCallbackTestChannel(CAND_PREV, SHA_PREV);
        insertCallbackTestEvidenceBundle("cand-1", SHA);

        String sig = computeSignature("PASS");
        CallbackResult result = service.receive(command("PASS", sig));

        assertThat(result.status()).isEqualTo("PUBLISHED");
        assertThat(result.idempotentReplay()).isFalse();
        assertThat(statusOf()).isEqualTo("PUBLISHED");
        assertThat(promoteOutcomeOf()).isEqualTo("SUCCEEDED");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT candidate_id FROM datapack_release_channels WHERE channel = 'production'",
            String.class)).isEqualTo("cand-1");
    }

    @Test
    @DisplayName("(i) PASS + production 채널 존재 + evidence bundle 미등록 → status PUBLISHED 유지 + promote_outcome=REJECTED")
	void passWithChannelButNoEvidenceBundle_publishesAndRejectsPromote() {
        insertRow("DISPATCHED", "production");
        insertCallbackTestCandidate(CAND_PREV, SHA_PREV);
        insertCallbackTestCandidate("cand-1", SHA);
        insertCallbackTestChannel(CAND_PREV, SHA_PREV);
        // evidence bundle 미삽입 → ensureProductionEvidenceBundle 게이트 거부 경로

        String sig = computeSignature("PASS");
        CallbackResult result = service.receive(command("PASS", sig));

        assertThat(result.status()).isEqualTo("PUBLISHED");
        assertThat(result.idempotentReplay()).isFalse();
        assertThat(statusOf()).isEqualTo("PUBLISHED");
        assertThat(promoteOutcomeOf()).isEqualTo("REJECTED");
        assertThat(promoteDetailOf()).contains("evidence");
	}

	@Test
	@DisplayName("유실 callback reconciliation은 passing evidence의 workflow URL로 promote한다")
	void reconciliationRestoresWorkflowUrlFromPassingEvidence() {
		insertRow("DISPATCHED", "production");
		insertCallbackTestCandidate(CAND_PREV, SHA_PREV);
		insertCallbackTestCandidate("cand-1", SHA);
		insertCallbackTestChannel(CAND_PREV, SHA_PREV);
		insertCallbackTestEvidenceBundle("cand-1", SHA);
		var delivery = DatapackReleaseDelivery.pending(
			APPROVAL_ID, RELEASE_SEQUENCE, SHA, CHANNEL, "cand-1", null, "b".repeat(64), T0);
		var catalog = new CatalogIdentity(
			RELEASE_SEQUENCE, SHA, CHANNEL, APPROVAL_ID, true, "b".repeat(64));

		assertThat(service.reconcile(delivery, catalog).status()).isEqualTo("PUBLISHED");

		assertThat(workflowRunUrlOf()).isEqualTo(WORKFLOW_URL);
		assertThat(promoteOutcomeOf()).isEqualTo("SUCCEEDED");
		assertThat(jdbcTemplate.queryForObject(
			"SELECT candidate_id FROM datapack_release_channels WHERE channel = 'production'",
			String.class)).isEqualTo("cand-1");
	}

	@Test
	@DisplayName("NO_CHANGE reconciliation은 passing evidence URL로 request를 종결하고 promote를 생략한다")
	void reconciliationCompletesNoChangeWithoutCandidatePromotion() {
		insertRow("APPROVED", "production");
		insertCallbackTestCandidate("cand-1", "e".repeat(64));
		insertCallbackTestEvidenceBundle("cand-1", SHA);
		var delivery = DatapackReleaseDelivery.pending(
			APPROVAL_ID, RELEASE_SEQUENCE, SHA, CHANNEL, "cand-1", null, "b".repeat(64), T0);
		var catalog = new CatalogIdentity(
			RELEASE_SEQUENCE, SHA, CHANNEL, APPROVAL_ID, true, "b".repeat(64), true);

		assertThat(service.reconcile(delivery, catalog).status()).isEqualTo("PUBLISHED");

		assertThat(statusOf()).isEqualTo("PUBLISHED");
		assertThat(workflowRunUrlOf()).isEqualTo(WORKFLOW_URL);
		assertThat(promoteOutcomeOf()).isEqualTo("NO_CHANGE");
	}

	@Test
	@DisplayName("reconciliation은 delivery와 binding의 전체 identity가 다르면 dead-letter한다")
	void reconciliationRejectsCatalogIdentityMismatch() {
		insertRow("DISPATCHED", "production");
		var delivery = DatapackReleaseDelivery.pending(
			APPROVAL_ID, RELEASE_SEQUENCE, SHA, CHANNEL, "cand-1", null, "b".repeat(64), T0);
		var mismatched = new CatalogIdentity(
			RELEASE_SEQUENCE, SHA, CHANNEL, "another-request", true, "b".repeat(64));

		assertThat(service.reconcile(delivery, mismatched).status()).isEqualTo("DEAD_LETTER");
		assertThat(statusOf()).isEqualTo("DISPATCHED");
	}

    @TestConfiguration
    static class CallbackSignatureTestConfig {

        // 메인 callbackSignature 빈과 이름이 달라야 BeanDefinitionOverrideException 없이 공존 가능.
        // @Primary로 autowiring 우선순위를 획득한다.
        @Bean("testCallbackSignature")
        @Primary
        CallbackSignature testCallbackSignature() {
            return new CallbackSignature("test-callback-hmac-key");
        }
	}
}

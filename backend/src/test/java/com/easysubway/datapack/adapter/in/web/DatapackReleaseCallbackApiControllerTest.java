package com.easysubway.datapack.adapter.in.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.datapack.application.service.CallbackSignature;
import com.easysubway.datapack.application.service.CallbackSignature.CanonicalFields;
import com.easysubway.datapack.application.port.out.DatapackReleaseCatalogPort;
import com.easysubway.datapack.application.port.out.DatapackReleaseCatalogPort.CatalogIdentity;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "easysubway.datapack.workflow-token=test-workflow-token",
    "easysubway.datapack.callback-hmac-key=test-callback-hmac-key"
})
@DisplayName("release callback 수신 API")
class DatapackReleaseCallbackApiControllerTest {

    private static final String SHA1 = "1".repeat(64);
    private static final String SHA2 = "2".repeat(64);
    private static final String SHA3 = "3".repeat(64);
    private static final String SHA4 = "4".repeat(64);
    private static final String SHA = "a".repeat(64);
    private static final String APPROVAL_ID = "release-request-ctrl-test-1";
    private static final String WORKFLOW_URL =
        "https://github.com/AquilaXk/easysubway/actions/runs/9001";
    private static final LocalDateTime T0 = LocalDateTime.parse("2026-07-07T00:00:00");
	private static final long RELEASE_SEQUENCE = 42;
	private static final String CHANNEL = "staging";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private CallbackSignature callbackSignature;
    @MockitoBean
    private DatapackReleaseCatalogPort releaseCatalog;

    @BeforeEach
    void setUp() {
		jdbcTemplate.update("DELETE FROM datapack_release_deliveries WHERE release_request_id = ?", APPROVAL_ID);
        jdbcTemplate.update(
            "DELETE FROM datapack_release_request WHERE approval_id = ?", APPROVAL_ID);
		when(releaseCatalog.fetchCurrent(CHANNEL)).thenReturn(new CatalogIdentity(
			RELEASE_SEQUENCE, SHA1, CHANNEL, APPROVAL_ID, true, SHA4));
		when(releaseCatalog.findByRequest(CHANNEL, APPROVAL_ID)).thenReturn(java.util.Optional.of(
			new CatalogIdentity(RELEASE_SEQUENCE, SHA1, CHANNEL, APPROVAL_ID, true, SHA4)));
    }

    private void insertDispatched(String approvalId) {
        jdbcTemplate.update(
            "INSERT INTO datapack_release_request "
                + "(approval_id, candidate_id, scope_id, target_channel, "
                + "build_spec_sha256, source_snapshot_set_hash, approved_ledger_hash, "
                + "requested_by, approved_by, status, dispatch_idempotency_key, workflow_run_url, "
                + "created_at, approved_at, updated_at, promote_outcome, promote_detail) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            approvalId, "cand-ctrl-1", "scope-1", "staging",
            SHA, SHA, SHA, "alice", "bob", "DISPATCHED", "idem-key", null,
            Timestamp.valueOf(T0), Timestamp.valueOf(T0), Timestamp.valueOf(T0),
            null, null);
    }

    private String signPayload(String approvalId, String publishStatus) {
        var fields = new CanonicalFields(2, "datapack-release-callback", approvalId,
			RELEASE_SEQUENCE, CHANNEL, idempotencyKey(approvalId),
            WORKFLOW_URL, SHA1, SHA2, SHA3, SHA4, "PASS", "PASS", publishStatus);
        return callbackSignature.sign(fields);
    }

    private String buildPayload(String approvalId, String publishStatus, String hmacValue) {
        return """
            {
              "schemaVersion": 2,
              "artifactKind": "datapack-release-callback",
              "releaseRequestId": "%s",
              "releaseSequence": %d,
              "channel": "%s",
              "idempotencyKey": "%s",
              "workflowRunUrl": "%s",
              "manifestSha256": "%s",
              "sqliteSha256": "%s",
              "gzipSha256": "%s",
              "evidenceBundleSha256": "%s",
              "validatorStatus": "PASS",
              "routeRegressionStatus": "PASS",
              "publishStatus": "%s",
              "callbackVerifier": {"kind": "payload-signature", "value": "%s"}
            }
            """.formatted(approvalId, RELEASE_SEQUENCE, CHANNEL, idempotencyKey(approvalId),
			WORKFLOW_URL, SHA1, SHA2, SHA3, SHA4,
            publishStatus, hmacValue);
    }

	private static String idempotencyKey(String approvalId) {
		return approvalId + ":" + RELEASE_SEQUENCE + ":" + SHA1;
	}

	private static String legacySignature(String approvalId, String publishStatus) throws Exception {
		var message = String.join("\n", "1", "datapack-release-callback", approvalId,
			WORKFLOW_URL, SHA1, SHA2, SHA3, SHA4, "PASS", "PASS", publishStatus);
		var mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec("test-callback-hmac-key".getBytes(StandardCharsets.UTF_8),
			"HmacSHA256"));
		return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
	}

	private static String buildLegacyPayload(String approvalId, String publishStatus, String hmacValue) {
		return """
			{
			  "schemaVersion": 1,
			  "artifactKind": "datapack-release-callback",
			  "releaseRequestId": "%s",
			  "workflowRunUrl": "%s",
			  "manifestSha256": "%s",
			  "sqliteSha256": "%s",
			  "gzipSha256": "%s",
			  "evidenceBundleSha256": "%s",
			  "validatorStatus": "PASS",
			  "routeRegressionStatus": "PASS",
			  "publishStatus": "%s",
			  "callbackVerifier": {"kind": "payload-signature", "value": "%s"}
			}
			""".formatted(approvalId, WORKFLOW_URL, SHA1, SHA2, SHA3, SHA4,
			publishStatus, hmacValue);
	}

	@Test
	@DisplayName("배포 전 시작된 schema v1 callback도 canonical HMAC 검증 후 완료한다")
	void legacySchemaCallbackCompletesDispatchedRequest() throws Exception {
		insertDispatched(APPROVAL_ID);
		var payload = buildLegacyPayload(APPROVAL_ID, "PASS", legacySignature(APPROVAL_ID, "PASS"));

		mockMvc.perform(post("/admin/api/datapack/release-callbacks")
				.header("Authorization", "Bearer test-workflow-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(payload))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PUBLISHED"));
	}

	@Test
	@DisplayName("schema v1 callback도 위조 HMAC은 거부한다")
	void forgedLegacySchemaCallbackIsRejected() throws Exception {
		insertDispatched(APPROVAL_ID);
		var payload = buildLegacyPayload(APPROVAL_ID, "PASS", "deadbeef".repeat(8));

		mockMvc.perform(post("/admin/api/datapack/release-callbacks")
				.header("Authorization", "Bearer test-workflow-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(payload))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("(a) 유효 payload+HMAC+Bearer → 200, status=PUBLISHED")
    void validPayloadAndBearerReturns200() throws Exception {
        insertDispatched(APPROVAL_ID);
        String hmac = signPayload(APPROVAL_ID, "PASS");
        String payload = buildPayload(APPROVAL_ID, "PASS", hmac);

        mockMvc.perform(post("/admin/api/datapack/release-callbacks")
                .header("Authorization", "Bearer test-workflow-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PUBLISHED"))
            .andExpect(jsonPath("$.idempotentReplay").value(false));
    }

    @Test
    @DisplayName("(b) 위조 HMAC → 403")
    void forgedHmacReturns403() throws Exception {
        insertDispatched(APPROVAL_ID);
        // SHA 길이 64를 맞추되 실제 서명과 다른 값
        String forgedHmac = "deadbeef".repeat(8);
        String payload = buildPayload(APPROVAL_ID, "PASS", forgedHmac);

        mockMvc.perform(post("/admin/api/datapack/release-callbacks")
                .header("Authorization", "Bearer test-workflow-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("(c) Bearer 없음 → 4xx (서비스토큰 필터)")
    void noBearerReturns4xx() throws Exception {
        String hmac = signPayload(APPROVAL_ID, "PASS");
        String payload = buildPayload(APPROVAL_ID, "PASS", hmac);

        mockMvc.perform(post("/admin/api/datapack/release-callbacks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("(d) 미존재 releaseRequestId + 유효 HMAC → 404")
    void nonExistentReleaseRequestReturns404() throws Exception {
        String nonExistentId = "nonexistent-release-request-ctrl";
        String hmac = signPayload(nonExistentId, "PASS");
        String payload = buildPayload(nonExistentId, "PASS", hmac);

        mockMvc.perform(post("/admin/api/datapack/release-callbacks")
                .header("Authorization", "Bearer test-workflow-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("(e) 미존재 releaseRequestId에 'verifier' 부분문자열 포함 + 유효 HMAC → 404(403 아님)")
    void nonExistentIdContainingVerifierSubstringReturns404() throws Exception {
        // "verifier" 부분문자열 포함 ID: contains 검사 시 403으로 오분류되는 회귀 케이스
        String idWithVerifier = "verifier-run-001";
        jdbcTemplate.update(
            "DELETE FROM datapack_release_request WHERE approval_id = ?", idWithVerifier);
        String hmac = signPayload(idWithVerifier, "PASS");
        String payload = buildPayload(idWithVerifier, "PASS", hmac);

        mockMvc.perform(post("/admin/api/datapack/release-callbacks")
                .header("Authorization", "Bearer test-workflow-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isNotFound());
    }
}

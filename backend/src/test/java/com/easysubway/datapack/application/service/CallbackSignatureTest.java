package com.easysubway.datapack.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static java.util.Objects.requireNonNull;

import com.easysubway.datapack.application.service.CallbackSignature.CanonicalFields;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CallbackSignature")
class CallbackSignatureTest {

	@Test
	@DisplayName("schema v2 canonical field는 양의 release sequence만 허용한다")
	void rejectsInvalidPrimitiveIdentityFields() {
		assertThatThrownBy(() -> fields(0, 42)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> fields(2, 0)).isInstanceOf(IllegalArgumentException.class);
	}

	private static CanonicalFields fields(int schemaVersion, long releaseSequence) {
		return new CanonicalFields(schemaVersion, "datapack-release-callback", "req-001",
				releaseSequence, "production", "req-001:" + releaseSequence + ":" + "a".repeat(64),
			"https://github.com/example/actions/runs/1", "a".repeat(64), "b".repeat(64),
			"c".repeat(64), "d".repeat(64), "PASS", "PASS", "PASS");
	}

    private static CanonicalFields fields(JsonNode f) {
        return new CanonicalFields(f.get("schemaVersion").asInt(), f.get("artifactKind").asText(),
            f.get("releaseRequestId").asText(), f.get("releaseSequence").asLong(),
			f.get("channel").asText(), f.get("idempotencyKey").asText(), f.get("workflowRunUrl").asText(),
            f.get("manifestSha256").asText(), f.get("sqliteSha256").asText(),
            f.get("gzipSha256").asText(), f.get("evidenceBundleSha256").asText(),
            f.get("validatorStatus").asText(), f.get("routeRegressionStatus").asText(),
            f.get("publishStatus").asText());
    }

    @Test
    @DisplayName("빈 키로 생성된 CallbackSignature의 verify는 false 반환(dormant 경로)")
    void emptyKeyVerifyReturnsFalse() {
        var sig = new CallbackSignature("");
        var f = new CanonicalFields(2, "datapack-release-callback", "req-001", 42,
			"production", "req-001:42:" + "a".repeat(64),
            "https://github.com/example/actions/runs/1",
            "a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64),
            "PASS", "PASS", "PASS");
        assertThat(sig.verify(f, "any-value")).isFalse();
        assertThat(sig.verify(f, null)).isFalse();
    }

    @Test
    @DisplayName("공유 fixture 벡터의 기대 HMAC과 일치하고 위조는 verify=false")
    void matchesSharedVector() throws Exception {
        var fixture = requireNonNull(getClass().getResourceAsStream(
            "/datapack/release-callback-signature-vector.json"));
        var node = new ObjectMapper().readTree(fixture);
        var sig = new CallbackSignature(node.get("hmacKey").asText());
        var f = fields(node.get("fields"));

        String expected = node.get("expectedHmacHex").asText();
        assertThat(sig.sign(f)).isEqualTo(expected);
        assertThat(sig.verify(f, expected)).isTrue();
        assertThat(sig.verify(f, "deadbeef")).isFalse();
			assertThat(f.payloadSha256()).isEqualTo(node.get("expectedPayloadSha256").asText());
		var changedSequence = new CanonicalFields(f.schemaVersion(), f.artifactKind(), f.releaseRequestId(), 43,
			f.channel(), f.idempotencyKey(), f.workflowRunUrl(), f.manifestSha256(), f.sqliteSha256(),
			f.gzipSha256(), f.evidenceBundleSha256(), f.validatorStatus(), f.routeRegressionStatus(),
			f.publishStatus());
		var changedChannel = new CanonicalFields(f.schemaVersion(), f.artifactKind(), f.releaseRequestId(),
			f.releaseSequence(), "staging", f.idempotencyKey(), f.workflowRunUrl(), f.manifestSha256(),
			f.sqliteSha256(), f.gzipSha256(), f.evidenceBundleSha256(), f.validatorStatus(),
			f.routeRegressionStatus(), f.publishStatus());
		var changedKey = new CanonicalFields(f.schemaVersion(), f.artifactKind(), f.releaseRequestId(),
			f.releaseSequence(), f.channel(), "different", f.workflowRunUrl(), f.manifestSha256(),
			f.sqliteSha256(), f.gzipSha256(), f.evidenceBundleSha256(), f.validatorStatus(),
			f.routeRegressionStatus(), f.publishStatus());
		for (var changed : java.util.List.of(changedSequence, changedChannel, changedKey)) {
			assertThat(changed.payloadSha256()).isNotEqualTo(f.payloadSha256());
			assertThat(sig.sign(changed)).isNotEqualTo(expected);
			assertThat(sig.verify(changed, expected)).isFalse();
		}
    }
}

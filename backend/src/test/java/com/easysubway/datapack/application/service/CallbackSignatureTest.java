package com.easysubway.datapack.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.datapack.application.service.CallbackSignature.CanonicalFields;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CallbackSignature")
class CallbackSignatureTest {

    private static CanonicalFields fields(JsonNode f) {
        return new CanonicalFields(f.get("schemaVersion").asInt(), f.get("artifactKind").asText(),
            f.get("releaseRequestId").asText(), f.get("workflowRunUrl").asText(),
            f.get("manifestSha256").asText(), f.get("sqliteSha256").asText(),
            f.get("gzipSha256").asText(), f.get("evidenceBundleSha256").asText(),
            f.get("validatorStatus").asText(), f.get("routeRegressionStatus").asText(),
            f.get("publishStatus").asText());
    }

    @Test
    @DisplayName("빈 키로 생성된 CallbackSignature의 verify는 false 반환(dormant 경로)")
    void emptyKeyVerifyReturnsFalse() {
        var sig = new CallbackSignature("");
        var f = new CanonicalFields(1, "datapack-release-callback", "req-001",
            "https://github.com/example/actions/runs/1",
            "a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64),
            "PASS", "PASS", "PASS");
        assertThat(sig.verify(f, "any-value")).isFalse();
        assertThat(sig.verify(f, null)).isFalse();
    }

    @Test
    @DisplayName("공유 fixture 벡터의 기대 HMAC과 일치하고 위조는 verify=false")
    void matchesSharedVector() throws Exception {
        var root = Path.of(System.getProperty("user.dir")).getParent(); // backend → repo root
        var node = new ObjectMapper().readTree(
            Files.readString(root.resolve("tools/datapack/fixtures/release-callback-signature-vector.json")));
        var sig = new CallbackSignature(node.get("hmacKey").asText());
        var f = fields(node.get("fields"));

        String expected = node.get("expectedHmacHex").asText();
        assertThat(sig.sign(f)).isEqualTo(expected);
        assertThat(sig.verify(f, expected)).isTrue();
        assertThat(sig.verify(f, "deadbeef")).isFalse();
    }
}

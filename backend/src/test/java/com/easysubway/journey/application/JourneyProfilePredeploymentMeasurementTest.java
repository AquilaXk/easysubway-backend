package com.easysubway.journey.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.journey.bundle.JourneyProfileMeasurementInputs;
import com.easysubway.journey.config.JourneyProfileResourcePolicyArtifact;
import com.easysubway.route.application.service.JourneyProfileFullCorpusRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

class JourneyProfilePredeploymentMeasurementTest {

	private static final ObjectMapper JSON = new ObjectMapper();
	@TempDir Path temporaryDirectory;

	@Test
	void retainsCanonicalCorpusBytesWithoutOverwritingEvidence() throws Exception {
		Path output = temporaryDirectory.resolve("corpus.json");
		var corpus = Map.of("oracleLimits", Map.of("maxWork", Long.toString(Long.MAX_VALUE)));
		writeCanonicalOnce(output, corpus);
		assertThat(Files.readString(output)).isEqualTo(
			"{\"oracleLimits\":{\"maxWork\":\"9223372036854775807\"}}\n");
		assertThatThrownBy(() -> writeCanonicalOnce(output, Map.of("replacement", true)))
			.isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
		assertThat(JSON.readTree(Files.readAllBytes(output)).path("oracleLimits").path("maxWork").textValue())
			.isEqualTo(Long.toString(Long.MAX_VALUE));
	}

	@Test
	void requiresExplicitDecimalOracleBoundsWithoutDefaults() {
		assertThat(decimal(Map.of("limit", "17"), "limit", false)).isEqualTo(17);
		assertThat(decimal(Map.of("limit", "0"), "limit", true)).isZero();
		for (String value : new String[] {"", "-1", "+1", "01", "1.0", " 1", "9223372036854775808"}) {
			assertThatThrownBy(() -> decimal(Map.of("limit", value), "limit", false))
				.isInstanceOf(IllegalArgumentException.class);
		}
		assertThatThrownBy(() -> decimal(Map.of(), "limit", false)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> decimal(Map.of("limit", "0"), "limit", false))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void canonicalizesNestedIdentityKeysWithoutReorderingArrays() throws Exception {
		assertThat(canonical(JSON.readTree("{\"z\":[2,1],\"a\":{\"y\":2,\"x\":1}}")))
			.isEqualTo("{\"a\":{\"x\":1,\"y\":2},\"z\":[2,1]}");
		assertThat(algorithmIdentity().get("POINT")).isEqualTo(Map.of("profileAlgorithm", "NOT_APPLICABLE"));
		assertThat(algorithmIdentity().get("DEPARTURE_PROFILE"))
			.isEqualTo(JourneyRaptorPruningInventoryV1.FORWARD_RANGE_RAPTOR);
	}

	@Test
	@EnabledIfEnvironmentVariable(named = "MEASUREMENT_INPUT", matches = ".+")
	void measurePinnedCandidate() throws Exception {
		Map<String, String> environment = System.getenv();
		Path output = Path.of(required(environment, "MEASUREMENT_OUTPUT"));
		Path corpusOutput = Path.of(output.toString() + ".corpus.json");
		if (Files.exists(output, LinkOption.NOFOLLOW_LINKS) || Files.exists(corpusOutput, LinkOption.NOFOLLOW_LINKS)) {
			throw new IllegalArgumentException("measurement output already exists");
		}
		Path policyPath = Path.of(required(environment, "MEASUREMENT_POLICY"));
		if (!Files.isRegularFile(policyPath, LinkOption.NOFOLLOW_LINKS)) {
			throw new IllegalArgumentException("measurement policy must be a regular file");
		}
		var policy = JourneyProfileResourcePolicyArtifact.read(Files.readAllBytes(policyPath),
			required(environment, "MEASUREMENT_POLICY_SHA256"));
		var limits = new JourneyProfileFullCorpusRunner.OracleLimits(
			decimal(environment, "MEASUREMENT_ORACLE_MAX_WORK", false),
			Math.toIntExact(decimal(environment, "MEASUREMENT_ORACLE_MAX_RIDES", false)),
			Math.toIntExact(decimal(environment, "MEASUREMENT_ORACLE_MAX_ACCESSES", false)));
		int slack = Math.toIntExact(decimal(environment, "MEASUREMENT_ORACLE_BOARDING_SLACK_SECONDS", true));
		var pinned = JourneyProfileMeasurementInputs.read(Path.of(required(environment, "CANDIDATE_ROOT")),
			Path.of(required(environment, "MEASUREMENT_INPUT")));
		// 단일 측정 프로세스의 첫 generation이며, 운영 활성화 generation을 주장하지 않는다.
		var result = JourneyProfileFullCorpusRunner.run(pinned, policy, limits, slack, 1);
		var algorithm = algorithmIdentity();
		var frontier = JourneyFrontierPolicyV1.identity();
		var corpus = new LinkedHashMap<String, Object>(result.corpus());
		corpus.put("algorithmIdentity", algorithm);
		corpus.put("frontierIdentity", frontier);
		var input = pinned.measurementInput();
		var observation = new LinkedHashMap<String, Object>();
		observation.put("schemaVersion", 2);
		observation.put("artifactKind", "journey-profile-predeployment-observation");
		observation.put("observationScope", "PREDEPLOYMENT_PLANNER");
		observation.put("servingBoundary", Map.of("status", "UNOBSERVABLE"));
		observation.put("backendHeadSha", input.backendHeadSha());
		observation.put("dataHeadSha", input.dataHeadSha());
		observation.put("dataRunId", input.dataRunId());
		observation.put("fanInSha256", input.fanIn().sha256());
		observation.put("routeBundleSha256", input.routeBundleSha256());
		observation.put("regionalMatrixSha256", input.regionalMatrixSha256());
		observation.put("corpusSha256", digest(corpus));
		observation.put("algorithmId", JourneyRaptorPruningInventoryV1.FORWARD_RANGE_RAPTOR.algorithmSuiteId());
		observation.put("algorithmSha256", digest(algorithm));
		observation.put("frontierPolicyId", frontier.frontierPolicyId());
		observation.put("frontierSha256", digest(frontier));
		observation.put("measurements", result.rows());
		// 운영 관측값을 합성하지 않는다. Data의 closed-schema gate가 완전성과 parity를 판정한다.
		writeCanonicalOnce(corpusOutput, corpus);
		writeCanonicalOnce(output, observation);
	}

	private static void writeCanonicalOnce(Path output, Object value) throws Exception {
		byte[] bytes = (canonical(JSON.valueToTree(value)) + "\n").getBytes(StandardCharsets.UTF_8);
		try (var stream = Files.newByteChannel(output,
			java.util.Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
			PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
			var buffer = java.nio.ByteBuffer.wrap(bytes);
			while (buffer.hasRemaining()) stream.write(buffer);
		}
	}

	private static Map<String, Object> algorithmIdentity() {
		var reverse = JourneyRaptorPruningInventoryV1.REVERSE_RANGE_RAPTOR;
		return Map.of("POINT", Map.of("profileAlgorithm", "NOT_APPLICABLE"),
			"DEPARTURE_PROFILE", JourneyRaptorPruningInventoryV1.FORWARD_RANGE_RAPTOR,
			"ARRIVE_BY", reverse, "LAST_CONNECTION", reverse, "CUTOFF", reverse, "TYPED_FAILURE", reverse);
	}

	private static String required(Map<String, String> environment, String name) {
		String value = environment.get(name);
		if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
		return value;
	}

	private static long decimal(Map<String, String> environment, String name, boolean allowZero) {
		String value = required(environment, name);
		if (!value.matches(allowZero ? "0|[1-9][0-9]*" : "[1-9][0-9]*")) {
			throw new IllegalArgumentException(name + " must be a bounded decimal integer");
		}
		return Long.parseLong(value);
	}

	private static String digest(Object value) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
			.digest(canonical(JSON.valueToTree(value)).getBytes(StandardCharsets.UTF_8)));
	}

	private static String canonical(JsonNode node) {
		if (node.isArray()) {
			var values = new java.util.ArrayList<String>();
			node.forEach(value -> values.add(canonical(value)));
			return "[" + String.join(",", values) + "]";
		}
		if (!node.isObject()) return node.toString();
		var fields = new TreeMap<String, String>();
		node.fields().forEachRemaining(entry -> fields.put(entry.getKey(), canonical(entry.getValue())));
		return "{" + fields.entrySet().stream()
			.map(entry -> JSON.getNodeFactory().textNode(entry.getKey()) + ":" + entry.getValue())
			.collect(java.util.stream.Collectors.joining(",")) + "}";
	}
}

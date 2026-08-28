package com.easysubway.journey.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.journey.bundle.JourneyV3BenchmarkRuntimeAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JourneyV3BenchmarkContractTest {

	private static final String SHA = "a".repeat(64);

	@Test
	void acceptsCompleteSameCorpusPaceMatrix() {
		var corpus = JourneyV3NationwideBenchmarkTest.Contract.parseCorpus(validCorpus());
		var evidence = completeEvidence(corpus);

		JourneyV3NationwideBenchmarkTest.Contract.validate(evidence);

		assertThat(evidence.warm().get(JourneyV3NationwideBenchmarkTest.Profile.STANDARD).percentiles().p95Nanos())
			.isEqualTo(20);
	}

	@Test
	void rejectsTupleCorpusAndProfileMatrixDrift() {
		var corpus = JourneyV3NationwideBenchmarkTest.Contract.parseCorpus(validCorpus());
		var missingDigest = completeEvidence(corpus);
		var invalidTuple = new JourneyV3BenchmarkRuntimeAdapter.ExpectedIdentity("bad", "b".repeat(64), "c".repeat(64), "d".repeat(40));
		var invalid = new JourneyV3NationwideBenchmarkTest.Evidence(invalidTuple, corpus, missingDigest.cold(),
			missingDigest.warm(), missingDigest.warmStartCounters(), missingDigest.counters(), missingDigest.config());
		assertThatThrownBy(() -> JourneyV3NationwideBenchmarkTest.Contract.validate(invalid))
			.hasMessageContaining("SHA-256");

		assertThatThrownBy(() -> JourneyV3NationwideBenchmarkTest.Contract.parseCorpus("""
			{"schemaVersion":1,"corpusVersion":"v1","cases":[{"id":"x","originStationId":"a","destinationStationId":"a","serviceDay":"WEEKDAY","departureLocalTime":"08:00"}]}
			""")).hasMessageContaining("corpus entry");
		assertThatThrownBy(() -> JourneyV3NationwideBenchmarkTest.Contract.parseCorpus("""
			{"schemaVersion":1,"corpusVersion":"v1","cases":[{"id":"x","originStationId":"a","destinationStationId":"b","serviceDay":"WEEKDAY","departureLocalTime":"25:00"}]}
			""")).hasMessageContaining("corpus entry");

		var digestMismatch = new JourneyV3NationwideBenchmarkTest.Evidence(missingDigest.tuple(), corpus,
			missingDigest.cold(), missingDigest.warm(), missingDigest.warmStartCounters(), missingDigest.counters(),
			new JourneyV3NationwideBenchmarkTest.Config(1, 1, "d".repeat(64), Path.of("result.json")));
		assertThatThrownBy(() -> JourneyV3NationwideBenchmarkTest.Contract.validate(digestMismatch))
			.hasMessageContaining("corpus digest");

		var unequal = completeEvidence(corpus);
		unequal.warm().get(JourneyV3NationwideBenchmarkTest.Profile.FAST).inputCaseIds().clear();
		assertThatThrownBy(() -> JourneyV3NationwideBenchmarkTest.Contract.validate(unequal))
			.hasMessageContaining("warm matrix");
	}

	@Test
	void rejectsNonpositiveCountsAndForbiddenAdapterPaths() {
		var corpus = JourneyV3NationwideBenchmarkTest.Contract.parseCorpus(validCorpus());
		var valid = completeEvidence(corpus);
		var invalidConfig = new JourneyV3NationwideBenchmarkTest.Config(0, 1, corpus.sha256(), Path.of("result.json"));
		var invalid = new JourneyV3NationwideBenchmarkTest.Evidence(valid.tuple(), corpus, valid.cold(), valid.warm(),
			valid.warmStartCounters(), valid.counters(), invalidConfig);
		assertThatThrownBy(() -> JourneyV3NationwideBenchmarkTest.Contract.validate(invalid))
			.hasMessageContaining("configuration");

		var forbidden = new JourneyV3BenchmarkRuntimeAdapter.Counters(1, 2, 13, 13, 1, 0, 0, 0);
		var unsafe = new JourneyV3NationwideBenchmarkTest.Evidence(valid.tuple(), corpus, valid.cold(), valid.warm(),
			valid.warmStartCounters(), forbidden, valid.config());
		assertThatThrownBy(() -> JourneyV3NationwideBenchmarkTest.Contract.validate(unsafe))
			.hasMessageContaining("forbidden");
	}

	@Test
	void validatesCompleteActiveServingReceiptAndRejectsIdentityOrStateDrift() throws Exception {
		byte[] valid = receipt("ACTIVE_SERVING", null);
		JourneyV3BenchmarkRuntimeAdapter.verifyActiveReceipt(valid, "4".repeat(64), "5".repeat(40));

		assertThatThrownBy(() -> JourneyV3BenchmarkRuntimeAdapter.verifyActiveReceipt(
				receipt("ACTIVE_SERVING", "sha256:" + "f".repeat(64)), "4".repeat(64), "5".repeat(40)))
			.hasMessageContaining("does not bind");
		assertThatThrownBy(() -> JourneyV3BenchmarkRuntimeAdapter.verifyActiveReceipt(
			receipt("FAILED_POSTSWITCH", null), "4".repeat(64), "5".repeat(40)))
			.hasMessageContaining("ACTIVE_SERVING");
		assertThatThrownBy(() -> JourneyV3BenchmarkRuntimeAdapter.verifyActiveReceipt(
			new String(valid, StandardCharsets.UTF_8).concat("{}").getBytes(StandardCharsets.UTF_8),
			"4".repeat(64), "5".repeat(40)))
			.isInstanceOf(java.io.IOException.class);
	}

	@Test
	void requiresAnIndependentDeploymentRevisionAndStrictReceiptNumbers() throws Exception {
		byte[] valid = receipt("ACTIVE_SERVING", null);
		assertThatThrownBy(() -> JourneyV3BenchmarkRuntimeAdapter.verifyActiveReceipt(
			valid, "4".repeat(64), "6".repeat(40)))
			.hasMessageContaining("release identity");

		var mapper = new ObjectMapper();
		var rollbackAsText = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(valid);
		rollbackAsText.put("rollbackAttemptCount", "0");
		assertThatThrownBy(() -> JourneyV3BenchmarkRuntimeAdapter.verifyActiveReceipt(
			mapper.writeValueAsBytes(rollbackAsText), "4".repeat(64), "5".repeat(40)))
			.hasMessageContaining("integer");

		var nodePortFraction = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(valid);
		((com.fasterxml.jackson.databind.node.ObjectNode) nodePortFraction.path("activation").path("endpoint"))
			.put("nodePort", 32080.5);
		assertThatThrownBy(() -> JourneyV3BenchmarkRuntimeAdapter.verifyActiveReceipt(
			mapper.writeValueAsBytes(nodePortFraction), "4".repeat(64), "5".repeat(40)))
			.hasMessageContaining("integer");

		var overflowingIntegral = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(valid);
		overflowingIntegral.put("rollbackAttemptCount", new java.math.BigInteger("18446744073709551616"));
		assertThatThrownBy(() -> JourneyV3BenchmarkRuntimeAdapter.verifyActiveReceipt(
			mapper.writeValueAsBytes(overflowingIntegral), "4".repeat(64), "5".repeat(40)))
			.hasMessageContaining("integer");
	}

	@Test
	void rejectsNoncanonicalCorpusShapesAndScalarTypes() {
		for (String invalid : List.of(
			"[]",
			"{\"schemaVersion\":\"1\",\"corpusVersion\":\"v1\",\"cases\":[]}",
			"{\"schemaVersion\":1.5,\"corpusVersion\":\"v1\",\"cases\":[]}",
			"{\"schemaVersion\":1,\"corpusVersion\":1,\"cases\":[]}",
			"{\"schemaVersion\":1,\"corpusVersion\":\"v1\",\"cases\":{}}",
			"{\"schemaVersion\":1,\"corpusVersion\":\"v1\",\"cases\":[\"case\"]}")) {
			assertThatThrownBy(() -> JourneyV3NationwideBenchmarkTest.Contract.parseCorpus(invalid))
				.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void rejectsArtifactPathSymlinkEscapes(@TempDir Path directory) throws Exception {
		Path root = Files.createDirectory(directory.resolve("artifact-root"));
		Path outside = Files.writeString(directory.resolve("outside.json"), "outside");
		Files.createSymbolicLink(root.resolve("ancestor"), directory);
		Files.createSymbolicLink(root.resolve("final.json"), outside);

		assertThatThrownBy(() -> JourneyV3BenchmarkRuntimeAdapter.checkedArtifactObjectPath(root, "ancestor/outside.json"))
			.hasMessageContaining("unavailable");
		assertThatThrownBy(() -> JourneyV3BenchmarkRuntimeAdapter.checkedArtifactObjectPath(root, "final.json"))
			.hasMessageContaining("unavailable");
	}

	@Test
	void writesCompleteEvidenceExactlyOnce(@TempDir Path directory) throws Exception {
		var evidence = completeEvidence(JourneyV3NationwideBenchmarkTest.Contract.parseCorpus(validCorpus()));
		Path output = directory.resolve("benchmark.json");

		JourneyV3NationwideBenchmarkTest.writeEvidence(output, evidence);
		byte[] first = Files.readAllBytes(output);
		assertThat(new ObjectMapper().readTree(first).path("schemaVersion").asInt()).isOne();

		assertThatThrownBy(() -> JourneyV3NationwideBenchmarkTest.writeEvidence(output, evidence))
			.isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
		assertThat(Files.readAllBytes(output)).isEqualTo(first);
	}

	@Test
	void rejectsDescriptorReceiptAndManifestTupleDrift() throws Exception {
		byte[] receipt = "active-receipt".getBytes(StandardCharsets.UTF_8);
		var expected = new JourneyV3BenchmarkRuntimeAdapter.ExpectedIdentity(
			"1".repeat(64), digest("active-receipt"), "2".repeat(64), "5".repeat(40));
		JourneyV3BenchmarkRuntimeAdapter.verifyExpectedIdentity(
			"1".repeat(64), receipt, "2".repeat(64), expected);

		assertThatThrownBy(() -> JourneyV3BenchmarkRuntimeAdapter.verifyExpectedIdentity(
			"3".repeat(64), receipt, "2".repeat(64), expected))
			.hasMessageContaining("descriptor self-digest");
		assertThatThrownBy(() -> JourneyV3BenchmarkRuntimeAdapter.verifyExpectedIdentity(
			"1".repeat(64), "other".getBytes(StandardCharsets.UTF_8), "2".repeat(64), expected))
			.hasMessageContaining("receipt digest");
		assertThatThrownBy(() -> JourneyV3BenchmarkRuntimeAdapter.verifyExpectedIdentity(
			"1".repeat(64), receipt, "4".repeat(64), expected))
			.hasMessageContaining("manifest digest");
	}

	private static JourneyV3NationwideBenchmarkTest.Evidence completeEvidence(
		JourneyV3NationwideBenchmarkTest.Corpus corpus
	) {
		var warm = new EnumMap<JourneyV3NationwideBenchmarkTest.Profile, JourneyV3NationwideBenchmarkTest.WarmEvidence>(
			JourneyV3NationwideBenchmarkTest.Profile.class);
		for (var profile : JourneyV3NationwideBenchmarkTest.Profile.values()) {
			var samples = new java.util.ArrayList<>(List.of(
				new JourneyV3NationwideBenchmarkTest.Sample("capital-cross-city", profile.name(), 0, 10, 100),
				new JourneyV3NationwideBenchmarkTest.Sample("regional-connection", profile.name(), 0, 20, 200)));
			warm.put(profile, new JourneyV3NationwideBenchmarkTest.WarmEvidence(
				new java.util.ArrayList<>(corpus.cases().stream().map(JourneyV3NationwideBenchmarkTest.Case::id).toList()), samples,
				JourneyV3NationwideBenchmarkTest.Percentiles.from(samples), new JourneyV3NationwideBenchmarkTest.ScanMetrics(1, 2, 3)));
		}
		return new JourneyV3NationwideBenchmarkTest.Evidence(
			new JourneyV3BenchmarkRuntimeAdapter.ExpectedIdentity(SHA, "b".repeat(64), "c".repeat(64), "d".repeat(40)), corpus,
			new JourneyV3NationwideBenchmarkTest.ColdEvidence(1, 1, 1, 1, 1, 1, 1, 1), warm,
			new JourneyV3BenchmarkRuntimeAdapter.Counters(1, 2, 1, 1, 0, 0, 0, 0),
			new JourneyV3BenchmarkRuntimeAdapter.Counters(1, 2, 13, 13, 0, 0, 0, 0),
			new JourneyV3NationwideBenchmarkTest.Config(1, 1, corpus.sha256(), Path.of("result.json")));
	}

	private static String validCorpus() {
		return """
			{"schemaVersion":1,"corpusVersion":"v1","cases":[
			{"id":"capital-cross-city","originStationId":"station-origin","destinationStationId":"station-destination","serviceDay":"WEEKDAY","departureLocalTime":"08:00"},
			{"id":"regional-connection","originStationId":"station-regional-origin","destinationStationId":"station-regional-destination","serviceDay":"WEEKEND","departureLocalTime":"14:30"}]}
			""";
	}

	private static byte[] receipt(String outcome, String tupleOverride) throws Exception {
		String image = "sha256:" + "1".repeat(64);
		String config = "sha256:" + "2".repeat(64);
		String contract = "sha256:" + "3".repeat(64);
		String bundle = "sha256:" + "4".repeat(64);
		String revision = "5".repeat(40);
		String environment = "production";
		String tuple = "sha256:" + digest(String.join("\n", List.of(
			image, config, contract, bundle, revision, environment)) + "\n");
		var identity = new LinkedHashMap<String, Object>();
		identity.put("tupleSha256", tupleOverride == null ? tuple : tupleOverride);
		identity.put("backendImageDigest", image);
		identity.put("backendConfigDigest", config);
		identity.put("journeyContractDigest", contract);
		identity.put("serverRouteBundleDigest", bundle);
		identity.put("deploymentRevision", revision);
		identity.put("environmentIdentity", environment);
		identity.put("candidateGeneration", 1);
		identity.put("trafficGeneration", 2);
		Map<String, Object> serviceCas = Map.of(
			"previousResourceVersion", "17",
			"committedResourceVersion", "18",
			"selector", Map.of("app", "easysubway-backend"),
			"evidenceDigest", "sha256:" + "a".repeat(64));
		Map<String, Object> activation = Map.of(
			"servicePreparation", Map.of("serviceExisted", true, "activeServiceMutationCount", 1,
				"evidenceDigest", "sha256:" + "b".repeat(64)),
			"serviceCas", serviceCas,
			"endpoint", Map.of("readyAddress", "10.0.0.1", "nodePort", 32080, "tupleSha256", tuple,
				"evidenceDigest", "sha256:" + "c".repeat(64)),
			"nginx", Map.of("targetPort", 32080, "nginxConfigSha256", "sha256:" + "d".repeat(64),
				"evidenceDigest", "sha256:" + "e".repeat(64)),
			"drain", Map.of("signal", "SIGTERM", "stopGracePeriodSeconds", 30, "oldWorkloadCount", 1,
				"evidenceDigest", "sha256:" + "6".repeat(64)),
			"publicSmoke", Map.of("passed", true, "tupleSha256", tuple,
				"evidenceDigest", "sha256:" + "7".repeat(64)));
		var value = new LinkedHashMap<String, Object>();
		value.put("schemaVersion", "PLATFORM_K3S_ACTIVATION_RECEIPT_V1");
		value.put("artifactKind", "platform-k3s-activation-receipt");
		value.put("orchestrator", "K3S");
		value.put("outcome", outcome);
		value.put("operation", Map.of("operationId", "sha256:" + "8".repeat(64),
			"runUrl", "https://github.com/AquilaXk/easysubway-platform/actions/runs/1",
			"generatedAt", "2026-08-28T10:00:00Z"));
		value.put("releaseIdentity", identity);
		value.put("verification", Map.of("inputsEvidenceDigest", "sha256:" + "9".repeat(64),
			"runtimeEvidenceDigest", "sha256:" + "a".repeat(64)));
		value.put("candidate", Map.of("deploymentName", "easysubway-backend-candidate",
			"candidateEvidenceDigest", "sha256:" + "b".repeat(64),
			"canaryEvidenceDigest", "sha256:" + "c".repeat(64),
			"observationEvidenceDigest", "sha256:" + "d".repeat(64),
			"candidateAdmissionSha256", "sha256:" + "e".repeat(64),
			"activeReadinessEvidenceDigest", "sha256:" + "f".repeat(64)));
		value.put("activation", activation);
		value.put("mutationCounts", Map.of("activeService", 1, "nginx", 1, "oldWorkload", 1));
		value.put("rollbackAttemptCount", 0);
		value.put("fallbackZero", Map.of("legacyGraphSuccessCount", 0, "localRouteInvocationCount", 0,
			"staleJourneyServedCount", 0, "alternateEndpointSuccessCount", 0));
		value.put("bundleAcquisitionEvidenceDigest", "sha256:" + "0".repeat(64));
		return new ObjectMapper().writeValueAsBytes(value);
	}

	private static String digest(String value) throws Exception {
		return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
			.digest(value.getBytes(StandardCharsets.UTF_8)));
	}
}

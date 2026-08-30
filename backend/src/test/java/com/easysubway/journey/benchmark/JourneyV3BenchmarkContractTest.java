package com.easysubway.journey.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.journey.application.JourneyExecutionResult;
import com.easysubway.journey.bundle.JourneyV3BenchmarkRuntimeAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JourneyV3BenchmarkContractTest {

	private static final String SHA = "a".repeat(64);
	private static final String ACTIVATION_REQUEST_IDENTITY = "activation-request-test";
	private static final JourneyExecutionResult.ActiveServingIdentity ACTIVE_SERVING_IDENTITY =
		new JourneyExecutionResult.ActiveServingIdentity(
			JourneyExecutionResult.ActiveServingIdentity.Status.OBSERVED,
			SHA, "b".repeat(64), "sha256:" + "e".repeat(64), "d".repeat(40), "03:00");

	@Test
	void acceptsCompleteSameCorpusPaceMatrix() {
		var corpus = JourneyV3CurrentProductionScopeBenchmarkTest.Contract.parseCorpus(validCorpus());
		var evidence = validatorFixture(corpus);

		JourneyV3CurrentProductionScopeBenchmarkTest.Contract.validate(evidence);

		assertThat(evidence.warm().get(JourneyV3CurrentProductionScopeBenchmarkTest.Profile.STANDARD).percentiles().p95Nanos())
			.isEqualTo(20);
	}

	@Test
	void rejectsTupleCorpusAndProfileMatrixDrift() {
		var corpus = JourneyV3CurrentProductionScopeBenchmarkTest.Contract.parseCorpus(validCorpus());
		var missingDigest = validatorFixture(corpus);
		var invalidTuple = new JourneyV3BenchmarkRuntimeAdapter.ExpectedIdentity("bad", "b".repeat(64), "c".repeat(64), "d".repeat(40));
		var invalid = new JourneyV3CurrentProductionScopeBenchmarkTest.Evidence(invalidTuple, corpus, missingDigest.cold(),
			missingDigest.warm(), missingDigest.activationRequestIdentity(),
			missingDigest.activeServingIdentity(), missingDigest.config());
		assertThatThrownBy(() -> JourneyV3CurrentProductionScopeBenchmarkTest.Contract.validate(invalid))
			.hasMessageContaining("SHA-256");

		assertThatThrownBy(() -> JourneyV3CurrentProductionScopeBenchmarkTest.Contract.parseCorpus("""
			{"schemaVersion":1,"corpusVersion":"v1","cases":[{"id":"x","originStationId":"a","destinationStationId":"a","serviceDay":"WEEKDAY","departureLocalTime":"08:00"}]}
			""")).hasMessageContaining("corpus entry");
		assertThatThrownBy(() -> JourneyV3CurrentProductionScopeBenchmarkTest.Contract.parseCorpus("""
			{"schemaVersion":1,"corpusVersion":"v1","cases":[{"id":"x","originStationId":"a","destinationStationId":"b","serviceDay":"WEEKDAY","departureLocalTime":"25:00"}]}
			""")).hasMessageContaining("corpus entry");

		var digestMismatch = new JourneyV3CurrentProductionScopeBenchmarkTest.Evidence(missingDigest.tuple(), corpus,
			missingDigest.cold(), missingDigest.warm(),
			missingDigest.activationRequestIdentity(), missingDigest.activeServingIdentity(),
			config(1, 1, "d".repeat(64)));
		assertThatThrownBy(() -> JourneyV3CurrentProductionScopeBenchmarkTest.Contract.validate(digestMismatch))
			.hasMessageContaining("corpus digest");

		var unequal = validatorFixture(corpus);
		unequal.warm().get(JourneyV3CurrentProductionScopeBenchmarkTest.Profile.FAST).inputCaseIds().clear();
		assertThatThrownBy(() -> JourneyV3CurrentProductionScopeBenchmarkTest.Contract.validate(unequal))
			.hasMessageContaining("warm matrix");
	}

	@Test
	void rejectsNonpositiveCounts() {
		var corpus = JourneyV3CurrentProductionScopeBenchmarkTest.Contract.parseCorpus(validCorpus());
		var valid = validatorFixture(corpus);
		var invalidConfig = config(0, 1, corpus.sha256());
		var invalid = new JourneyV3CurrentProductionScopeBenchmarkTest.Evidence(valid.tuple(), corpus, valid.cold(), valid.warm(),
			valid.activationRequestIdentity(),
			valid.activeServingIdentity(), invalidConfig);
		assertThatThrownBy(() -> JourneyV3CurrentProductionScopeBenchmarkTest.Contract.validate(invalid))
			.hasMessageContaining("configuration");
	}

	@Test
	void rejectsUnobservableOrNonzeroPerRequestBoundaryEvidence() {
		var corpus = JourneyV3CurrentProductionScopeBenchmarkTest.Contract.parseCorpus(validCorpus());
		var valid = validatorFixture(corpus);
		var first = valid.warm().get(JourneyV3CurrentProductionScopeBenchmarkTest.Profile.STANDARD).samples().getFirst();
		var unobservableIdentity = new JourneyV3CurrentProductionScopeBenchmarkTest.RequestIdentity(
			first.requestIdentity().requestId(), first.requestIdentity().routeBundleSha256(),
			first.requestIdentity().activationRequestIdentity(), first.requestIdentity().activeServingIdentity(),
			JourneyExecutionResult.BoundaryObservation.unobservable());
		valid.warm().get(JourneyV3CurrentProductionScopeBenchmarkTest.Profile.STANDARD).samples().set(0,
			new JourneyV3CurrentProductionScopeBenchmarkTest.Sample(first.caseId(), first.profile(), first.sequence(),
				first.nanos(), first.allocatedBytes(), first.scanMetrics(), unobservableIdentity));

		assertThatThrownBy(() -> JourneyV3CurrentProductionScopeBenchmarkTest.Contract.validate(valid))
			.hasMessageContaining("warm matrix");

		var coldInvalid = validatorFixture(corpus);
		var coldSample = coldInvalid.cold().firstSearch();
		var coldIdentity = new JourneyV3CurrentProductionScopeBenchmarkTest.RequestIdentity(
			coldSample.requestIdentity().requestId(), coldSample.requestIdentity().routeBundleSha256(),
			coldSample.requestIdentity().activationRequestIdentity(), coldSample.requestIdentity().activeServingIdentity(),
			JourneyExecutionResult.BoundaryObservation.unobservable());
		var invalidCold = new JourneyV3CurrentProductionScopeBenchmarkTest.Evidence(
			coldInvalid.tuple(), coldInvalid.corpus(), new JourneyV3CurrentProductionScopeBenchmarkTest.ColdEvidence(
				coldInvalid.cold().loadNanos(), coldInvalid.cold().loadAllocatedBytes(),
				coldInvalid.cold().verificationNanos(), coldInvalid.cold().verificationAllocatedBytes(),
				coldInvalid.cold().compilationNanos(), coldInvalid.cold().compilationAllocatedBytes(),
				new JourneyV3CurrentProductionScopeBenchmarkTest.Sample(coldSample.caseId(), coldSample.profile(),
					coldSample.sequence(), coldSample.nanos(), coldSample.allocatedBytes(), coldSample.scanMetrics(), coldIdentity)),
			coldInvalid.warm(), coldInvalid.activationRequestIdentity(), coldInvalid.activeServingIdentity(), coldInvalid.config());
		assertThatThrownBy(() -> JourneyV3CurrentProductionScopeBenchmarkTest.Contract.validate(invalidCold))
			.hasMessageContaining("cold evidence");

		var unavailableActiveServing = validatorFixture(corpus).withActiveServingIdentity(
			JourneyExecutionResult.ActiveServingIdentity.unobservable());
		assertThatThrownBy(() -> JourneyV3CurrentProductionScopeBenchmarkTest.Contract.validate(unavailableActiveServing))
			.hasMessageContaining("active-serving identity is UNOBSERVABLE");

	}

	@Test
	void parsesOnlyAClosedRequestBoundDeployedObservation() {
		var parsed = JourneyV3CurrentProductionScopeBenchmarkTest.DeployedJourneyClient.parse(observationResponse(0),
			"01K1Y000000000000000000000");

		assertThat(parsed.requestId()).isEqualTo("01K1Y000000000000000000000");
		assertThat(parsed.routeBundleSha256()).isEqualTo("c".repeat(64));
		assertThat(parsed.boundaryObservation().providerCalls()).isZero();
		assertThatThrownBy(() -> JourneyV3CurrentProductionScopeBenchmarkTest.DeployedJourneyClient.parse(
			observationResponse(1), "01K1Y000000000000000000000"))
			.hasMessageContaining("invalid");
		assertThatThrownBy(() -> JourneyV3CurrentProductionScopeBenchmarkTest.DeployedJourneyClient.parse(
			new String(observationResponse(0), StandardCharsets.UTF_8)
				.replace("}", ",\"extra\":true}").getBytes(StandardCharsets.UTF_8),
			"01K1Y000000000000000000000"))
			.hasMessageContaining("invalid");
	}

	@Test
	void bindsDeployedObservationTimeoutsToTheRequiredJourneySearchTimeout() {
		var config = JourneyV3CurrentProductionScopeBenchmarkTest.Config.from(Map.of(
			"EASYSUBWAY_BENCHMARK_WARMUP_ITERATIONS", "1",
			"EASYSUBWAY_BENCHMARK_MEASUREMENT_ITERATIONS", "1",
			"EASYSUBWAY_BENCHMARK_CORPUS_SHA256", SHA,
			"EASYSUBWAY_BENCHMARK_OUTPUT_PATH", "result.json",
			"EASYSUBWAY_JOURNEY_V3_BENCHMARK_BASE_URL", "https://journey.example",
			"EASYSUBWAY_JOURNEY_V3_READINESS_TOKEN", "token",
			"EASYSUBWAY_JOURNEY_SEARCH_TIMEOUT", "PT2S",
			"EASYSUBWAY_BENCHMARK_WEEKDAY_DATE", "2026-08-10",
			"EASYSUBWAY_BENCHMARK_WEEKEND_DATE", "2026-08-08"));
		var client = new JourneyV3CurrentProductionScopeBenchmarkTest.DeployedJourneyClient(
			config.baseUri(), config.readinessToken(), config.searchTimeout(), config.requestTimeout());

		assertThat(client.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
		assertThat(client.requestTimeout()).isEqualTo(Duration.ofSeconds(3));
		assertThat(client.request("{}").timeout()).contains(Duration.ofSeconds(3));
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
			assertThatThrownBy(() -> JourneyV3CurrentProductionScopeBenchmarkTest.Contract.parseCorpus(invalid))
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
		var evidence = validatorFixture(JourneyV3CurrentProductionScopeBenchmarkTest.Contract.parseCorpus(validCorpus()));
		Path output = directory.resolve("benchmark.json");

		JourneyV3CurrentProductionScopeBenchmarkTest.writeEvidence(output, evidence);
		byte[] first = Files.readAllBytes(output);
		var persisted = new ObjectMapper().readTree(first);
		assertThat(persisted.path("schemaVersion").asInt()).isOne();
		var config = persisted.path("config");
		assertThat(fieldNames(config)).isEqualTo(java.util.Set.of(
			"warmupIterations", "measurementIterations", "corpusSha256", "outputPath", "baseUri",
			"searchTimeout", "requestTimeout", "weekdayDate", "weekendDate"));
		assertThat(config.path("searchTimeout").asText()).isEqualTo("PT2S");
		assertThat(config.path("requestTimeout").asText()).isEqualTo("PT3S");
		var source = evidence.cold().firstSearch();
		var sample = persisted.path("cold").path("firstSearch");
		assertThat(fieldNames(sample)).isEqualTo(java.util.Set.of(
			"caseId", "profile", "sequence", "nanos", "allocatedBytes", "scanMetrics", "requestIdentity"));
		assertThat(sample.path("caseId").asText()).isEqualTo(source.caseId());
		assertThat(sample.path("profile").asText()).isEqualTo(source.profile());
		assertThat(sample.path("sequence").asInt()).isEqualTo(source.sequence());
		assertThat(sample.path("nanos").asLong()).isEqualTo(source.nanos());
		assertThat(sample.path("allocatedBytes").asLong()).isEqualTo(source.allocatedBytes());
		var scanMetrics = sample.path("scanMetrics");
		assertThat(fieldNames(scanMetrics)).isEqualTo(java.util.Set.of(
			"expandedRoutes", "expandedTrips", "expandedTransfers"));
		assertThat(scanMetrics.path("expandedRoutes").asLong()).isEqualTo(source.scanMetrics().expandedRoutes());
		assertThat(scanMetrics.path("expandedTrips").asLong()).isEqualTo(source.scanMetrics().expandedTrips());
		assertThat(scanMetrics.path("expandedTransfers").asLong()).isEqualTo(source.scanMetrics().expandedTransfers());
		var identity = sample.path("requestIdentity");
		assertThat(fieldNames(identity)).isEqualTo(java.util.Set.of(
			"requestId", "routeBundleSha256", "activationRequestIdentity", "activeServingIdentity", "boundaryObservation"));
		assertThat(identity.path("requestId").asText()).isEqualTo(source.requestIdentity().requestId());
		assertThat(identity.path("routeBundleSha256").asText())
			.isEqualTo(source.requestIdentity().routeBundleSha256());
		assertThat(identity.path("activationRequestIdentity").asText())
			.isEqualTo(source.requestIdentity().activationRequestIdentity());
		var activeServing = identity.path("activeServingIdentity");
		assertThat(fieldNames(activeServing)).isEqualTo(java.util.Set.of(
			"status", "descriptorSha256", "receiptSha256", "deploymentIdentity", "deploymentRevision", "serviceDayCutoff"));
		assertThat(activeServing.path("status").asText())
			.isEqualTo(source.requestIdentity().activeServingIdentity().status().name());
		assertThat(activeServing.path("descriptorSha256").asText())
			.isEqualTo(source.requestIdentity().activeServingIdentity().descriptorSha256());
		assertThat(activeServing.path("receiptSha256").asText())
			.isEqualTo(source.requestIdentity().activeServingIdentity().receiptSha256());
		assertThat(activeServing.path("deploymentIdentity").asText())
			.isEqualTo(source.requestIdentity().activeServingIdentity().deploymentIdentity());
		assertThat(activeServing.path("deploymentRevision").asText())
			.isEqualTo(source.requestIdentity().activeServingIdentity().deploymentRevision());
		assertThat(activeServing.path("serviceDayCutoff").asText())
			.isEqualTo(source.requestIdentity().activeServingIdentity().serviceDayCutoff());
		var boundary = identity.path("boundaryObservation");
		assertThat(fieldNames(boundary)).isEqualTo(java.util.Set.of(
			"status", "providerCalls", "cacheHits", "staleArtifactUses", "fallbackUses"));
		assertThat(boundary.path("status").asText()).isEqualTo(source.requestIdentity().boundaryObservation().status().name());
		assertThat(boundary.path("providerCalls").asLong()).isEqualTo(source.requestIdentity().boundaryObservation().providerCalls());
		assertThat(boundary.path("cacheHits").asLong()).isEqualTo(source.requestIdentity().boundaryObservation().cacheHits());
		assertThat(boundary.path("staleArtifactUses").asLong()).isEqualTo(source.requestIdentity().boundaryObservation().staleArtifactUses());
		assertThat(boundary.path("fallbackUses").asLong()).isEqualTo(source.requestIdentity().boundaryObservation().fallbackUses());

		assertThatThrownBy(() -> JourneyV3CurrentProductionScopeBenchmarkTest.writeEvidence(output, evidence))
			.isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
		assertThat(Files.readAllBytes(output)).isEqualTo(first);
	}

	private static java.util.Set<String> fieldNames(com.fasterxml.jackson.databind.JsonNode value) {
		var names = new java.util.HashSet<String>();
		value.fieldNames().forEachRemaining(names::add);
		return java.util.Set.copyOf(names);
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

	private static JourneyV3CurrentProductionScopeBenchmarkTest.Evidence validatorFixture(
		JourneyV3CurrentProductionScopeBenchmarkTest.Corpus corpus
	) {
		var warm = new EnumMap<JourneyV3CurrentProductionScopeBenchmarkTest.Profile, JourneyV3CurrentProductionScopeBenchmarkTest.WarmEvidence>(
			JourneyV3CurrentProductionScopeBenchmarkTest.Profile.class);
		for (var profile : JourneyV3CurrentProductionScopeBenchmarkTest.Profile.values()) {
			var samples = new java.util.ArrayList<>(List.of(
				new JourneyV3CurrentProductionScopeBenchmarkTest.Sample("capital-cross-city", profile.name(), 0, 10, 100,
					new JourneyV3CurrentProductionScopeBenchmarkTest.ScanMetrics(1, 2, 3), requestIdentity(profile, 0)),
				new JourneyV3CurrentProductionScopeBenchmarkTest.Sample("regional-connection", profile.name(), 0, 20, 200,
					new JourneyV3CurrentProductionScopeBenchmarkTest.ScanMetrics(1, 2, 3), requestIdentity(profile, 1))));
			warm.put(profile, new JourneyV3CurrentProductionScopeBenchmarkTest.WarmEvidence(
				new java.util.ArrayList<>(corpus.cases().stream().map(JourneyV3CurrentProductionScopeBenchmarkTest.Case::id).toList()), samples,
				JourneyV3CurrentProductionScopeBenchmarkTest.Percentiles.from(samples), new JourneyV3CurrentProductionScopeBenchmarkTest.ScanMetrics(1, 2, 3)));
		}
		var coldSearch = new JourneyV3CurrentProductionScopeBenchmarkTest.Sample(
			"capital-cross-city", JourneyV3CurrentProductionScopeBenchmarkTest.Profile.STANDARD.name(), 0, 1, 1,
			new JourneyV3CurrentProductionScopeBenchmarkTest.ScanMetrics(1, 2, 3), requestIdentity(
				JourneyV3CurrentProductionScopeBenchmarkTest.Profile.STANDARD, 6));
		return new JourneyV3CurrentProductionScopeBenchmarkTest.Evidence(
			new JourneyV3BenchmarkRuntimeAdapter.ExpectedIdentity(SHA, "b".repeat(64), "c".repeat(64), "d".repeat(40)), corpus,
			new JourneyV3CurrentProductionScopeBenchmarkTest.ColdEvidence(1, 1, 1, 1, 1, 1, coldSearch), warm,
			ACTIVATION_REQUEST_IDENTITY,
			ACTIVE_SERVING_IDENTITY,
			config(1, 1, corpus.sha256()));
	}

	private static JourneyV3CurrentProductionScopeBenchmarkTest.RequestIdentity requestIdentity(
		JourneyV3CurrentProductionScopeBenchmarkTest.Profile profile,
		int index
	) {
		return new JourneyV3CurrentProductionScopeBenchmarkTest.RequestIdentity(
			String.format("0%025d", profile.ordinal() * 2 + index + 1),
			"c".repeat(64), ACTIVATION_REQUEST_IDENTITY, ACTIVE_SERVING_IDENTITY,
			new JourneyExecutionResult.BoundaryObservation(
				JourneyExecutionResult.BoundaryObservation.Status.OBSERVED, 0L, 0L, 0L, 0L));
	}

	private static JourneyV3CurrentProductionScopeBenchmarkTest.Config config(
		int warmup,
		int measurement,
		String corpusSha256
	) {
		return new JourneyV3CurrentProductionScopeBenchmarkTest.Config(warmup, measurement, corpusSha256,
			Path.of("result.json"), java.net.URI.create("https://journey.example"), null,
			Duration.ofSeconds(2),
			java.time.LocalDate.parse("2026-08-10"), java.time.LocalDate.parse("2026-08-08"));
	}

	private static String validCorpus() {
		return """
			{"schemaVersion":1,"corpusVersion":"v1","cases":[
			{"id":"capital-cross-city","originStationId":"station-origin","destinationStationId":"station-destination","serviceDay":"WEEKDAY","departureLocalTime":"08:00"},
			{"id":"regional-connection","originStationId":"station-regional-origin","destinationStationId":"station-regional-destination","serviceDay":"WEEKEND","departureLocalTime":"14:30"}]}
			""";
	}

	private static byte[] observationResponse(int providerCalls) {
		return ("""
			{"requestId":"01K1Y000000000000000000000","routeBundleSha256":"%s","bundleGeneration":1,
			"serviceDay":{"serviceDate":"2026-08-10","timezone":"Asia/Seoul","cutoffLocalTime":"03:00"},
			"scanMetrics":{"expandedRoutes":1,"expandedTrips":2,"expandedTransfers":3},
			"boundaryObservation":{"status":"OBSERVED","providerCalls":%d,"cacheHits":0,"staleArtifactUses":0,"fallbackUses":0},
			"executionNanos":10,"allocatedBytes":20,
			"activeReadiness":{"schemaVersion":1,"artifactKind":"journey-v3-active-readiness","instanceId":"backend-a",
			"releaseTupleSha256":"%s","backendImageDigest":"sha256:%s","backendConfigSha256":"%s",
			"journeyContractSha256":"%s","routeBundleManifestSha256":"%s","bundleId":"bundle-a",
			"bundleReleaseSequence":1,"generation":1,"serviceTimezone":"Asia/Seoul","serviceDayCutoff":"03:00",
			"trafficGeneration":1,"servingReady":true,"draining":false,"freshUntil":"2026-08-11T00:00:00Z",
			"activatedAt":"2026-08-10T00:00:00Z","evidenceSha256":"%s"}}
			""").formatted("c".repeat(64), providerCalls, "a".repeat(64), "b".repeat(64),
			"c".repeat(64), "d".repeat(64), "c".repeat(64), "e".repeat(64)).getBytes(StandardCharsets.UTF_8);
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

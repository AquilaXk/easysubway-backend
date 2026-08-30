package com.easysubway.journey.benchmark;

import static java.util.Objects.requireNonNull;

import com.easysubway.journey.application.JourneyExecutionResult;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.journey.bundle.JourneyV3BenchmarkRuntimeAdapter;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.management.ThreadMXBean;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Executable, opt-in same-RC current-production-scope Journey V3 benchmark for #297. */
@DisplayName("#297 Journey V3 current-production-scope same-RC benchmark")
@EnabledIfEnvironmentVariable(named = "EASYSUBWAY_BENCHMARK", matches = "true")
class JourneyV3CurrentProductionScopeBenchmarkTest {

	private static final String CORPUS = "journey-benchmark/v1/current-production-scope-corpus.json";
	private static final ObjectMapper OUTPUT = new ObjectMapper();
	private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

	@Test
	@DisplayName("measures the deployment-provided ACTIVE_SERVING Journey V3 runtime")
	void measuresActiveServingRuntime() throws Exception {
		Map<String, String> environment = System.getenv();
		Corpus corpus = readCorpus();
		Config config = Config.from(environment);
		if (!corpus.sha256().equals(config.corpusSha256())) throw new IllegalArgumentException("benchmark corpus digest does not match the required runtime value");
		ThreadMXBean allocations = allocationBean();

		JourneyV3BenchmarkRuntimeAdapter.ExpectedIdentity tuple = JourneyV3BenchmarkRuntimeAdapter.expectedIdentity(environment);
		Phase<JourneyV3BenchmarkRuntimeAdapter.Loaded> load = measure(allocations,
			() -> JourneyV3BenchmarkRuntimeAdapter.load(environment, tuple));
		Phase<JourneyV3BenchmarkRuntimeAdapter.Verified> verify = measure(allocations,
			() -> JourneyV3BenchmarkRuntimeAdapter.verify(load.value(), tuple));
		Phase<JourneyV3BenchmarkRuntimeAdapter.Compiled> compile = measure(allocations,
			() -> JourneyV3BenchmarkRuntimeAdapter.compile(verify.value()));
		JourneyV3BenchmarkRuntimeAdapter.Compiled compiled = compile.value();

		List<RequestCase> requests = corpus.cases().stream().map(testCase -> new RequestCase(testCase,
			config.scheduledInstant(testCase.serviceDay(), testCase.departureLocalTime()))).toList();
		var deployed = new DeployedJourneyClient(config.baseUri(), config.readinessToken(), config.searchTimeout(),
			config.requestTimeout());
		Sample firstSearch = run(deployed, requests.getFirst(), Profile.STANDARD, 0,
			0, tuple.routeBundleSha256(), compiled.activationRequestIdentity(), compiled.activeServingProjection());
		warm(deployed, requests, config.warmupIterations(), tuple.routeBundleSha256(), compiled.activationRequestIdentity(),
			compiled.activeServingProjection());
		Map<Profile, WarmEvidence> warm = measureProfiles(deployed, requests, config, tuple.routeBundleSha256(),
			compiled.activationRequestIdentity(), compiled.activeServingProjection());
		Evidence evidence = new Evidence(tuple, corpus, new ColdEvidence(load.nanos(), load.allocatedBytes(),
			verify.nanos(), verify.allocatedBytes(), compile.nanos(), compile.allocatedBytes(), firstSearch), warm,
			compiled.activationRequestIdentity(), compiled.activeServingIdentity(), config);
		Contract.validate(evidence);
		writeEvidence(config.outputPath(), evidence);
	}

	private static Corpus readCorpus() throws Exception {
		try (InputStream stream = requireNonNull(JourneyV3CurrentProductionScopeBenchmarkTest.class.getClassLoader().getResourceAsStream(CORPUS))) {
			return Contract.parseCorpus(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
		}
	}

	private static void warm(DeployedJourneyClient client, List<RequestCase> requests, int iterations,
		String routeBundleSha256, String activationRequestIdentity,
		JourneyV3BenchmarkRuntimeAdapter.ActiveServingProjection activeServingProjection) {
		for (Profile profile : Profile.values()) for (int iteration = 0; iteration < iterations; iteration++)
			for (int index = 0; index < requests.size(); index += 1) run(client, requests.get(index), profile,
				iteration, 1 + profile.ordinal() * iterations * requests.size() + iteration * requests.size() + index,
				routeBundleSha256, activationRequestIdentity, activeServingProjection);
	}

	private static Map<Profile, WarmEvidence> measureProfiles(DeployedJourneyClient client,
		List<RequestCase> requests, Config config, String routeBundleSha256,
		String activationRequestIdentity, JourneyV3BenchmarkRuntimeAdapter.ActiveServingProjection activeServingProjection) {
		var output = new EnumMap<Profile, WarmEvidence>(Profile.class);
		for (Profile profile : Profile.values()) {
			var samples = new ArrayList<Sample>();
			long routes = 0, trips = 0, transfers = 0;
			for (int iteration = 0; iteration < config.measurementIterations(); iteration++) for (int index = 0;
				index < requests.size(); index += 1) {
				RequestCase request = requests.get(index);
				int sequence = iteration + config.warmupIterations();
				Sample measured = run(client, request, profile,
					sequence, 1 + Profile.values().length * config.warmupIterations() * requests.size()
						+ profile.ordinal() * config.measurementIterations() * requests.size()
						+ iteration * requests.size() + index, routeBundleSha256, activationRequestIdentity,
						activeServingProjection);
				samples.add(measured);
				ScanMetrics scan = measured.scanMetrics();
				routes += scan.expandedRoutes(); trips += scan.expandedTrips(); transfers += scan.expandedTransfers();
			}
			output.put(profile, new WarmEvidence(requests.stream().map(item -> item.testCase().id()).toList(),
				List.copyOf(samples), Percentiles.from(samples), new ScanMetrics(routes, trips, transfers)));
		}
		return Map.copyOf(output);
	}

	private static Sample run(DeployedJourneyClient client, RequestCase request, Profile profile, int sequence,
		int requestOrdinal, String routeBundleSha256, String activationRequestIdentity,
		JourneyV3BenchmarkRuntimeAdapter.ActiveServingProjection activeServingProjection) {
		String generatedRequestId = requestId(requestOrdinal);
		DeployedObservation observation = client.search(generatedRequestId, request, profile);
		if (!observation.routeBundleSha256().equals(routeBundleSha256)
			|| !observation.requestId().equals(generatedRequestId)) throw new IllegalStateException(
				"benchmark response identity does not match the active route bundle");
		if (!observation.matches(activeServingProjection)) {
			throw new IllegalStateException("benchmark response does not match the active-serving receipt");
		}
		return new Sample(request.testCase().id(), profile.name(), sequence, observation.executionNanos(), observation.allocatedBytes(),
			observation.scanMetrics(), new RequestIdentity(generatedRequestId, observation.routeBundleSha256(),
				activationRequestIdentity, activeServingProjection.activeServingIdentity(), observation.boundaryObservation()));
	}

	private static String requestId(int value) {
		char[] alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
		char[] result = new char[26]; result[0] = '0';
		for (int position = 25; position > 0; position--) { result[position] = alphabet[value & 31]; value >>>= 5; }
		return new String(result);
	}

	private static ThreadMXBean allocationBean() {
		if (!(ManagementFactory.getThreadMXBean() instanceof ThreadMXBean bean) || !bean.isThreadAllocatedMemorySupported())
			throw new IllegalStateException("ThreadMXBean allocation measurement is unavailable");
		if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
		return bean;
	}

	private static <T> Phase<T> measure(ThreadMXBean allocations, CheckedSupplier<T> action) throws Exception {
		long thread = Thread.currentThread().threadId(), bytes = allocations.getThreadAllocatedBytes(thread), started = System.nanoTime();
		T value = action.get();
		return new Phase<>(value, System.nanoTime() - started, allocations.getThreadAllocatedBytes(thread) - bytes);
	}

	static void writeEvidence(Path path, Evidence evidence) throws Exception {
		if (path.getParent() == null || !Files.isDirectory(path.getParent())) throw new IllegalArgumentException("benchmark output parent must exist");
		Path temporary = Files.createTempFile(path.getParent(), path.getFileName().toString() + ".", ".tmp");
		try {
			try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
				var bytes = java.nio.ByteBuffer.wrap(OUTPUT.writeValueAsBytes(evidence.asJson()));
				while (bytes.hasRemaining()) channel.write(bytes);
				channel.force(true);
			}
			Files.createLink(path, temporary);
			Files.delete(temporary);
		} catch (Exception exception) { Files.deleteIfExists(temporary); throw exception; }
	}

	enum Profile { SLOW, STANDARD, FAST }
	record Config(int warmupIterations, int measurementIterations, String corpusSha256, Path outputPath,
		URI baseUri, String readinessToken, Duration searchTimeout, LocalDate weekdayDate, LocalDate weekendDate) {
		static Config from(Map<String, String> environment) {
			return new Config(positive(environment, "EASYSUBWAY_BENCHMARK_WARMUP_ITERATIONS"),
				positive(environment, "EASYSUBWAY_BENCHMARK_MEASUREMENT_ITERATIONS"),
				requiredSha(environment, "EASYSUBWAY_BENCHMARK_CORPUS_SHA256"),
				Path.of(required(environment, "EASYSUBWAY_BENCHMARK_OUTPUT_PATH")),
				requiredHttpsUri(environment, "EASYSUBWAY_JOURNEY_V3_BENCHMARK_BASE_URL"),
				required(environment, "EASYSUBWAY_JOURNEY_V3_READINESS_TOKEN"),
				positiveDuration(environment, "EASYSUBWAY_JOURNEY_SEARCH_TIMEOUT"),
				requiredServiceDate(environment, "EASYSUBWAY_BENCHMARK_WEEKDAY_DATE", true),
				requiredServiceDate(environment, "EASYSUBWAY_BENCHMARK_WEEKEND_DATE", false));
		}

		Duration requestTimeout() { return searchTimeout.plusSeconds(1); }

		Instant scheduledInstant(String serviceDay, String departureLocalTime) {
			LocalDate date = switch (serviceDay) {
				case "WEEKDAY" -> weekdayDate;
				case "WEEKEND" -> weekendDate;
				default -> throw new IllegalArgumentException("benchmark service day is invalid");
			};
			return date.atTime(LocalTime.parse(departureLocalTime)).atZone(SERVICE_ZONE).toInstant();
		}
	}
	record Corpus(String version, String sha256, List<Case> cases) { }
	record Case(String id, String originStationId, String destinationStationId, String serviceDay, String departureLocalTime) { }
	record RequestCase(Case testCase, Instant scheduledInstant) { }
	record RequestIdentity(
		String requestId,
		String routeBundleSha256,
		String activationRequestIdentity,
		JourneyExecutionResult.ActiveServingIdentity activeServingIdentity,
		JourneyExecutionResult.BoundaryObservation boundaryObservation
	) { }
	record Sample(String caseId, String profile, int sequence, long nanos, long allocatedBytes, ScanMetrics scanMetrics,
		RequestIdentity requestIdentity) {
		Sample withMeasurement(long nanos, long allocatedBytes) {
			return new Sample(caseId, profile, sequence, nanos, allocatedBytes, scanMetrics, requestIdentity);
		}
	}
	record Phase<T>(T value, long nanos, long allocatedBytes) { }
	record Percentiles(long p50Nanos, long p95Nanos, long p99Nanos, long maxNanos) {
		static Percentiles from(List<Sample> samples) { long[] values = samples.stream().mapToLong(Sample::nanos).sorted().toArray();
			if (values.length == 0) throw new IllegalArgumentException("samples must not be empty");
			return new Percentiles(at(values, 50), at(values, 95), at(values, 99), values[values.length - 1]); }
		private static long at(long[] values, int percentile) { return values[(int) Math.ceil(values.length * percentile / 100.0d) - 1]; }
	}
	record ScanMetrics(long expandedRoutes, long expandedTrips, long expandedTransfers) { }
	record ColdEvidence(long loadNanos, long loadAllocatedBytes, long verificationNanos, long verificationAllocatedBytes,
		long compilationNanos, long compilationAllocatedBytes, Sample firstSearch) { }
	record WarmEvidence(List<String> inputCaseIds, List<Sample> samples, Percentiles percentiles, ScanMetrics scanMetrics) { }
	record Evidence(JourneyV3BenchmarkRuntimeAdapter.ExpectedIdentity tuple, Corpus corpus, ColdEvidence cold,
		Map<Profile, WarmEvidence> warm,
		String activationRequestIdentity, JourneyExecutionResult.ActiveServingIdentity activeServingIdentity, Config config) {
		Evidence withActiveServingIdentity(JourneyExecutionResult.ActiveServingIdentity replacement) {
			return new Evidence(tuple, corpus, cold, warm, activationRequestIdentity, replacement, config);
		}
		Map<String, Object> asJson() { var result = new LinkedHashMap<String, Object>(); result.put("schemaVersion", 1); result.put("tuple", tuple); result.put("corpus", corpus);
			result.put("scope", "current-production-scope");
			result.put("jvm", Map.of("javaVersion", System.getProperty("java.version"), "vmName", System.getProperty("java.vm.name")));
			result.put("walkingPaceMetersPerHour", Map.of(
				"SLOW", JourneyRequest.WalkingPace.SLOW.speedMetersPerHour(),
				"STANDARD", JourneyRequest.WalkingPace.STANDARD.speedMetersPerHour(),
				"FAST", JourneyRequest.WalkingPace.FAST.speedMetersPerHour()));
			result.put("config", Map.of("warmupIterations", config.warmupIterations(), "corpusSha256", config.corpusSha256(),
				"measurementIterations", config.measurementIterations(), "outputPath", config.outputPath().toString(),
				"baseUri", config.baseUri().toString(), "searchTimeout", config.searchTimeout().toString(),
				"requestTimeout", config.requestTimeout().toString(), "weekdayDate", config.weekdayDate().toString(),
				"weekendDate", config.weekendDate().toString()));
			result.put("cold", cold); result.put("warm", warm); result.put("activationRequestIdentity", activationRequestIdentity); result.put("activeServingIdentity", activeServingIdentity); return result; }
	}

	record DeployedObservation(String requestId, String routeBundleSha256, long bundleGeneration,
		ScanMetrics scanMetrics, JourneyExecutionResult.BoundaryObservation boundaryObservation,
		long executionNanos, long allocatedBytes, RemoteActiveReadiness activeReadiness) {
		boolean matches(JourneyV3BenchmarkRuntimeAdapter.ActiveServingProjection projection) {
			return routeBundleSha256.equals(projection.routeBundleManifestSha256())
				&& bundleGeneration == projection.candidateGeneration()
				&& activeReadiness.matches(projection);
		}
	}

	record RemoteActiveReadiness(String releaseTupleSha256, String backendImageDigest,
		String backendConfigSha256, String journeyContractSha256, String routeBundleManifestSha256,
		long generation, String serviceTimezone, String serviceDayCutoff, long trafficGeneration,
		boolean servingReady, boolean draining, String evidenceSha256) {
		boolean matches(JourneyV3BenchmarkRuntimeAdapter.ActiveServingProjection projection) {
			return servingReady && !draining
				&& releaseTupleSha256.equals(projection.releaseTupleSha256())
				&& backendImageDigest.equals(projection.backendImageDigest())
				&& backendConfigSha256.equals(projection.backendConfigSha256())
				&& journeyContractSha256.equals(projection.journeyContractSha256())
				&& routeBundleManifestSha256.equals(projection.routeBundleManifestSha256())
				&& generation == projection.candidateGeneration()
				&& trafficGeneration == projection.trafficGeneration()
				&& "Asia/Seoul".equals(serviceTimezone) && "03:00".equals(serviceDayCutoff)
				&& ("sha256:" + evidenceSha256).equals(projection.activeReadinessEvidenceDigest());
		}
	}

	static final class DeployedJourneyClient {
		private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
			.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
		private static final Set<String> RESPONSE_FIELDS = Set.of("requestId", "routeBundleSha256", "bundleGeneration",
			"serviceDay", "scanMetrics", "boundaryObservation", "executionNanos", "allocatedBytes", "activeReadiness");
		private static final Set<String> ACTIVE_READINESS_FIELDS = Set.of("schemaVersion", "artifactKind", "instanceId",
			"releaseTupleSha256", "backendImageDigest", "backendConfigSha256", "journeyContractSha256",
			"routeBundleManifestSha256", "bundleId", "bundleReleaseSequence", "generation", "serviceTimezone",
			"serviceDayCutoff", "trafficGeneration", "servingReady", "draining", "freshUntil", "activatedAt", "evidenceSha256");
		private final HttpClient client;
		private final URI endpoint;
		private final String token;
		private final Duration connectTimeout;
		private final Duration requestTimeout;

		DeployedJourneyClient(URI baseUri, String token, Duration connectTimeout, Duration requestTimeout) {
			endpoint = baseUri.resolve("/internal/v1/journey/benchmark-observation");
			this.token = requireNonNull(token);
			this.connectTimeout = positiveTimeout(connectTimeout, "benchmark connect timeout");
			this.requestTimeout = positiveTimeout(requestTimeout, "benchmark request timeout");
			client = HttpClient.newBuilder().connectTimeout(this.connectTimeout)
				.followRedirects(HttpClient.Redirect.NEVER).build();
		}

		DeployedObservation search(String requestId, RequestCase request, Profile profile) {
			try {
				String body = JSON.writeValueAsString(Map.of("requestId", requestId,
					"originStationId", request.testCase().originStationId(), "destinationStationId", request.testCase().destinationStationId(),
					"departure", Map.of("mode", "SCHEDULED", "requestedAt", request.scheduledInstant().toString()),
					"timePolicy", "TIMETABLE_REQUIRED", "walkingPace", profile.name(), "mobilityProfile", "STANDARD",
					"constraintMode", "NONE", "maxTransfers", 3, "alternativeCount", 1));
				HttpResponse<byte[]> response = client.send(request(body), HttpResponse.BodyHandlers.ofByteArray());
				if (response.statusCode() != 200 || !"no-store".equals(response.headers().firstValue("Cache-Control").orElse(null))) {
					throw new IllegalStateException("deployed Journey V3 observation request was not a no-store success");
				}
				return parse(response.body(), requestId);
			} catch (IOException exception) {
				throw new IllegalStateException("deployed Journey V3 observation request failed", exception);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("deployed Journey V3 observation request was interrupted", exception);
			}
		}

		Duration connectTimeout() { return connectTimeout; }
		Duration requestTimeout() { return requestTimeout; }

		HttpRequest request(String body) {
			return HttpRequest.newBuilder(endpoint).timeout(requestTimeout)
				.header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body)).build();
		}

		static DeployedObservation parse(byte[] body, String expectedRequestId) {
			try {
				JsonNode root = JSON.readTree(body);
				if (!hasExactFields(root, RESPONSE_FIELDS)) throw new IllegalArgumentException("benchmark response fields are invalid");
				String requestId = text(root, "requestId"), routeBundleSha256 = text(root, "routeBundleSha256");
				if (!requestId.equals(expectedRequestId) || !routeBundleSha256.matches("[a-f0-9]{64}")
					|| !root.path("bundleGeneration").isIntegralNumber() || root.path("bundleGeneration").longValue() < 1) throw new IllegalArgumentException("benchmark response identity is invalid");
				JsonNode serviceDay = root.path("serviceDay");
				if (!hasExactFields(serviceDay, Set.of("serviceDate", "timezone", "cutoffLocalTime"))
					|| !"Asia/Seoul".equals(text(serviceDay, "timezone")) || !"03:00".equals(text(serviceDay, "cutoffLocalTime"))) throw new IllegalArgumentException("benchmark response service day is invalid");
				LocalDate.parse(text(serviceDay, "serviceDate"));
				JsonNode scan = root.path("scanMetrics");
				if (!hasExactFields(scan, Set.of("expandedRoutes", "expandedTrips", "expandedTransfers"))) throw new IllegalArgumentException("benchmark response scan metrics are invalid");
				ScanMetrics metrics = new ScanMetrics(nonnegative(scan, "expandedRoutes"), nonnegative(scan, "expandedTrips"), nonnegative(scan, "expandedTransfers"));
				JsonNode boundary = root.path("boundaryObservation");
				if (!hasExactFields(boundary, Set.of("status", "providerCalls", "cacheHits", "staleArtifactUses", "fallbackUses"))
					|| !"OBSERVED".equals(text(boundary, "status"))) throw new IllegalArgumentException("benchmark boundary is unobservable");
				var observation = new JourneyExecutionResult.BoundaryObservation(JourneyExecutionResult.BoundaryObservation.Status.OBSERVED,
					nonnegative(boundary, "providerCalls"), nonnegative(boundary, "cacheHits"), nonnegative(boundary, "staleArtifactUses"), nonnegative(boundary, "fallbackUses"));
				if (observation.providerCalls() != 0 || observation.cacheHits() != 0 || observation.staleArtifactUses() != 0 || observation.fallbackUses() != 0) throw new IllegalArgumentException("benchmark boundary counters are not zero");
				long executionNanos = nonnegative(root, "executionNanos");
				long allocatedBytes = nonnegative(root, "allocatedBytes");
				return new DeployedObservation(requestId, routeBundleSha256, root.path("bundleGeneration").longValue(),
					metrics, observation, executionNanos, allocatedBytes, activeReadiness(root.path("activeReadiness")));
			} catch (IOException | RuntimeException exception) {
				throw new IllegalArgumentException("deployed Journey V3 observation response is invalid", exception);
			}
		}

		private static Duration positiveTimeout(Duration value, String name) {
			if (value == null || value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
			return value;
		}

		private static String text(JsonNode object, String name) {
			if (!object.path(name).isTextual() || object.path(name).textValue().isBlank()) throw new IllegalArgumentException();
			return object.path(name).textValue();
		}

		private static long nonnegative(JsonNode object, String name) {
			if (!object.path(name).isIntegralNumber() || object.path(name).longValue() < 0) throw new IllegalArgumentException();
			return object.path(name).longValue();
		}

		private static RemoteActiveReadiness activeReadiness(JsonNode value) {
			if (!hasExactFields(value, ACTIVE_READINESS_FIELDS) || value.path("schemaVersion").intValue() != 1
				|| !value.path("schemaVersion").isIntegralNumber()
				|| !"journey-v3-active-readiness".equals(text(value, "artifactKind"))
				|| !value.path("servingReady").isBoolean() || !value.path("draining").isBoolean()) {
				throw new IllegalArgumentException("active readiness is invalid");
			}
			for (String field : Set.of("releaseTupleSha256", "backendConfigSha256", "journeyContractSha256",
				"routeBundleManifestSha256", "evidenceSha256")) {
				if (!text(value, field).matches("[a-f0-9]{64}")) throw new IllegalArgumentException("active readiness is invalid");
			}
			if (!text(value, "backendImageDigest").matches("sha256:[a-f0-9]{64}")
				|| !"Asia/Seoul".equals(text(value, "serviceTimezone"))
				|| !"03:00".equals(text(value, "serviceDayCutoff"))) {
				throw new IllegalArgumentException("active readiness is invalid");
			}
			try {
				Instant.parse(text(value, "freshUntil"));
				Instant.parse(text(value, "activatedAt"));
			} catch (RuntimeException exception) {
				throw new IllegalArgumentException("active readiness is invalid", exception);
			}
			return new RemoteActiveReadiness(text(value, "releaseTupleSha256"), text(value, "backendImageDigest"),
				text(value, "backendConfigSha256"), text(value, "journeyContractSha256"),
				text(value, "routeBundleManifestSha256"), positiveLong(value, "generation"),
				text(value, "serviceTimezone"), text(value, "serviceDayCutoff"), positiveLong(value, "trafficGeneration"),
				value.path("servingReady").booleanValue(), value.path("draining").booleanValue(), text(value, "evidenceSha256"));
		}

		private static long positiveLong(JsonNode object, String name) {
			long value = nonnegative(object, name);
			if (value < 1) throw new IllegalArgumentException();
			return value;
		}
	}

	static final class Contract {
		private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
		private static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");
		static Corpus parseCorpus(String json) { try { JsonNode root = JSON.readTree(requireNonNull(json));
			if (root == null || !root.isObject() || !root.path("schemaVersion").isIntegralNumber()
				|| root.path("schemaVersion").intValue() != 1 || !root.path("corpusVersion").isTextual()
				|| !"v1".equals(root.path("corpusVersion").textValue()) || !root.path("cases").isArray()) throw new IllegalArgumentException("corpus version is invalid");
			var cases = new ArrayList<Case>(); var ids = new java.util.HashSet<String>();
			for (JsonNode item : root.path("cases")) { if (!item.isObject()) throw new IllegalArgumentException("corpus entry is invalid"); Case value = new Case(text(item, "id"), text(item, "originStationId"), text(item, "destinationStationId"), text(item, "serviceDay"), text(item, "departureLocalTime"));
				try { LocalTime parsed = LocalTime.parse(value.departureLocalTime()); if (parsed.getSecond() != 0 || parsed.getNano() != 0 || value.departureLocalTime().length() != 5) throw new IllegalArgumentException(); }
				catch (RuntimeException exception) { throw new IllegalArgumentException("corpus entry is invalid", exception); }
				if (value.originStationId().equals(value.destinationStationId()) || !Set.of("WEEKDAY", "WEEKEND").contains(value.serviceDay()) || !value.departureLocalTime().matches("[0-2]\\d:[0-5]\\d") || !ids.add(value.id())) throw new IllegalArgumentException("corpus entry is invalid"); cases.add(value); }
			if (cases.isEmpty()) throw new IllegalArgumentException("corpus must not be empty"); return new Corpus("v1", sha(json.getBytes(StandardCharsets.UTF_8)), List.copyOf(cases));
		} catch (java.io.IOException exception) { throw new IllegalArgumentException("corpus is malformed", exception); } }
		static void validate(Evidence evidence) { requireNonNull(evidence); requireSha(evidence.tuple().descriptorSha256(), "descriptorSha256"); requireSha(evidence.tuple().receiptSha256(), "receiptSha256"); requireSha(evidence.tuple().routeBundleSha256(), "routeBundleSha256"); if (evidence.tuple().deploymentRevision() == null || !evidence.tuple().deploymentRevision().matches("[a-f0-9]{40}")) throw new IllegalArgumentException("deployment revision is invalid"); requireSha(evidence.corpus().sha256(), "corpusSha256"); requireSha(evidence.config().corpusSha256(), "corpusSha256"); if (!evidence.corpus().sha256().equals(evidence.config().corpusSha256())) throw new IllegalArgumentException("corpus digest is invalid");
			if (!evidence.asJson().keySet().equals(Set.of("schemaVersion", "tuple", "corpus", "scope", "jvm",
				"walkingPaceMetersPerHour", "config", "cold", "warm", "activationRequestIdentity",
				"activeServingIdentity"))) throw new IllegalArgumentException("benchmark output fields are incomplete");
			if (evidence.config().warmupIterations() < 1 || evidence.config().measurementIterations() < 1
				|| evidence.config().searchTimeout().isZero() || evidence.config().searchTimeout().isNegative()
				|| !evidence.config().requestTimeout().equals(evidence.config().searchTimeout().plusSeconds(1))
				|| !evidence.warm().keySet().equals(Set.of(Profile.values()))) throw new IllegalArgumentException("benchmark configuration is incomplete");
			for (long value : List.of(evidence.cold().loadNanos(), evidence.cold().loadAllocatedBytes(), evidence.cold().verificationNanos(), evidence.cold().verificationAllocatedBytes(), evidence.cold().compilationNanos(), evidence.cold().compilationAllocatedBytes())) if (value < 0) throw new IllegalArgumentException("cold evidence is invalid");
			if (evidence.activationRequestIdentity() == null || evidence.activationRequestIdentity().isBlank()
				|| evidence.activeServingIdentity() == null) throw new IllegalArgumentException("request identity is incomplete");
			var activeServing = evidence.activeServingIdentity();
			if (activeServing.status() != JourneyExecutionResult.ActiveServingIdentity.Status.OBSERVED) {
				throw new IllegalArgumentException("active-serving identity is UNOBSERVABLE");
			}
			if (!activeServing.descriptorSha256().equals(evidence.tuple().descriptorSha256())
				|| !activeServing.receiptSha256().equals(evidence.tuple().receiptSha256())
				|| !activeServing.deploymentRevision().equals(evidence.tuple().deploymentRevision())
				|| !activeServing.deploymentIdentity().matches("sha256:[0-9a-f]{64}")
				|| !"03:00".equals(activeServing.serviceDayCutoff())) {
				throw new IllegalArgumentException("active-serving identity does not match the exact tuple");
			}
			List<String> ids = evidence.corpus().cases().stream().map(Case::id).toList(); int count = ids.size() * evidence.config().measurementIterations();
			Set<String> requestIds = new java.util.HashSet<>();
			if (!validSample(evidence.cold().firstSearch(), evidence, requestIds)) {
				throw new IllegalArgumentException("cold evidence is invalid");
			}
			for (var entry : evidence.warm().entrySet()) { WarmEvidence warm = entry.getValue();
				Map<String, Long> perCase = warm.samples().stream().collect(java.util.stream.Collectors.groupingBy(Sample::caseId, java.util.stream.Collectors.counting()));
				if (!warm.inputCaseIds().equals(ids) || warm.samples().size() != count || !perCase.keySet().equals(Set.copyOf(ids))
					|| perCase.values().stream().anyMatch(value -> value != evidence.config().measurementIterations())
					|| !warm.percentiles().equals(Percentiles.from(warm.samples())) || warm.samples().stream().anyMatch(sample -> !sample.profile().equals(entry.getKey().name())
						|| !validSample(sample, evidence, requestIds))) throw new IllegalArgumentException("warm matrix is invalid"); }
			for (WarmEvidence item : evidence.warm().values()) if (item.scanMetrics().expandedRoutes() < 0 || item.scanMetrics().expandedTrips() < 0 || item.scanMetrics().expandedTransfers() < 0) throw new IllegalArgumentException("scan metrics are invalid"); }
		private static boolean validSample(Sample sample, Evidence evidence, Set<String> requestIds) {
			return sample != null && sample.nanos() >= 0 && sample.allocatedBytes() >= 0
				&& sample.requestIdentity() != null
				&& sample.requestIdentity().requestId().matches("^[0-7][0-9A-HJKMNP-TV-Z]{25}$")
				&& requestIds.add(sample.requestIdentity().requestId())
				&& sample.requestIdentity().routeBundleSha256().equals(evidence.tuple().routeBundleSha256())
				&& sample.requestIdentity().activationRequestIdentity().equals(evidence.activationRequestIdentity())
				&& sample.requestIdentity().activeServingIdentity().equals(evidence.activeServingIdentity())
				&& sample.requestIdentity().boundaryObservation() != null
				&& sample.requestIdentity().boundaryObservation().status()
					== JourneyExecutionResult.BoundaryObservation.Status.OBSERVED
				&& sample.requestIdentity().boundaryObservation().providerCalls() == 0
				&& sample.requestIdentity().boundaryObservation().cacheHits() == 0
				&& sample.requestIdentity().boundaryObservation().staleArtifactUses() == 0
				&& sample.requestIdentity().boundaryObservation().fallbackUses() == 0;
		}
		private static String text(JsonNode node, String name) { String value = node.path(name).textValue(); if (value == null || value.isBlank()) throw new IllegalArgumentException("corpus " + name + " is required"); return value; }
		private static void requireSha(String value, String field) { if (value == null || !SHA.matcher(value).matches()) throw new IllegalArgumentException(field + " must be a SHA-256 digest"); }
		private static String sha(byte[] bytes) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); } }
	}
	private static String required(Map<String, String> environment, String name) { String value = environment.get(name); if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required"); return value; }
	private static URI requiredHttpsUri(Map<String, String> environment, String name) {
		URI value;
		try { value = URI.create(required(environment, name)); }
		catch (IllegalArgumentException exception) { throw new IllegalArgumentException(name + " must be an absolute HTTPS URL", exception); }
		if (!value.isAbsolute() || !"https".equals(value.getScheme()) || value.getHost() == null
			|| value.getRawUserInfo() != null || value.getRawQuery() != null || value.getRawFragment() != null
			|| !(value.getPath().isEmpty() || "/".equals(value.getPath()))) {
			throw new IllegalArgumentException(name + " must be an absolute HTTPS URL");
		}
		return value;
	}
	private static LocalDate requiredServiceDate(Map<String, String> environment, String name, boolean weekday) {
		try {
			LocalDate value = LocalDate.parse(required(environment, name));
			boolean actualWeekday = value.getDayOfWeek().getValue() <= 5;
			if (actualWeekday != weekday) throw new IllegalArgumentException(name + " has the wrong service-day class");
			return value;
		} catch (java.time.format.DateTimeParseException exception) {
			throw new IllegalArgumentException(name + " must be an ISO-8601 date", exception);
		}
	}
	private static boolean hasExactFields(JsonNode value, Set<String> expected) {
		if (value == null || !value.isObject() || value.size() != expected.size()) return false;
		var actual = new java.util.HashSet<String>();
		value.fieldNames().forEachRemaining(actual::add);
		return actual.equals(expected);
	}
	private static String requiredSha(Map<String, String> environment, String name) { String value = required(environment, name); if (!Contract.SHA.matcher(value).matches()) throw new IllegalArgumentException(name + " must be a SHA-256 digest"); return value; }
	private static int positive(Map<String, String> environment, String name) { try { int value = Integer.parseInt(required(environment, name)); if (value < 1) throw new IllegalArgumentException(name + " must be positive"); return value; } catch (NumberFormatException exception) { throw new IllegalArgumentException(name + " must be an integer", exception); } }
	private static Duration positiveDuration(Map<String, String> environment, String name) {
		try {
			Duration value = Duration.parse(required(environment, name));
			if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
			value.plusSeconds(1);
			return value;
		} catch (java.time.format.DateTimeParseException | ArithmeticException exception) {
			throw new IllegalArgumentException(name + " must be an ISO-8601 duration", exception);
		}
	}
	@FunctionalInterface interface CheckedSupplier<T> { T get() throws Exception; }
}

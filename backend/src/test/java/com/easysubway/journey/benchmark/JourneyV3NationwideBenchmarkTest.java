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
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalTime;
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

/** Executable, opt-in same-RC nationwide Journey V3 benchmark for #297. */
@DisplayName("#297 Journey V3 nationwide same-RC benchmark")
@EnabledIfEnvironmentVariable(named = "EASYSUBWAY_BENCHMARK", matches = "true")
class JourneyV3NationwideBenchmarkTest {

	private static final String CORPUS = "journey-benchmark/v1/nationwide-corpus.json";
	private static final ObjectMapper OUTPUT = new ObjectMapper();

	@Test
	@DisplayName("measures the deployment-provided ACTIVE_SERVING Journey V3 runtime")
	void measuresActiveServingRuntime() throws Exception {
		Map<String, String> environment = System.getenv();
		Corpus corpus = readCorpus();
		Config config = Config.from(environment);
		if (!corpus.sha256().equals(config.corpusSha256())) throw new IllegalArgumentException("benchmark corpus digest does not match the required runtime value");
		ThreadMXBean allocations = allocationBean();

		Phase<JourneyV3BenchmarkRuntimeAdapter.Loaded> load = measure(allocations,
			() -> JourneyV3BenchmarkRuntimeAdapter.load(environment));
		JourneyV3BenchmarkRuntimeAdapter.ExpectedIdentity tuple = JourneyV3BenchmarkRuntimeAdapter.expectedIdentity(environment);
		Phase<JourneyV3BenchmarkRuntimeAdapter.Verified> verify = measure(allocations,
			() -> JourneyV3BenchmarkRuntimeAdapter.verify(load.value(), tuple));
		Phase<JourneyV3BenchmarkRuntimeAdapter> compile = measure(allocations,
			() -> JourneyV3BenchmarkRuntimeAdapter.compile(verify.value()));
		JourneyV3BenchmarkRuntimeAdapter runtime = compile.value();

		List<RequestCase> requests = corpus.cases().stream().map(testCase -> new RequestCase(testCase,
			runtime.scheduledInstant(testCase.serviceDay(), testCase.departureLocalTime()))).toList();
		Phase<Sample> firstSearch = measure(allocations, () -> run(runtime, requests.getFirst(), Profile.STANDARD, 0,
			tuple.routeBundleSha256()));
		JourneyV3BenchmarkRuntimeAdapter.Counters warmStart = runtime.counters();
		warm(runtime, requests, config.warmupIterations(), tuple.routeBundleSha256());
		Map<Profile, WarmEvidence> warm = measureProfiles(runtime, requests, config, allocations, tuple.routeBundleSha256());
		Evidence evidence = new Evidence(tuple, corpus, new ColdEvidence(load.nanos(), load.allocatedBytes(),
			verify.nanos(), verify.allocatedBytes(), compile.nanos(), compile.allocatedBytes(), firstSearch.nanos(),
			firstSearch.allocatedBytes()), warm, warmStart, runtime.counters(), config);
		Contract.validate(evidence);
		writeEvidence(config.outputPath(), evidence);
	}

	private static Corpus readCorpus() throws Exception {
		try (InputStream stream = requireNonNull(JourneyV3NationwideBenchmarkTest.class.getClassLoader().getResourceAsStream(CORPUS))) {
			return Contract.parseCorpus(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
		}
	}

	private static void warm(JourneyV3BenchmarkRuntimeAdapter runtime, List<RequestCase> requests, int iterations,
		String routeBundleSha256) {
		for (Profile profile : Profile.values()) for (int iteration = 0; iteration < iterations; iteration++)
			for (RequestCase request : requests) run(runtime, request, profile, iteration, routeBundleSha256);
	}

	private static Map<Profile, WarmEvidence> measureProfiles(JourneyV3BenchmarkRuntimeAdapter runtime,
		List<RequestCase> requests, Config config, ThreadMXBean allocations, String routeBundleSha256) throws Exception {
		var output = new EnumMap<Profile, WarmEvidence>(Profile.class);
		for (Profile profile : Profile.values()) {
			var samples = new ArrayList<Sample>();
			long routes = 0, trips = 0, transfers = 0;
			for (int iteration = 0; iteration < config.measurementIterations(); iteration++) for (RequestCase request : requests) {
				int sequence = iteration + config.warmupIterations();
				Phase<Sample> measured = measure(allocations, () -> run(runtime, request, profile,
					sequence, routeBundleSha256));
				samples.add(measured.value().withMeasurement(measured.nanos(), measured.allocatedBytes()));
				long[] scan = runtime.lastScanMetrics();
				routes += scan[0]; trips += scan[1]; transfers += scan[2];
			}
			output.put(profile, new WarmEvidence(requests.stream().map(item -> item.testCase().id()).toList(),
				List.copyOf(samples), Percentiles.from(samples), new ScanMetrics(routes, trips, transfers)));
		}
		return Map.copyOf(output);
	}

	private static Sample run(JourneyV3BenchmarkRuntimeAdapter runtime, RequestCase request, Profile profile, int sequence,
		String routeBundleSha256) {
		JourneyExecutionResult result = runtime.search(new JourneyRequest(requestId(sequence), request.testCase().originStationId(),
			request.testCase().destinationStationId(), new JourneyRequest.Departure.Scheduled(request.scheduledInstant()),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.valueOf(profile.name()),
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 3, 1, () -> false));
		if (!(result instanceof JourneyExecutionResult.Success success)
			|| !success.sourceIdentity().routeBundleSha256().equals(routeBundleSha256)) {
			throw new IllegalStateException("benchmark request did not return Success for the active route bundle");
		}
		return new Sample(request.testCase().id(), profile.name(), sequence, 0, 0);
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
	record Config(int warmupIterations, int measurementIterations, String corpusSha256, Path outputPath) {
		static Config from(Map<String, String> environment) {
			return new Config(positive(environment, "EASYSUBWAY_BENCHMARK_WARMUP_ITERATIONS"),
				positive(environment, "EASYSUBWAY_BENCHMARK_MEASUREMENT_ITERATIONS"),
				requiredSha(environment, "EASYSUBWAY_BENCHMARK_CORPUS_SHA256"),
				Path.of(required(environment, "EASYSUBWAY_BENCHMARK_OUTPUT_PATH")));
		}
	}
	record Corpus(String version, String sha256, List<Case> cases) { }
	record Case(String id, String originStationId, String destinationStationId, String serviceDay, String departureLocalTime) { }
	record RequestCase(Case testCase, Instant scheduledInstant) { }
	record Sample(String caseId, String profile, int sequence, long nanos, long allocatedBytes) {
		Sample withMeasurement(long nanos, long allocatedBytes) { return new Sample(caseId, profile, sequence, nanos, allocatedBytes); }
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
		long compilationNanos, long compilationAllocatedBytes, long firstSearchNanos, long firstSearchAllocatedBytes) { }
	record WarmEvidence(List<String> inputCaseIds, List<Sample> samples, Percentiles percentiles, ScanMetrics scanMetrics) { }
	record Evidence(JourneyV3BenchmarkRuntimeAdapter.ExpectedIdentity tuple, Corpus corpus, ColdEvidence cold,
		Map<Profile, WarmEvidence> warm, JourneyV3BenchmarkRuntimeAdapter.Counters warmStartCounters,
		JourneyV3BenchmarkRuntimeAdapter.Counters counters, Config config) {
		Map<String, Object> asJson() { var result = new LinkedHashMap<String, Object>(); result.put("schemaVersion", 1); result.put("tuple", tuple); result.put("corpus", corpus);
			result.put("jvm", Map.of("javaVersion", System.getProperty("java.version"), "vmName", System.getProperty("java.vm.name")));
			result.put("walkingPaceMetersPerHour", Map.of(
				"SLOW", JourneyRequest.WalkingPace.SLOW.speedMetersPerHour(),
				"STANDARD", JourneyRequest.WalkingPace.STANDARD.speedMetersPerHour(),
				"FAST", JourneyRequest.WalkingPace.FAST.speedMetersPerHour()));
			result.put("config", Map.of("warmupIterations", config.warmupIterations(), "corpusSha256", config.corpusSha256(),
				"measurementIterations", config.measurementIterations(), "outputPath", config.outputPath().toString()));
			result.put("cold", cold); result.put("warm", warm); result.put("warmStartAdapterCounters", warmStartCounters); result.put("adapterCounters", counters); return result; }
	}

	static final class Contract {
		private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
		private static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");
		static Corpus parseCorpus(String json) { try { JsonNode root = JSON.readTree(requireNonNull(json));
			if (root == null || root.path("schemaVersion").asInt(-1) != 1 || !"v1".equals(root.path("corpusVersion").asText())) throw new IllegalArgumentException("corpus version is invalid");
			var cases = new ArrayList<Case>(); var ids = new java.util.HashSet<String>();
			for (JsonNode item : root.path("cases")) { Case value = new Case(text(item, "id"), text(item, "originStationId"), text(item, "destinationStationId"), text(item, "serviceDay"), text(item, "departureLocalTime"));
				try { LocalTime parsed = LocalTime.parse(value.departureLocalTime()); if (parsed.getSecond() != 0 || parsed.getNano() != 0 || value.departureLocalTime().length() != 5) throw new IllegalArgumentException(); }
				catch (RuntimeException exception) { throw new IllegalArgumentException("corpus entry is invalid", exception); }
				if (value.originStationId().equals(value.destinationStationId()) || !Set.of("WEEKDAY", "WEEKEND").contains(value.serviceDay()) || !value.departureLocalTime().matches("[0-2]\\d:[0-5]\\d") || !ids.add(value.id())) throw new IllegalArgumentException("corpus entry is invalid"); cases.add(value); }
			if (cases.isEmpty()) throw new IllegalArgumentException("corpus must not be empty"); return new Corpus("v1", sha(json.getBytes(StandardCharsets.UTF_8)), List.copyOf(cases));
		} catch (java.io.IOException exception) { throw new IllegalArgumentException("corpus is malformed", exception); } }
		static void validate(Evidence evidence) { requireNonNull(evidence); requireSha(evidence.tuple().descriptorSha256(), "descriptorSha256"); requireSha(evidence.tuple().receiptSha256(), "receiptSha256"); requireSha(evidence.tuple().routeBundleSha256(), "routeBundleSha256"); requireSha(evidence.corpus().sha256(), "corpusSha256"); requireSha(evidence.config().corpusSha256(), "corpusSha256"); if (!evidence.corpus().sha256().equals(evidence.config().corpusSha256())) throw new IllegalArgumentException("corpus digest is invalid");
			if (!evidence.asJson().keySet().equals(Set.of("schemaVersion", "tuple", "corpus", "jvm",
				"walkingPaceMetersPerHour", "config", "cold", "warm", "warmStartAdapterCounters",
				"adapterCounters"))) throw new IllegalArgumentException("benchmark output fields are incomplete");
			if (evidence.config().warmupIterations() < 1 || evidence.config().measurementIterations() < 1 || !evidence.warm().keySet().equals(Set.of(Profile.values()))) throw new IllegalArgumentException("benchmark configuration is incomplete");
			for (long value : List.of(evidence.cold().loadNanos(), evidence.cold().loadAllocatedBytes(), evidence.cold().verificationNanos(), evidence.cold().verificationAllocatedBytes(), evidence.cold().compilationNanos(), evidence.cold().compilationAllocatedBytes(), evidence.cold().firstSearchNanos(), evidence.cold().firstSearchAllocatedBytes())) if (value < 0) throw new IllegalArgumentException("cold evidence is invalid");
			List<String> ids = evidence.corpus().cases().stream().map(Case::id).toList(); int count = ids.size() * evidence.config().measurementIterations();
			for (var entry : evidence.warm().entrySet()) { WarmEvidence warm = entry.getValue();
				Map<String, Long> perCase = warm.samples().stream().collect(java.util.stream.Collectors.groupingBy(Sample::caseId, java.util.stream.Collectors.counting()));
				if (!warm.inputCaseIds().equals(ids) || warm.samples().size() != count || !perCase.keySet().equals(Set.copyOf(ids))
					|| perCase.values().stream().anyMatch(value -> value != evidence.config().measurementIterations())
					|| !warm.percentiles().equals(Percentiles.from(warm.samples())) || warm.samples().stream().anyMatch(sample -> !sample.profile().equals(entry.getKey().name()) || sample.nanos() < 0 || sample.allocatedBytes() < 0)) throw new IllegalArgumentException("warm matrix is invalid"); }
			var start = evidence.warmStartCounters(); var counters = evidence.counters(); int warmCalls = evidence.corpus().cases().size() * evidence.config().warmupIterations() * Profile.values().length; int measuredCalls = count * Profile.values().length;
			if (start.artifactReadCount() < 1 || start.artifactReadBytes() < 1 || counters.artifactReadCount() != start.artifactReadCount() || counters.artifactReadBytes() != start.artifactReadBytes() || counters.searchCalls() - start.searchCalls() != warmCalls + measuredCalls || counters.activeSnapshotLookups() - start.activeSnapshotLookups() != warmCalls + measuredCalls) throw new IllegalArgumentException("warm adapter counters are invalid");
			if (counters.providerCalls() != 0 || counters.cacheHits() != 0 || counters.staleArtifactUses() != 0 || counters.fallbackUses() != 0) throw new IllegalArgumentException("forbidden adapter path was used");
			for (WarmEvidence item : evidence.warm().values()) if (item.scanMetrics().expandedRoutes() < 0 || item.scanMetrics().expandedTrips() < 0 || item.scanMetrics().expandedTransfers() < 0) throw new IllegalArgumentException("scan metrics are invalid"); }
		private static String text(JsonNode node, String name) { String value = node.path(name).textValue(); if (value == null || value.isBlank()) throw new IllegalArgumentException("corpus " + name + " is required"); return value; }
		private static void requireSha(String value, String field) { if (value == null || !SHA.matcher(value).matches()) throw new IllegalArgumentException(field + " must be a SHA-256 digest"); }
		private static String sha(byte[] bytes) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); } }
	}
	private static String required(Map<String, String> environment, String name) { String value = environment.get(name); if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required"); return value; }
	private static String requiredSha(Map<String, String> environment, String name) { String value = required(environment, name); if (!Contract.SHA.matcher(value).matches()) throw new IllegalArgumentException(name + " must be a SHA-256 digest"); return value; }
	private static int positive(Map<String, String> environment, String name) { try { int value = Integer.parseInt(required(environment, name)); if (value < 1) throw new IllegalArgumentException(name + " must be positive"); return value; } catch (NumberFormatException exception) { throw new IllegalArgumentException(name + " must be an integer", exception); } }
	@FunctionalInterface interface CheckedSupplier<T> { T get() throws Exception; }
}

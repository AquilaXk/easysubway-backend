package com.easysubway.journey.bundle;

import com.easysubway.journey.application.JourneyApplicationService;
import com.easysubway.journey.application.ActiveJourneySnapshotPort;
import com.easysubway.journey.application.JourneyExecutionResult;
import com.easysubway.journey.application.JourneyRealtimePort;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.route.application.service.JourneyRaptorAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Test-only, fail-closed bridge for the #297 benchmark runner. */
public final class JourneyV3BenchmarkRuntimeAdapter {

	private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
		.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
		.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
	private final JourneyApplicationService journey;
	private final JourneyRaptorAdapter raptor;
	private final MutableCounters counters;
	private final Instant activeFrom;
	private final Instant freshUntil;

	private JourneyV3BenchmarkRuntimeAdapter(JourneyApplicationService journey, JourneyRaptorAdapter raptor,
		MutableCounters counters, Instant activeFrom, Instant freshUntil) {
		this.journey = journey;
		this.raptor = raptor;
		this.counters = counters;
		this.activeFrom = activeFrom;
		this.freshUntil = freshUntil;
	}

	public record ExpectedIdentity(String descriptorSha256, String receiptSha256, String routeBundleSha256,
		String deploymentRevision) { }
	public record Loaded(byte[] descriptorBytes, byte[] receiptBytes,
		List<RouteBundlePublicationObjectFetcher.FetchedObject> objects,
		long artifactReadCount, long artifactReadBytes, String activationRequestIdentity, String currentKeyId,
		String currentKeyPem, RouteBundlePublicationDescriptor descriptor) { }
	public record Verified(Loaded loaded, RouteBundlePublicationDescriptor descriptor,
		RouteBundleObjectAdmission.VerifiedPublicationObjectAdmission admission) { }
	public record Counters(long artifactReadCount, long artifactReadBytes, long activeSnapshotLookups, long searchCalls,
		long providerCalls, long cacheHits, long staleArtifactUses, long fallbackUses) { }

	public static ExpectedIdentity expectedIdentity(Map<String, String> environment) {
		return new ExpectedIdentity(required(environment, "EASYSUBWAY_JOURNEY_V3_DESCRIPTOR_SHA256"),
			required(environment, "EASYSUBWAY_JOURNEY_V3_RECEIPT_SHA256"),
			required(environment, "EASYSUBWAY_JOURNEY_V3_ROUTE_BUNDLE_SHA256"),
			required(environment, "EASYSUBWAY_JOURNEY_V3_DEPLOYMENT_REVISION"));
	}

	public static Loaded load(Map<String, String> environment, ExpectedIdentity expected) throws IOException {
		Path descriptorPath = Path.of(required(environment, "EASYSUBWAY_JOURNEY_V3_DESCRIPTOR_PATH"));
		Path receiptPath = Path.of(required(environment, "EASYSUBWAY_JOURNEY_V3_ACTIVE_RECEIPT_PATH"));
		String activationRequestIdentity = required(environment, "EASYSUBWAY_JOURNEY_V3_ACTIVATION_REQUEST_IDENTITY");
		byte[] descriptorBytes = Files.readAllBytes(descriptorPath);
		byte[] receiptBytes = Files.readAllBytes(receiptPath);
		RouteBundlePublicationDescriptor descriptor = RouteBundleConsumerHandoffParser.parsePublicationDescriptor(
			descriptorBytes, activationRequestIdentity);
		verifyExpectedIdentity(descriptor.descriptorSha256(), receiptBytes,
			descriptor.admissionEvidence().manifestSha256(), expected);
		verifyActiveReceipt(receiptBytes, descriptor.admissionEvidence().manifestSha256(), expected.deploymentRevision());
		var reads = new MutableCounters(2, descriptorBytes.length + receiptBytes.length);
		Path artifactRoot = Path.of(required(environment, "EASYSUBWAY_JOURNEY_V3_ARTIFACT_ROOT"))
			.toRealPath(LinkOption.NOFOLLOW_LINKS);
		var objects = new java.util.ArrayList<RouteBundlePublicationObjectFetcher.FetchedObject>();
		for (RouteBundlePublicationDescriptor.PublishedObject object : descriptor.objects()) {
			objects.add(readObject(artifactRoot, object.path(), object.objectKey(), reads));
		}
		return new Loaded(descriptorBytes, receiptBytes, List.copyOf(objects),
			reads.artifactReadCount, reads.artifactReadBytes, activationRequestIdentity,
			required(environment, "EASYSUBWAY_JOURNEY_V3_CURRENT_KEY_ID"),
			required(environment, "EASYSUBWAY_JOURNEY_V3_CURRENT_KEY_PEM"), descriptor);
	}

	public static Verified verify(Loaded loaded, ExpectedIdentity expected) throws IOException {
		RouteBundlePublicationDescriptor descriptor = loaded.descriptor();
		var currentKey = new RouteBundleCurrentKeyVerifier.CurrentKey(loaded.currentKeyId(), loaded.currentKeyPem());
		var objects = new RouteBundlePublicationObjectFetcher.FetchedPublicationObjects(
			descriptor.descriptorSha256(), descriptor.identity().keyId(), loaded.objects());
		var admission = RouteBundleObjectAdmission.admitPublicationDescriptor(
			loaded.descriptorBytes(), loaded.activationRequestIdentity(), objects, currentKey);
		return new Verified(loaded, descriptor, admission);
	}

	public static void verifyExpectedIdentity(String descriptorSha256, byte[] receiptBytes,
		String routeBundleSha256, ExpectedIdentity expected) {
		if (!descriptorSha256.equals(expected.descriptorSha256())) throw new IllegalArgumentException(
			"parsed descriptor self-digest does not match the active tuple");
		if (!sha(receiptBytes).equals(expected.receiptSha256())) throw new IllegalArgumentException(
			"raw activation receipt digest does not match the active tuple");
		if (!routeBundleSha256.equals(expected.routeBundleSha256())) throw new IllegalArgumentException(
			"descriptor manifest digest does not match the active tuple");
		if (!expected.deploymentRevision().matches("[a-f0-9]{40}")) throw new IllegalArgumentException(
			"expected deployment revision is invalid");
	}

	public static JourneyV3BenchmarkRuntimeAdapter compile(Verified verified) {
		Loaded loaded = verified.loaded();
		RouteBundlePublicationDescriptor descriptor = verified.descriptor();
		var counters = new MutableCounters(loaded.artifactReadCount(), loaded.artifactReadBytes());
		var admitted = verified.admission();
		var registry = new RouteBundleActivationRegistry(Clock.systemUTC());
		var candidate = new RouteBundleCandidateAssembler().assemble(admitted, 1, Instant.now());
		registry.stage(candidate, 0);
		registry.activate(candidate.admissionEvidence().manifestSha256(), 0);
		var raptor = new JourneyRaptorAdapter();
		ActiveJourneySnapshotPort snapshots = instant -> {
			counters.activeSnapshotLookups++;
			return new RouteBundleActiveJourneySnapshotAdapter(registry).requireActive(instant);
		};
		return new JourneyV3BenchmarkRuntimeAdapter(new JourneyApplicationService(
			snapshots, realtimeMustNotBeCalled(), raptor, Clock.systemUTC()),
			raptor, counters, candidate.identity().activeFromInstant(), candidate.identity().freshUntilInstant());
	}

	public JourneyExecutionResult search(JourneyRequest request) {
		counters.searchCalls++;
		return journey.execute(request);
	}

	public long[] lastScanMetrics() {
		try {
			var field = JourneyRaptorAdapter.class.getDeclaredField("planner");
			field.setAccessible(true);
			Object planner = field.get(raptor);
			var last = planner.getClass().getDeclaredMethod("lastScanMetrics");
			last.setAccessible(true);
			Object metrics = last.invoke(planner);
			return new long[] { metric(metrics, "expandedRoutes"), metric(metrics, "expandedTrips"), metric(metrics, "expandedTransfers") };
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException("Journey V3 RAPTOR scan metrics are unavailable", exception);
		}
	}

	public Counters counters() { return counters.snapshot(); }

	/** Selects a scheduled instant from the verified active bundle validity window. */
	public Instant scheduledInstant(String serviceDay, String localTime) {
		ZoneId zone = ZoneId.of("Asia/Seoul");
		LocalDate date = activeFrom.atZone(zone).toLocalDate();
		LocalTime time = LocalTime.parse(localTime);
		for (int day = 0; day < 370; day++, date = date.plusDays(1)) {
			boolean matches = "WEEKDAY".equals(serviceDay)
				? date.getDayOfWeek().getValue() <= 5 : date.getDayOfWeek().getValue() >= 6;
			Instant candidate = date.atTime(time).atZone(zone).toInstant();
			if (matches && !candidate.isBefore(activeFrom) && candidate.isBefore(freshUntil)) return candidate;
		}
		throw new IllegalStateException("active route bundle has no valid " + serviceDay + " benchmark instant");
	}

	private static long metric(Object metrics, String name) throws ReflectiveOperationException {
		var accessor = metrics.getClass().getDeclaredMethod(name);
		accessor.setAccessible(true);
		return ((Number) accessor.invoke(metrics)).longValue();
	}

	public static Path checkedArtifactObjectPath(Path root, String objectPath) {
		try {
			Path realRoot = root.toRealPath(LinkOption.NOFOLLOW_LINKS);
			Path path = realRoot.resolve(objectPath).normalize();
			if (!path.startsWith(realRoot)) throw new IllegalArgumentException("staged artifact object is unavailable");
			Path current = realRoot;
			for (Path part : realRoot.relativize(path)) {
				current = current.resolve(part);
				if (Files.isSymbolicLink(current)) throw new IllegalArgumentException("staged artifact object is unavailable");
			}
			if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
				|| !path.toRealPath(LinkOption.NOFOLLOW_LINKS).startsWith(realRoot)) {
				throw new IllegalArgumentException("staged artifact object is unavailable");
			}
			return path;
		} catch (IOException exception) { throw new IllegalArgumentException("staged artifact object is unavailable", exception); }
	}

	private static RouteBundlePublicationObjectFetcher.FetchedObject readObject(Path root, String objectPath,
		String objectKey, MutableCounters counters) {
		try {
			Path path = checkedArtifactObjectPath(root, objectPath);
			byte[] bytes = Files.readAllBytes(path);
			counters.artifactReadCount++;
			counters.artifactReadBytes += bytes.length;
			return new RouteBundlePublicationObjectFetcher.FetchedObject(objectPath, objectKey, bytes);
		} catch (IOException exception) { throw new IllegalArgumentException("staged artifact object cannot be read", exception); }
	}

	public static void verifyActiveReceipt(byte[] bytes, String routeBundleSha256, String expectedDeploymentRevision) throws IOException {
		JsonNode receipt = JSON.readTree(bytes);
		requireObject(receipt, Set.of("schemaVersion", "artifactKind", "orchestrator", "outcome", "operation",
			"releaseIdentity", "verification", "candidate", "activation", "mutationCounts", "rollbackAttemptCount", "fallbackZero",
			"bundleAcquisitionEvidenceDigest"), "receipt");
		if (!"PLATFORM_K3S_ACTIVATION_RECEIPT_V1".equals(text(receipt, "schemaVersion"))
			|| !"platform-k3s-activation-receipt".equals(text(receipt, "artifactKind"))
			|| !"K3S".equals(text(receipt, "orchestrator")) || !"ACTIVE_SERVING".equals(text(receipt, "outcome"))
			|| integer(receipt, "rollbackAttemptCount") != 0) throw new IllegalArgumentException("activation receipt is not an ACTIVE_SERVING success receipt");
		JsonNode identity = object(receipt, "releaseIdentity", Set.of("tupleSha256", "backendImageDigest", "backendConfigDigest",
			"journeyContractDigest", "serverRouteBundleDigest", "deploymentRevision", "environmentIdentity", "candidateGeneration", "trafficGeneration"));
		for (String field : List.of("tupleSha256", "backendImageDigest", "backendConfigDigest", "journeyContractDigest", "serverRouteBundleDigest")) digest(text(identity, field));
		if (!text(identity, "deploymentRevision").matches("[a-f0-9]{40}") || !text(identity, "deploymentRevision").equals(expectedDeploymentRevision)
			|| text(identity, "environmentIdentity").isBlank() || text(identity, "environmentIdentity").length() > 255
			|| integer(identity, "candidateGeneration") < 1 || integer(identity, "trafficGeneration") < 1) throw new IllegalArgumentException("receipt release identity is invalid");
		String computedTuple = "sha256:" + sha((String.join("\n", List.of(text(identity, "backendImageDigest"), text(identity, "backendConfigDigest"), text(identity, "journeyContractDigest"), text(identity, "serverRouteBundleDigest"), text(identity, "deploymentRevision"), text(identity, "environmentIdentity"))) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
		if (!computedTuple.equals(text(identity, "tupleSha256"))
			|| !("sha256:" + routeBundleSha256).equals(text(identity, "serverRouteBundleDigest"))) {
			throw new IllegalArgumentException("active receipt does not bind the descriptor identity");
		}
		digests(object(receipt, "operation", Set.of("operationId", "runUrl", "generatedAt")), "operationId");
		if (!text(receipt.path("operation"), "runUrl").matches("https://github\\.com/AquilaXk/easysubway-platform/actions/runs/[1-9][0-9]*")) throw new IllegalArgumentException("receipt operation is invalid");
		try { Instant.parse(text(receipt.path("operation"), "generatedAt")); } catch (RuntimeException exception) { throw new IllegalArgumentException("receipt operation is invalid", exception); }
		digests(object(receipt, "verification", Set.of("inputsEvidenceDigest", "runtimeEvidenceDigest")), "inputsEvidenceDigest", "runtimeEvidenceDigest");
		JsonNode candidate = object(receipt, "candidate", Set.of("deploymentName", "candidateEvidenceDigest", "canaryEvidenceDigest", "observationEvidenceDigest", "candidateAdmissionSha256", "activeReadinessEvidenceDigest"));
		if (text(candidate, "deploymentName").isBlank()) throw new IllegalArgumentException("receipt candidate is invalid"); digests(candidate, "candidateEvidenceDigest", "canaryEvidenceDigest", "observationEvidenceDigest", "candidateAdmissionSha256", "activeReadinessEvidenceDigest");
		JsonNode activation = object(receipt, "activation", Set.of("servicePreparation", "serviceCas", "endpoint", "nginx", "drain", "publicSmoke"));
		JsonNode preparation = object(activation, "servicePreparation", Set.of("serviceExisted", "activeServiceMutationCount", "evidenceDigest")); if (!preparation.path("serviceExisted").isBoolean() || integer(preparation, "activeServiceMutationCount") < 0) throw new IllegalArgumentException("receipt activation is invalid"); digests(preparation, "evidenceDigest");
		JsonNode cas = object(activation, "serviceCas", Set.of("previousResourceVersion", "committedResourceVersion", "selector", "evidenceDigest")); if (!text(cas, "previousResourceVersion").matches("[1-9][0-9]*") || !text(cas, "committedResourceVersion").matches("[1-9][0-9]*") || !cas.path("selector").isObject() || cas.path("selector").isEmpty()) throw new IllegalArgumentException("receipt service CAS is invalid"); digests(cas, "evidenceDigest");
		JsonNode endpoint = object(activation, "endpoint", Set.of("readyAddress", "nodePort", "tupleSha256", "evidenceDigest")); if (text(endpoint, "readyAddress").isBlank() || integer(endpoint, "nodePort") != 32080 || !text(identity, "tupleSha256").equals(text(endpoint, "tupleSha256"))) throw new IllegalArgumentException("receipt endpoint is invalid"); digests(endpoint, "tupleSha256", "evidenceDigest");
		JsonNode nginx = object(activation, "nginx", Set.of("targetPort", "nginxConfigSha256", "evidenceDigest")); if (integer(nginx, "targetPort") != 32080) throw new IllegalArgumentException("receipt nginx is invalid"); digests(nginx, "nginxConfigSha256", "evidenceDigest");
		JsonNode drain = object(activation, "drain", Set.of("signal", "stopGracePeriodSeconds", "oldWorkloadCount", "evidenceDigest")); if (!"SIGTERM".equals(text(drain, "signal")) || integer(drain, "stopGracePeriodSeconds") != 30 || integer(drain, "oldWorkloadCount") < 0) throw new IllegalArgumentException("receipt drain is invalid"); digests(drain, "evidenceDigest");
		JsonNode smoke = object(activation, "publicSmoke", Set.of("passed", "tupleSha256", "evidenceDigest")); if (!smoke.path("passed").asBoolean(false) || !text(identity, "tupleSha256").equals(text(smoke, "tupleSha256"))) throw new IllegalArgumentException("receipt public smoke is invalid"); digests(smoke, "tupleSha256", "evidenceDigest");
		JsonNode mutations = object(receipt, "mutationCounts", Set.of("activeService", "nginx", "oldWorkload")); for (String field : List.of("activeService", "nginx", "oldWorkload")) if (integer(mutations, field) < 0) throw new IllegalArgumentException("receipt mutations are invalid");
		JsonNode fallback = object(receipt, "fallbackZero", Set.of("legacyGraphSuccessCount", "localRouteInvocationCount", "staleJourneyServedCount", "alternateEndpointSuccessCount")); for (String field : List.of("legacyGraphSuccessCount", "localRouteInvocationCount", "staleJourneyServedCount", "alternateEndpointSuccessCount")) if (integer(fallback, field) != 0) throw new IllegalArgumentException("receipt fallback is invalid");
		digest(text(receipt, "bundleAcquisitionEvidenceDigest"));
	}

	private static final Pattern DIGEST = Pattern.compile("sha256:[a-f0-9]{64}");
	private static JsonNode object(JsonNode parent, String field, Set<String> keys) { JsonNode value = parent.path(field); requireObject(value, keys, field); return value; }
	private static void requireObject(JsonNode value, Set<String> keys, String name) { if (value == null || !value.isObject() || value.size() != keys.size() || !value.fieldNames().hasNext() && !keys.isEmpty() || !fieldSet(value).equals(keys)) throw new IllegalArgumentException(name + " is invalid"); }
	private static Set<String> fieldSet(JsonNode value) { var fields = new java.util.HashSet<String>(); value.fieldNames().forEachRemaining(fields::add); return fields; }
	private static String text(JsonNode node, String field) { String value = node.path(field).textValue(); if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required"); return value; }
	private static long integer(JsonNode node, String field) { JsonNode value = node.path(field); if (!value.isIntegralNumber() || !value.canConvertToLong()) throw new IllegalArgumentException(field + " must be an integer"); return value.longValue(); }
	private static void digests(JsonNode node, String... fields) { for (String field : fields) digest(text(node, field)); }
	private static void digest(String value) { if (!DIGEST.matcher(value).matches()) throw new IllegalArgumentException("receipt digest is invalid"); }

	private static JourneyRealtimePort realtimeMustNotBeCalled() {
		return (request, snapshot, instant) -> { throw new AssertionError("benchmark corpus must not call a provider"); };
	}

	private static String required(Map<String, String> environment, String name) {
		String value = environment.get(name);
		if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
		return value;
	}

	private static String sha(byte[] bytes) {
		try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
		catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
	}

	private static final class MutableCounters {
		long artifactReadCount; long artifactReadBytes; long activeSnapshotLookups; long searchCalls;
		MutableCounters() { }
		MutableCounters(long artifactReadCount, long artifactReadBytes) { this.artifactReadCount = artifactReadCount; this.artifactReadBytes = artifactReadBytes; }
		Counters snapshot() { return new Counters(artifactReadCount, artifactReadBytes, activeSnapshotLookups, searchCalls, 0, 0, 0, 0); }
	}
}

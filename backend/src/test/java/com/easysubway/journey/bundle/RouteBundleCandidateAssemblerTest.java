package com.easysubway.journey.bundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RouteBundleCandidateAssemblerTest {

	private static final String TOPOLOGY = "payload/topology.sqlite.zst";
	private static final String TIMETABLE = "payload/timetable.sqlite.zst";
	private static final String ACCESSIBILITY = "payload/accessibility.sqlite.zst";
	private static final String FARE = "payload/fare.sqlite.zst";
	private static final Instant ACTIVE_FROM = Instant.parse("2026-08-09T00:00:00Z");
	private static final Instant FRESH_UNTIL = Instant.parse("2026-08-10T00:00:00Z");
	private static final Instant VERIFIED_AT = Instant.parse("2026-08-09T01:00:00Z");

	@Test
	void assemblesOneCandidateFromTheExactAdmittedManifestAndPayloadBytes() {
		Fixture fixture = fixture();
		var calls = new AtomicInteger();
		var captured = new AtomicReference<RouteBundleSqliteRuntimeCompiler.Input>();
		var runtime = new TestRuntime();
		var assembler = new RouteBundleCandidateAssembler(input -> {
			calls.incrementAndGet();
			captured.set(input);
			return runtime;
		});

		var candidate = assembler.assemble(fixture.admission(), 7, VERIFIED_AT);

		assertThat(calls.get()).isEqualTo(1);
		assertThat(candidate.identity()).isEqualTo(fixture.identity());
		assertThat(candidate.admissionEvidence()).isEqualTo(fixture.handoff().admissionEvidence());
		assertThat(candidate.servingEvidence()).isEqualTo(RouteBundleServingEvidence.unobservable());
		assertThat(candidate.runtimeView()).isSameAs(runtime);
		assertThat(candidate.verifiedAt()).isEqualTo(VERIFIED_AT);
		assertThat(captured.get().routeBundleSha256()).isEqualTo(fixture.manifestSha256());
		assertThat(captured.get().generation()).isEqualTo(7);
		assertThat(captured.get().bundleId()).isEqualTo(fixture.identity().bundleId());
		assertThat(captured.get().releaseSequence()).isEqualTo(fixture.identity().releaseSequence());
		assertThat(captured.get().stationSetSha256()).isEqualTo(fixture.identity().stationSetSha256());
		assertThat(captured.get().admittedPayloadSha256s()).isEqualTo(fixture.payloadDigests());
		assertThat(captured.get().compressedPayloads()).containsOnlyKeys(
			TOPOLOGY, TIMETABLE, ACCESSIBILITY, FARE);
		fixture.payloads().forEach((path, bytes) ->
			assertThat(captured.get().compressedPayloads().get(path)).containsExactly(bytes));
	}

	@Test
	void assemblesOneCandidateFromTheVerifiedDescriptorV2Admission() {
		Fixture fixture = fixture();
		var calls = new AtomicInteger();
		var captured = new AtomicReference<RouteBundleSqliteRuntimeCompiler.Input>();
		var assembler = new RouteBundleCandidateAssembler(input -> {
			calls.incrementAndGet();
			captured.set(input);
			return new TestRuntime();
		});

		var candidate = assembler.assemble(v2Admission(fixture), 7, VERIFIED_AT);

		assertThat(calls.get()).isEqualTo(1);
		assertThat(candidate.identity()).isEqualTo(fixture.identity());
		assertThat(candidate.admissionEvidence()).isEqualTo(fixture.handoff().admissionEvidence());
		assertThat(candidate.servingEvidence()).isEqualTo(RouteBundleServingEvidence.observed(
			"d".repeat(64), "e".repeat(64)));
		assertThat(captured.get().routeBundleSha256()).isEqualTo(fixture.manifestSha256());
		assertThat(captured.get().generation()).isEqualTo(7);
		assertThat(captured.get().admittedPayloadSha256s()).isEqualTo(fixture.payloadDigests());
	}

	@Test
	void servingEvidenceRejectsMalformedObservedDigests() {
		for (var evidence : List.<java.util.function.Supplier<RouteBundleServingEvidence>>of(
			() -> RouteBundleServingEvidence.observed("A".repeat(64), "b".repeat(64)),
			() -> RouteBundleServingEvidence.observed("a".repeat(63), "b".repeat(64)),
			() -> RouteBundleServingEvidence.observed("a".repeat(64), "b".repeat(63)))) {
			assertThatThrownBy(evidence::get).isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void rejectsInvalidGenerationAndVerificationTimeBeforeCompilation() {
		Fixture fixture = fixture();
		var calls = new AtomicInteger();
		var assembler = new RouteBundleCandidateAssembler(input -> {
			calls.incrementAndGet();
			return new TestRuntime();
		});

		for (var invocation : List.<Runnable>of(
			() -> new RouteBundleCandidateAssembler().assemble(fixture.admission(), 0, VERIFIED_AT),
			() -> assembler.assemble(fixture.admission(), 0, VERIFIED_AT),
			() -> assembler.assemble(v2Admission(fixture), 0, VERIFIED_AT),
			() -> assembler.assemble(fixture.admission(), 1, ACTIVE_FROM.minusNanos(1)),
			() -> assembler.assemble(fixture.admission(), 1, FRESH_UNTIL))) {
			assertThatThrownBy(invocation::run).isInstanceOf(IllegalArgumentException.class);
		}
		assertThat(calls.get()).isZero();
	}

	@Test
	void rejectsManifestAdmissionAndPlatformIdentityMismatchBeforeCompilation() {
		Fixture fixture = fixture();
		var calls = new AtomicInteger();
		var assembler = new RouteBundleCandidateAssembler(input -> {
			calls.incrementAndGet();
			return new TestRuntime();
		});
		var mismatchedIdentity = identity("other-bundle", fixture.payloads());
		var mismatchedEvidence = new RouteBundleAdmissionEvidence(
			"f".repeat(64), "final-ref", "promotion-ref", "publication-ref", "activation-ref");
		var incompleteObjects = new LinkedHashMap<>(fixture.objects());
		incompleteObjects.remove(TOPOLOGY);

		for (var admission : List.of(
			admission(handoff(mismatchedIdentity, fixture.handoff().admissionEvidence(),
				"sha256:" + fixture.manifestSha256(), fixture.objects()), fixture.objects()),
			admission(handoff(fixture.identity(), mismatchedEvidence,
				"sha256:" + fixture.manifestSha256(), fixture.objects()), fixture.objects()),
			admission(handoff(fixture.identity(), fixture.handoff().admissionEvidence(),
				"sha256:" + "f".repeat(64), fixture.objects()), fixture.objects()),
			admission(handoff(fixture.identity(), fixture.handoff().admissionEvidence(),
				"sha256:" + fixture.manifestSha256(), incompleteObjects), fixture.objects()))) {
			assertThatThrownBy(() -> assembler.assemble(admission, 1, VERIFIED_AT))
				.isInstanceOf(IllegalArgumentException.class);
		}
		assertThat(calls.get()).isZero();
	}

	@Test
	void propagatesCompilerFailureWithoutCreatingACandidate() {
		Fixture fixture = fixture();
		var assembler = new RouteBundleCandidateAssembler(input -> {
			throw new IllegalArgumentException("compile failed");
		});

		assertThatThrownBy(() -> assembler.assemble(fixture.admission(), 1, VERIFIED_AT))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("compile failed");
	}

	private static Fixture fixture() {
		var payloads = payloads();
		var identity = identity("capital-v1", payloads);
		var manifest = manifest(identity);
		var objects = new LinkedHashMap<String, byte[]>();
		objects.put("compatibility.json", "compatibility".getBytes(StandardCharsets.UTF_8));
		objects.put("manifest.json", manifest);
		objects.put("manifest.signing-input.json", "signing-input".getBytes(StandardCharsets.UTF_8));
		objects.put(ACCESSIBILITY, payloads.get(ACCESSIBILITY));
		objects.put(FARE, payloads.get(FARE));
		objects.put(TIMETABLE, payloads.get(TIMETABLE));
		objects.put(TOPOLOGY, payloads.get(TOPOLOGY));
		objects.put("provenance.json", "provenance".getBytes(StandardCharsets.UTF_8));
		String manifestSha256 = sha(manifest);
		var evidence = new RouteBundleAdmissionEvidence(
			manifestSha256, "final-ref", "promotion-ref", "publication-ref", "activation-ref");
		var handoff = handoff(identity, evidence, "sha256:" + manifestSha256, objects);
		return new Fixture(
			identity,
			handoff,
			admission(handoff, objects),
			copy(objects),
			copy(payloads),
			payloadDigests(payloads),
			manifestSha256);
	}

	private static RouteBundleObjectAdmission.VerifiedObjectAdmission admission(
		RouteBundleConsumerHandoff handoff,
		Map<String, byte[]> objects) {
		var admission = mock(RouteBundleObjectAdmission.VerifiedObjectAdmission.class);
		var signature = mock(RouteBundleCurrentKeyVerifier.VerifiedSignature.class);
		when(admission.verifiedSignature()).thenReturn(signature);
		when(signature.handoff()).thenReturn(handoff);
		when(admission.objectBytes(anyString())).thenAnswer(invocation -> {
			byte[] bytes = objects.get(invocation.getArgument(0, String.class));
			if (bytes == null) throw new IllegalArgumentException("unknown admitted object path");
			return bytes.clone();
		});
		return admission;
	}

	private static RouteBundleObjectAdmission.VerifiedPublicationObjectAdmission v2Admission(Fixture fixture) {
		var admission = mock(RouteBundleObjectAdmission.VerifiedPublicationObjectAdmission.class);
		var signature = mock(RouteBundleCurrentKeyVerifier.VerifiedPublicationDescriptorSignature.class);
		var descriptor = mock(RouteBundlePublicationDescriptor.class);
		when(admission.verifiedDescriptorSignature()).thenReturn(signature);
		when(signature.descriptor()).thenReturn(descriptor);
		when(descriptor.identity()).thenReturn(fixture.identity());
		when(descriptor.admissionEvidence()).thenReturn(fixture.handoff().admissionEvidence());
		when(descriptor.descriptorSha256()).thenReturn("d".repeat(64));
		var release = mock(RouteBundlePublicationDescriptor.ReleaseEvidence.class);
		when(descriptor.release()).thenReturn(release);
		when(release.publicationReceiptSha256()).thenReturn("e".repeat(64));
		when(descriptor.objects()).thenReturn(fixture.handoff().objects().stream()
			.map(object -> new RouteBundlePublicationDescriptor.PublishedObject(
				object.path(), object.objectKey(), object.sizeBytes(), object.sha256()))
			.toList());
		when(admission.objectBytes(anyString())).thenAnswer(invocation -> {
			byte[] bytes = fixture.objects().get(invocation.getArgument(0, String.class));
			if (bytes == null) throw new IllegalArgumentException("unknown admitted object path");
			return bytes.clone();
		});
		return admission;
	}

	private static RouteBundleConsumerHandoff handoff(
		RouteBundleIdentity identity,
		RouteBundleAdmissionEvidence evidence,
		String platformDigest,
		Map<String, byte[]> objects) {
		var publishedObjects = new ArrayList<RouteBundleConsumerHandoff.PublishedObject>();
		objects.forEach((path, bytes) -> publishedObjects.add(
			new RouteBundleConsumerHandoff.PublishedObject(path, "objects/" + path, bytes.length, sha(bytes))));
		return new RouteBundleConsumerHandoff(
			"a".repeat(40),
			identity,
			"b".repeat(64),
			evidence,
			new RouteBundleConsumerHandoff.PublicationLocator("https://example.invalid", "objects/"),
			publishedObjects,
			"c".repeat(64),
			new RouteBundleConsumerHandoff.ReleaseEvidence(
				"PASS", "d".repeat(64), "e".repeat(64), "f".repeat(64), "0".repeat(64), "1".repeat(64)),
			platformDigest,
			"2".repeat(64));
	}

	private static RouteBundleIdentity identity(String bundleId, Map<String, byte[]> payloads) {
		return new RouteBundleIdentity(
			1,
			"server-route-bundle",
			bundleId,
			1,
			"0".repeat(64),
			inventorySha(payloads),
			sha(payloads.get(TOPOLOGY)),
			sha(payloads.get(TIMETABLE)),
			sha(payloads.get(ACCESSIBILITY)),
			sha(payloads.get(FARE)),
			"1".repeat(64),
			"2".repeat(64),
			"Asia/Seoul",
			"2026-08-09T09:00:00.000+09:00",
			"2026-08-10T09:00:00.000+09:00",
			new RouteBundleIdentity.SchemaCompatibility(3, 3),
			"route-bundle-key",
			new RouteBundleIdentity.Signature("rsa-sha256-server-route-bundle-v1", "AQID"));
	}

	private static byte[] manifest(RouteBundleIdentity identity) {
		return ("{\"manifestVersion\":1,\"artifactKind\":\"server-route-bundle\",\"bundleId\":\"" + identity.bundleId()
			+ "\",\"releaseSequence\":1,\"stationSetSha256\":\"" + identity.stationSetSha256()
			+ "\",\"payloadSha256\":\"" + identity.payloadSha256()
			+ "\",\"topologySha256\":\"" + identity.topologySha256()
			+ "\",\"timetableSha256\":\"" + identity.timetableSha256()
			+ "\",\"accessibilitySha256\":\"" + identity.accessibilitySha256()
			+ "\",\"fareSha256\":\"" + identity.fareSha256()
			+ "\",\"provenanceSha256\":\"" + identity.provenanceSha256()
			+ "\",\"compatibilitySha256\":\"" + identity.compatibilitySha256()
			+ "\",\"serviceTimezone\":\"Asia/Seoul\",\"activeFrom\":\"" + identity.activeFrom()
			+ "\",\"freshUntil\":\"" + identity.freshUntil()
			+ "\",\"schemaCompatibility\":{\"backendMin\":3,\"backendMax\":3},\"keyId\":\""
			+ identity.keyId() + "\",\"signature\":{\"algorithm\":\"" + identity.signature().algorithm()
			+ "\",\"value\":\"" + identity.signature().value() + "\"}}")
			.getBytes(StandardCharsets.UTF_8);
	}

	private static Map<String, byte[]> payloads() {
		var payloads = new LinkedHashMap<String, byte[]>();
		payloads.put(ACCESSIBILITY, "accessibility".getBytes(StandardCharsets.UTF_8));
		payloads.put(FARE, "fare".getBytes(StandardCharsets.UTF_8));
		payloads.put(TIMETABLE, "timetable".getBytes(StandardCharsets.UTF_8));
		payloads.put(TOPOLOGY, "topology".getBytes(StandardCharsets.UTF_8));
		return payloads;
	}

	private static Map<String, String> payloadDigests(Map<String, byte[]> payloads) {
		var digests = new LinkedHashMap<String, String>();
		payloads.forEach((path, bytes) -> digests.put(path, sha(bytes)));
		return Map.copyOf(digests);
	}

	private static String inventorySha(Map<String, byte[]> payloads) {
		String inventory = "[" + payloads.entrySet().stream()
			.map(entry -> "{\"path\":\"" + entry.getKey() + "\",\"sha256\":\"" + sha(entry.getValue())
				+ "\",\"sizeBytes\":" + entry.getValue().length + "}")
			.reduce((left, right) -> left + "," + right)
			.orElseThrow() + "]";
		return sha(inventory.getBytes(StandardCharsets.UTF_8));
	}

	private static Map<String, byte[]> copy(Map<String, byte[]> source) {
		var copy = new LinkedHashMap<String, byte[]>();
		source.forEach((path, bytes) -> copy.put(path, bytes.clone()));
		return Map.copyOf(copy);
	}

	private static String sha(byte[] bytes) {
		try {
			return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException exception) {
			throw new AssertionError(exception);
		}
	}

	private record Fixture(
		RouteBundleIdentity identity,
		RouteBundleConsumerHandoff handoff,
		RouteBundleObjectAdmission.VerifiedObjectAdmission admission,
		Map<String, byte[]> objects,
		Map<String, byte[]> payloads,
		Map<String, String> payloadDigests,
		String manifestSha256) {
	}

	private record TestRuntime() implements RouteBundleRuntimeView {
	}
}

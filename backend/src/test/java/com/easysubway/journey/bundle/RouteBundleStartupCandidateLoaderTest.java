package com.easysubway.journey.bundle;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("Route bundle startup candidate loader")
class RouteBundleStartupCandidateLoaderTest {

	private static final byte[] DESCRIPTOR = "{}".getBytes(StandardCharsets.UTF_8);
	private static final String ACTIVATION_ID = "sha256:" + "e".repeat(64);
	private static final Instant NOW = Instant.parse("2026-08-13T00:00:00Z");
	private static final String PUBLIC_KEY_PEM = """
		-----BEGIN PUBLIC KEY-----
		MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAtfRwZiXfeTubXwMUsnZ5
		e1exey2YoolJVU5LsmAaOuF/3umllVeK37fLxxRZdqLd6mwvbDKPZJv1mDklRtjK
		tMJAwfZ69oH3dTD/CSYtBN2mO/KPet6Ui4gLZua4MZy5HqMdNCVDj6Z4QwQptdR6
		AXqhwjj/fBFQCc/ONmWGCoZ76FGlxCbpbobhaJ/gWzjwAE8M20jalUewh9Yh/xHd
		hmc5+ufKoZ/OFwOGlyLP1N06k4yxQa49jJTM30w7N8KyyyBRXS1Sz2Ubmwf8EFZA
		FdCGzzwpSjEVrLth3kGrx8XgpddzBqIRmSH3s+WqpN+mXPbp2EYhaVlc0oHwSb5X
		sQIDAQAB
		-----END PUBLIC KEY-----""";

	@Test
	@DisplayName("exact v2 chain을 generation 1 candidate stage까지 한 번 실행한다")
	void fetchesAdmitsAssemblesAndStagesOneColdCandidate() {
		var properties = properties();
		var fetcher = mock(RouteBundlePublicationObjectFetcher.class);
		var fetched = mock(RouteBundlePublicationObjectFetcher.FetchedPublicationObjects.class);
		var admissionFunction = mock(RouteBundleStartupCandidateLoader.PublicationAdmission.class);
		var admission = mock(RouteBundleObjectAdmission.VerifiedPublicationObjectAdmission.class);
		var assembler = mock(RouteBundleCandidateAssembler.class);
		var candidate = mock(VerifiedRouteBundleCandidate.class);
		var registry = mock(RouteBundleActivationRegistry.class);
		var snapshot = mock(RouteBundleActivationRegistry.CandidateSnapshot.class);
		when(fetcher.fetch(DESCRIPTOR, ACTIVATION_ID)).thenReturn(fetched);
		when(admissionFunction.admit(any(), anyString(), any(), any())).thenReturn(admission);
		when(assembler.assemble(admission, 1, NOW)).thenReturn(candidate);
		when(registry.stage(candidate, 0)).thenReturn(snapshot);
		var loader = new RouteBundleStartupCandidateLoader(
			properties, fetcher, admissionFunction, assembler, registry, Clock.fixed(NOW, ZoneOffset.UTC));

		assertSame(snapshot, loader.loadAndStage());

		var descriptor = ArgumentCaptor.forClass(byte[].class);
		var currentKey = ArgumentCaptor.forClass(RouteBundleCurrentKeyVerifier.CurrentKey.class);
		verify(admissionFunction).admit(
			descriptor.capture(),
			org.mockito.ArgumentMatchers.eq(ACTIVATION_ID),
			org.mockito.ArgumentMatchers.same(fetched),
			currentKey.capture());
		org.junit.jupiter.api.Assertions.assertArrayEquals(DESCRIPTOR, descriptor.getValue());
		org.junit.jupiter.api.Assertions.assertEquals("launch-2026", currentKey.getValue().keyId());
		org.junit.jupiter.api.Assertions.assertEquals(PUBLIC_KEY_PEM, currentKey.getValue().publicKeyPem());
		verify(registry, never()).activate(anyString(), anyLong());

		var order = inOrder(fetcher, admissionFunction, assembler, registry);
		order.verify(fetcher).fetch(DESCRIPTOR, ACTIVATION_ID);
		order.verify(admissionFunction).admit(any(), anyString(), any(), any());
		order.verify(assembler).assemble(admission, 1, NOW);
		order.verify(registry).stage(candidate, 0);
	}

	@Test
	@DisplayName("actual cold registry에는 generation 1 candidate만 남고 active는 unavailable이다")
	void stagesOnlyOneCandidateInActualColdRegistry() {
		var fetched = mock(RouteBundlePublicationObjectFetcher.FetchedPublicationObjects.class);
		var verified = mock(RouteBundleObjectAdmission.VerifiedPublicationObjectAdmission.class);
		var identity = mock(RouteBundleIdentity.class);
		when(identity.activeFromInstant()).thenReturn(NOW.minusSeconds(60));
		when(identity.freshUntilInstant()).thenReturn(NOW.plusSeconds(600));
		var evidence = mock(RouteBundleAdmissionEvidence.class);
		var runtime = mock(RouteBundleRuntimeView.class);
		var candidate = new VerifiedRouteBundleCandidate(
			identity, evidence, RouteBundleServingEvidence.unobservable(), runtime, NOW);
		var assembler = mock(RouteBundleCandidateAssembler.class);
		when(assembler.assemble(verified, 1, NOW)).thenReturn(candidate);
		var registry = new RouteBundleActivationRegistry(Clock.fixed(NOW, ZoneOffset.UTC));

		var snapshot = loader(
			successfulFetcher(fetched), successfulAdmission(verified), assembler, registry).loadAndStage();

		assertSame(identity, snapshot.identity());
		org.junit.jupiter.api.Assertions.assertEquals(1, snapshot.generation());
		org.junit.jupiter.api.Assertions.assertEquals(1, registry.candidateSnapshot().generation());
		var unavailable = assertThrows(RouteBundleActivationException.class, registry::activeSnapshot);
		org.junit.jupiter.api.Assertions.assertEquals(
			RouteBundleActivationException.Reason.BUNDLE_UNAVAILABLE, unavailable.reason());
	}

	@Test
	@DisplayName("fetch 실패는 admission·assemble·stage 없이 전파된다")
	void fetchFailureStopsBeforeAdmission() {
		var fetcher = mock(RouteBundlePublicationObjectFetcher.class);
		var admission = mock(RouteBundleStartupCandidateLoader.PublicationAdmission.class);
		var assembler = mock(RouteBundleCandidateAssembler.class);
		var registry = mock(RouteBundleActivationRegistry.class);
		var failure = new IllegalStateException("fetch failed");
		when(fetcher.fetch(DESCRIPTOR, ACTIVATION_ID)).thenThrow(failure);

		var loader = loader(fetcher, admission, assembler, registry);

		assertSame(failure, assertThrows(IllegalStateException.class, loader::loadAndStage));
		verifyNoInteractions(admission, assembler, registry);
	}

	@Test
	@DisplayName("admission·assemble·stage 실패는 later call과 activation 없이 전파된다")
	void downstreamFailuresStopWithoutActivation() {
		var fetched = mock(RouteBundlePublicationObjectFetcher.FetchedPublicationObjects.class);
		var verified = mock(RouteBundleObjectAdmission.VerifiedPublicationObjectAdmission.class);
		var candidate = mock(VerifiedRouteBundleCandidate.class);

		var admissionFailure = new IllegalStateException("admission failed");
		var admissionFetcher = successfulFetcher(fetched);
		var failingAdmission = mock(RouteBundleStartupCandidateLoader.PublicationAdmission.class);
		var admissionAssembler = mock(RouteBundleCandidateAssembler.class);
		var admissionRegistry = mock(RouteBundleActivationRegistry.class);
		when(failingAdmission.admit(any(), anyString(), any(), any())).thenThrow(admissionFailure);
		assertSame(admissionFailure, assertThrows(IllegalStateException.class,
			loader(admissionFetcher, failingAdmission, admissionAssembler, admissionRegistry)::loadAndStage));
		verifyNoInteractions(admissionAssembler, admissionRegistry);

		var assembleFailure = new IllegalStateException("assemble failed");
		var assembleFetcher = successfulFetcher(fetched);
		var successfulAdmission = successfulAdmission(verified);
		var failingAssembler = mock(RouteBundleCandidateAssembler.class);
		var assembleRegistry = mock(RouteBundleActivationRegistry.class);
		when(failingAssembler.assemble(verified, 1, NOW)).thenThrow(assembleFailure);
		assertSame(assembleFailure, assertThrows(IllegalStateException.class,
			loader(assembleFetcher, successfulAdmission, failingAssembler, assembleRegistry)::loadAndStage));
		verifyNoInteractions(assembleRegistry);

		var stageFailure = new IllegalStateException("stage failed");
		var stageFetcher = successfulFetcher(fetched);
		var stageAdmission = successfulAdmission(verified);
		var successfulAssembler = mock(RouteBundleCandidateAssembler.class);
		var failingRegistry = mock(RouteBundleActivationRegistry.class);
		when(successfulAssembler.assemble(verified, 1, NOW)).thenReturn(candidate);
		when(failingRegistry.stage(candidate, 0)).thenThrow(stageFailure);
		assertSame(stageFailure, assertThrows(IllegalStateException.class,
			loader(stageFetcher, stageAdmission, successfulAssembler, failingRegistry)::loadAndStage));
		verify(failingRegistry, never()).activate(anyString(), anyLong());
	}

	private static RouteBundleStartupCandidateLoader loader(
		RouteBundlePublicationObjectFetcher fetcher,
		RouteBundleStartupCandidateLoader.PublicationAdmission admission,
		RouteBundleCandidateAssembler assembler,
		RouteBundleActivationRegistry registry) {
		return new RouteBundleStartupCandidateLoader(
			properties(), fetcher, admission, assembler, registry, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private static RouteBundlePublicationObjectFetcher successfulFetcher(
		RouteBundlePublicationObjectFetcher.FetchedPublicationObjects fetched) {
		var fetcher = mock(RouteBundlePublicationObjectFetcher.class);
		when(fetcher.fetch(DESCRIPTOR, ACTIVATION_ID)).thenReturn(fetched);
		return fetcher;
	}

	private static RouteBundleStartupCandidateLoader.PublicationAdmission successfulAdmission(
		RouteBundleObjectAdmission.VerifiedPublicationObjectAdmission verified) {
		var admission = mock(RouteBundleStartupCandidateLoader.PublicationAdmission.class);
		when(admission.admit(any(), anyString(), any(), any())).thenReturn(verified);
		return admission;
	}

	private static RouteBundleStartupProperties properties() {
		return new RouteBundleStartupProperties(
			Base64.getEncoder().encodeToString(DESCRIPTOR),
			ACTIVATION_ID,
			"https://objects.example.com",
			"launch-2026",
			PUBLIC_KEY_PEM);
	}
}

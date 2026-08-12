package com.easysubway.journey.bundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Route bundle startup production composition")
class RouteBundleStartupConfigurationTest {

	@Test
	@DisplayName("release startup bean은 trusted fetcher·assembler·loader를 exact 조립한다")
	void composesProductionStartupChain() {
		var configuration = new RouteBundleStartupConfiguration();
		var properties = properties("https://objects.example.com");
		var fetcher = configuration.routeBundlePublicationObjectFetcher(properties);
		var assembler = configuration.routeBundleCandidateAssembler();
		var registry = mock(RouteBundleActivationRegistry.class);

		assertNotNull(fetcher);
		assertNotNull(assembler);
		assertNotNull(configuration.routeBundleStartupCandidateLoader(
			properties, fetcher, assembler, registry));
	}

	@Test
	@DisplayName("invalid trusted raw origin은 startup bean 생성에서 fail closed한다")
	void invalidTrustedOriginFailsClosed() {
		var configuration = new RouteBundleStartupConfiguration();

		var failure = assertThrows(
			RouteBundlePublicationObjectFetcher.AcquisitionException.class,
			() -> configuration.routeBundlePublicationObjectFetcher(properties("http://objects.example.com")));

		assertEquals(RouteBundlePublicationObjectFetcher.Reason.VERIFIED_DESCRIPTOR_INVALID, failure.reason());
	}

	@Test
	@DisplayName("singleton startup hook은 loader를 한 번 호출하고 실패를 그대로 전파한다")
	void startupHookInvokesOnceAndPropagatesFailure() {
		var configuration = new RouteBundleStartupConfiguration();
		var loader = mock(RouteBundleStartupCandidateLoader.class);
		var snapshot = mock(RouteBundleActivationRegistry.CandidateSnapshot.class);
		when(loader.loadAndStage()).thenReturn(snapshot);

		configuration.routeBundleStartupCandidateStager(loader).afterSingletonsInstantiated();

		verify(loader).loadAndStage();

		var failure = new IllegalStateException("startup failed");
		var failingLoader = mock(RouteBundleStartupCandidateLoader.class);
		when(failingLoader.loadAndStage()).thenThrow(failure);
		assertSame(failure, assertThrows(
			IllegalStateException.class,
			configuration.routeBundleStartupCandidateStager(failingLoader)::afterSingletonsInstantiated));
	}

	private static RouteBundleStartupProperties properties(String trustedOrigin) {
		return new RouteBundleStartupProperties(
			Base64.getEncoder().encodeToString("{}".getBytes(StandardCharsets.UTF_8)),
			"sha256:" + "e".repeat(64),
			trustedOrigin,
			"launch-2026",
			"synthetic-public-key");
	}
}

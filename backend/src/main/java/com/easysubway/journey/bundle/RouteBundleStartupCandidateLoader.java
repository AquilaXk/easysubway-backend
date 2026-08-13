package com.easysubway.journey.bundle;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** Loads one exact publication into the cold process-local candidate slot during startup. */
public final class RouteBundleStartupCandidateLoader {

	private static final long COLD_CANDIDATE_GENERATION = 1;
	private static final long EMPTY_REGISTRY_GENERATION = 0;

	private final RouteBundleStartupProperties properties;
	private final RouteBundlePublicationObjectFetcher fetcher;
	private final PublicationAdmission admission;
	private final RouteBundleCandidateAssembler assembler;
	private final RouteBundleActivationRegistry registry;
	private final Clock clock;

	RouteBundleStartupCandidateLoader(
		RouteBundleStartupProperties properties,
		RouteBundlePublicationObjectFetcher fetcher,
		PublicationAdmission admission,
		RouteBundleCandidateAssembler assembler,
		RouteBundleActivationRegistry registry,
		Clock clock) {
		this.properties = Objects.requireNonNull(properties, "properties");
		this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
		this.admission = Objects.requireNonNull(admission, "admission");
		this.assembler = Objects.requireNonNull(assembler, "assembler");
		this.registry = Objects.requireNonNull(registry, "registry");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	public RouteBundleActivationRegistry.CandidateSnapshot loadAndStage() {
		byte[] descriptorBytes = properties.descriptorBytes();
		String activationRequestIdentity = properties.activationRequestIdentity();
		var fetched = fetcher.fetch(descriptorBytes.clone(), activationRequestIdentity);
		var admitted = admission.admit(
			descriptorBytes.clone(),
			activationRequestIdentity,
			fetched,
			new RouteBundleCurrentKeyVerifier.CurrentKey(
				properties.currentKeyId(), properties.currentPublicKeyPem()));
		Instant verifiedAt = clock.instant();
		var candidate = assembler.assemble(admitted, COLD_CANDIDATE_GENERATION, verifiedAt);
		return registry.stage(candidate, EMPTY_REGISTRY_GENERATION);
	}

	@FunctionalInterface
	interface PublicationAdmission {
		RouteBundleObjectAdmission.VerifiedPublicationObjectAdmission admit(
			byte[] descriptorBytes,
			String activationRequestIdentity,
			RouteBundlePublicationObjectFetcher.FetchedPublicationObjects fetched,
			RouteBundleCurrentKeyVerifier.CurrentKey currentKey);
	}
}

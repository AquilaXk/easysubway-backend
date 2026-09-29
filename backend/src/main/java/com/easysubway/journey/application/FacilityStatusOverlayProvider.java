package com.easysubway.journey.application;

import com.easysubway.route.application.service.RaptorRouteBundleRuntimeView;
import com.easysubway.journey.bundle.RouteBundleActivationRegistry;
import com.easysubway.journey.bundle.TransitionFacilityRequirements;
import com.easysubway.transit.application.port.out.LoadTransitMasterPort;
import com.easysubway.transit.domain.AccessibilityFacility;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Periodically caches facility availability and computes blocked transitions
 * based on active route bundle requirements.
 */
public class FacilityStatusOverlayProvider implements FacilityAvailabilityPort {

	private static final Logger log = LoggerFactory.getLogger(FacilityStatusOverlayProvider.class);

	private final Supplier<Map<String, AccessibilityFacilityStatus>> statusSupplier;
	private final Supplier<TransitionFacilityRequirements> requirementsSupplier;
	private final Clock clock;
	private volatile FacilityAvailabilityView cachedView = FacilityAvailabilityView.unavailable();
	private volatile Instant lastSuccessfulUpdate = null;
	private volatile int blockedTransitionCount = 0;

	public FacilityStatusOverlayProvider(
		Supplier<Map<String, AccessibilityFacilityStatus>> statusSupplier,
		Supplier<TransitionFacilityRequirements> requirementsSupplier,
		Clock clock,
		MeterRegistry meterRegistry
	) {
		this.statusSupplier = Objects.requireNonNull(statusSupplier, "statusSupplier");
		this.requirementsSupplier = requirementsSupplier != null ? requirementsSupplier : TransitionFacilityRequirements::missing;
		this.clock = Objects.requireNonNull(clock, "clock");
		MeterRegistry registry = meterRegistry != null ? meterRegistry : new SimpleMeterRegistry();

		Gauge.builder("easysubway.journey.facility-status.blocked-transitions", this,
				provider -> (double) provider.blockedTransitionCount())
			.description("Count of blocked transitions computed from facility status")
			.register(registry);

		Gauge.builder("easysubway.journey.facility-status.seconds-since-last-update", this, provider -> {
				Instant last = provider.lastSuccessfulUpdate();
				if (last == null) {
					return Double.NaN;
				}
				return (double) Math.max(0, Duration.between(last, provider.clock.instant()).toSeconds());
			})
			.description("Seconds since the last successful facility status update")
			.register(registry);
	}

	public FacilityStatusOverlayProvider(
		LoadTransitMasterPort loadTransitMasterPort,
		RouteBundleActivationRegistry registry,
		Clock clock,
		MeterRegistry meterRegistry
	) {
		this(
			() -> {
				if (loadTransitMasterPort == null) {
					return Map.of();
				}
				var facilities = loadTransitMasterPort.loadAccessibilityFacilities();
				var statuses = new LinkedHashMap<String, AccessibilityFacilityStatus>();
				for (AccessibilityFacility f : facilities) {
					statuses.put(f.id(), f.status());
				}
				return Map.copyOf(statuses);
			},
			() -> {
				if (registry == null) {
					return TransitionFacilityRequirements.missing();
				}
				try {
					var active = registry.activeSnapshot();
					if (active != null && active.runtimeView() instanceof RaptorRouteBundleRuntimeView raptorView) {
						return raptorView.facilityRequirements();
					}
				} catch (Exception ignored) {
					// Bundle might not be active yet
				}
				return TransitionFacilityRequirements.missing();
			},
			clock,
			meterRegistry
		);
	}

	@Scheduled(fixedDelayString = "${easysubway.journey.facility-status.refresh-interval-ms:60000}")
	public synchronized void refresh() {
		try {
			Map<String, AccessibilityFacilityStatus> statuses = statusSupplier.get();
			TransitionFacilityRequirements requirements = requirementsSupplier.get();
			Set<String> blockedTransitions = BlockedTransitionEvaluator.evaluateBlockedTransitions(requirements, statuses);
			Instant now = clock.instant();
			this.lastSuccessfulUpdate = now;
			this.cachedView = FacilityAvailabilityView.blocked(now, blockedTransitions);
			this.blockedTransitionCount = blockedTransitions.size();
		} catch (Exception exception) {
			log.warn("Failed to refresh facility statuses: {}", exception.getMessage());
			// ObservedAt remains the last successful time; previous cache is preserved.
		}
	}

	@Override
	public FacilityAvailabilityView currentView() {
		return cachedView;
	}

	public Instant lastSuccessfulUpdate() {
		return lastSuccessfulUpdate;
	}

	public int blockedTransitionCount() {
		return blockedTransitionCount;
	}
}

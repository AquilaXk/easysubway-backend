package com.easysubway.route.application.service;

import com.easysubway.journey.application.JourneyRaptorRealtimeView;
import com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableRealtimeUpdates;
import java.util.Objects;

public final class RaptorRealtimeRuntimeView implements JourneyRaptorRealtimeView {

	private final String identity;
	private final RaptorRouteBundleRuntimeView routeRuntimeView;
	private final RouteTimetableRaptorPlanner.RealtimeOverlay realtimeOverlay;

	private RaptorRealtimeRuntimeView(
		String identity,
		RaptorRouteBundleRuntimeView routeRuntimeView,
		RouteTimetableRaptorPlanner.RealtimeOverlay realtimeOverlay
	) {
		this.identity = requireText(identity, "identity");
		this.routeRuntimeView = Objects.requireNonNull(routeRuntimeView, "routeRuntimeView");
		this.realtimeOverlay = Objects.requireNonNull(realtimeOverlay, "realtimeOverlay");
	}

	public static RaptorRealtimeRuntimeView compile(
		String identity,
		RaptorRouteBundleRuntimeView routeRuntimeView,
		TimetableRealtimeUpdates updates
	) {
		String requiredIdentity = requireText(identity, "identity");
		routeRuntimeView = Objects.requireNonNull(routeRuntimeView, "routeRuntimeView");
		updates = Objects.requireNonNull(updates, "updates");
		if (!updates.available() || updates.updates().stream()
			.anyMatch(update -> !requiredIdentity.equals(update.providerSnapshotId()))) {
			throw new IllegalArgumentException("realtime updates do not match runtime identity");
		}
		var overlay = new RouteTimetableRaptorPlanner().compileRealtimeOverlay(
			routeRuntimeView.compiledTimetable(), updates);
		if (!overlay.available() || overlay.isEmpty()) {
			throw new IllegalArgumentException("realtime overlay must contain valid updates");
		}
		return new RaptorRealtimeRuntimeView(requiredIdentity, routeRuntimeView, overlay);
	}

	@Override
	public String identity() {
		return identity;
	}

	@Override
	public String routeBundleSha256() {
		return routeRuntimeView.routeBundleSha256();
	}

	@Override
	public long generation() {
		return routeRuntimeView.generation();
	}

	RaptorRouteBundleRuntimeView routeRuntimeView() {
		return routeRuntimeView;
	}

	RouteTimetableRaptorPlanner.RealtimeOverlay realtimeOverlay() {
		return realtimeOverlay;
	}

	private static String requireText(String value, String name) {
		Objects.requireNonNull(value, name);
		if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
		return value;
	}
}

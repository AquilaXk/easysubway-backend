package com.easysubway.route.application.service;

import com.easysubway.journey.application.JourneyRaptorRealtimeView;
import com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableRealtimeUpdate;
import com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableRealtimeUpdates;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class RaptorRealtimeRuntimeView implements JourneyRaptorRealtimeView {

	private final String identity;
	private final RaptorRouteBundleRuntimeView routeRuntimeView;
	private final Map<LocalDate, RouteTimetableRaptorPlanner.RealtimeOverlay> realtimeOverlays;

	private RaptorRealtimeRuntimeView(
		String identity,
		RaptorRouteBundleRuntimeView routeRuntimeView,
		Map<LocalDate, RouteTimetableRaptorPlanner.RealtimeOverlay> realtimeOverlays
	) {
		this.identity = requireText(identity, "identity");
		this.routeRuntimeView = Objects.requireNonNull(routeRuntimeView, "routeRuntimeView");
		this.realtimeOverlays = Map.copyOf(Objects.requireNonNull(realtimeOverlays, "realtimeOverlays"));
	}

	public static RaptorRealtimeRuntimeView compile(
		String identity,
		RaptorRouteBundleRuntimeView routeRuntimeView,
		JourneyTimetableRealtimeResolver.Updates updates
	) {
		String requiredIdentity = requireText(identity, "identity");
		routeRuntimeView = Objects.requireNonNull(routeRuntimeView, "routeRuntimeView");
		updates = Objects.requireNonNull(updates, "updates");
		if (!updates.available() || updates.updates().stream()
			.anyMatch(update -> update == null || !requiredIdentity.equals(update.providerSnapshotId()))) {
			throw new IllegalArgumentException("realtime updates do not match runtime identity");
		}
		Map<LocalDate, List<JourneyTimetableRealtimeResolver.Update>> updatesByDate = updatesByServiceDate(updates);
		Map<LocalDate, RouteTimetableRaptorPlanner.RealtimeOverlay> overlays = new LinkedHashMap<>();
		for (Map.Entry<LocalDate, List<JourneyTimetableRealtimeResolver.Update>> entry : updatesByDate.entrySet()) {
			TimetableRealtimeUpdates compilerInput = verifiedCompilerInput(
				routeRuntimeView.compiledTimetable(), updates.version(), entry.getValue(), entry.getKey());
			var overlay = new RouteTimetableRaptorPlanner().compileRealtimeOverlay(
				routeRuntimeView.compiledTimetable(), compilerInput);
			if (!overlay.available() || overlay.isEmpty()) {
				throw new IllegalArgumentException("realtime overlay must contain valid updates");
			}
			overlays.put(entry.getKey(), overlay);
		}
		return new RaptorRealtimeRuntimeView(requiredIdentity, routeRuntimeView, overlays);
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

	RouteTimetableRaptorPlanner.RealtimeOverlay realtimeOverlay(LocalDate serviceDate) {
		RouteTimetableRaptorPlanner.RealtimeOverlay overlay = realtimeOverlays.get(serviceDate);
		if (overlay == null) {
			throw new IllegalArgumentException("realtime overlay is unavailable for service date");
		}
		return overlay;
	}

	private static Map<LocalDate, List<JourneyTimetableRealtimeResolver.Update>> updatesByServiceDate(
		JourneyTimetableRealtimeResolver.Updates updates
	) {
		Map<LocalDate, List<JourneyTimetableRealtimeResolver.Update>> byDate = new LinkedHashMap<>();
		for (JourneyTimetableRealtimeResolver.Update update : updates.updates()) {
			if (update.departure() == null || update.departure().serviceDate() == null) {
				throw new IllegalArgumentException("realtime overlay must contain valid updates");
			}
			byDate.computeIfAbsent(update.departure().serviceDate(), ignored -> new ArrayList<>()).add(update);
		}
		if (byDate.isEmpty()) {
			throw new IllegalArgumentException("realtime overlay must contain valid updates");
		}
		Map<LocalDate, List<JourneyTimetableRealtimeResolver.Update>> immutable = new LinkedHashMap<>();
		byDate.forEach((serviceDate, dateUpdates) -> immutable.put(serviceDate, List.copyOf(dateUpdates)));
		return Map.copyOf(immutable);
	}

	private static TimetableRealtimeUpdates verifiedCompilerInput(
		RouteTimetableRaptorPlanner.CompiledTimetable timetable,
		String version,
		List<JourneyTimetableRealtimeResolver.Update> updates,
		LocalDate serviceDate
	) {
		RouteTimetableRaptorPlanner planner = new RouteTimetableRaptorPlanner();
		List<TimetableRealtimeUpdate> projected = new ArrayList<>(updates.size());
		for (JourneyTimetableRealtimeResolver.Update update : updates) {
			if (!serviceDate.equals(update.departure().serviceDate())
				|| !planner.matchesActiveJourneyRealtimeDeparture(timetable, update.departure())) {
				throw new IllegalArgumentException("realtime overlay must contain valid updates");
			}
			projected.add(new TimetableRealtimeUpdate(
				update.departure().tripId(), update.arrivalDeltaSeconds(), update.departureDeltaSeconds(),
				update.cancelled(), update.providerSnapshotId(), update.providerObservedAt()));
		}
		return new TimetableRealtimeUpdates(version, true, projected, null);
	}

	private static String requireText(String value, String name) {
		Objects.requireNonNull(value, name);
		if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
		return value;
	}
}

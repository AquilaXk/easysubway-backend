package com.easysubway.route.application.service;

import com.easysubway.journey.application.JourneyRaptorRealtimeView;
import com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableRealtimeUpdate;
import com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableRealtimeUpdates;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class RaptorRealtimeRuntimeView implements JourneyRaptorRealtimeView {

	private final String identity;
	private final RaptorRouteBundleRuntimeView routeRuntimeView;
	private final LocalDate serviceDate;
	private final RouteTimetableRaptorPlanner.RealtimeOverlay realtimeOverlay;

	private RaptorRealtimeRuntimeView(
		String identity,
		RaptorRouteBundleRuntimeView routeRuntimeView,
		LocalDate serviceDate,
		RouteTimetableRaptorPlanner.RealtimeOverlay realtimeOverlay
	) {
		this.identity = requireText(identity, "identity");
		this.routeRuntimeView = Objects.requireNonNull(routeRuntimeView, "routeRuntimeView");
		this.serviceDate = Objects.requireNonNull(serviceDate, "serviceDate");
		this.realtimeOverlay = Objects.requireNonNull(realtimeOverlay, "realtimeOverlay");
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
		LocalDate serviceDate = requireSingleServiceDate(updates);
		TimetableRealtimeUpdates compilerInput = verifiedCompilerInput(
			routeRuntimeView.compiledTimetable(), updates, serviceDate);
		var overlay = new RouteTimetableRaptorPlanner().compileRealtimeOverlay(
			routeRuntimeView.compiledTimetable(), compilerInput);
		if (!overlay.available() || overlay.isEmpty()) {
			throw new IllegalArgumentException("realtime overlay must contain valid updates");
		}
		return new RaptorRealtimeRuntimeView(requiredIdentity, routeRuntimeView, serviceDate, overlay);
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

	LocalDate serviceDate() {
		return serviceDate;
	}

	private static LocalDate requireSingleServiceDate(JourneyTimetableRealtimeResolver.Updates updates) {
		LocalDate serviceDate = null;
		for (JourneyTimetableRealtimeResolver.Update update : updates.updates()) {
			if (update.departure() == null || update.departure().serviceDate() == null) {
				throw new IllegalArgumentException("realtime overlay must contain valid updates");
			}
			if (serviceDate == null) {
				serviceDate = update.departure().serviceDate();
			} else if (!serviceDate.equals(update.departure().serviceDate())) {
				throw new IllegalArgumentException("realtime overlay must contain valid updates");
			}
		}
		if (serviceDate == null) {
			throw new IllegalArgumentException("realtime overlay must contain valid updates");
		}
		return serviceDate;
	}

	private static TimetableRealtimeUpdates verifiedCompilerInput(
		RouteTimetableRaptorPlanner.CompiledTimetable timetable,
		JourneyTimetableRealtimeResolver.Updates updates,
		LocalDate serviceDate
	) {
		RouteTimetableRaptorPlanner planner = new RouteTimetableRaptorPlanner();
		List<TimetableRealtimeUpdate> projected = new ArrayList<>(updates.updates().size());
		for (JourneyTimetableRealtimeResolver.Update update : updates.updates()) {
			if (!serviceDate.equals(update.departure().serviceDate())
				|| !planner.matchesActiveJourneyRealtimeDeparture(timetable, update.departure())) {
				throw new IllegalArgumentException("realtime overlay must contain valid updates");
			}
			projected.add(new TimetableRealtimeUpdate(
				update.departure().tripId(), update.arrivalDeltaSeconds(), update.departureDeltaSeconds(),
				update.cancelled(), update.providerSnapshotId(), update.providerObservedAt()));
		}
		return new TimetableRealtimeUpdates(updates.version(), true, projected, null);
	}

	private static String requireText(String value, String name) {
		Objects.requireNonNull(value, name);
		if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
		return value;
	}
}

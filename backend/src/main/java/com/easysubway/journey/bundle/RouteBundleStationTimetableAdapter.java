package com.easysubway.journey.bundle;

import com.easysubway.journey.application.StationTimetableSearchService.Failure;
import com.easysubway.journey.application.StationTimetableSearchService.FailureException;
import com.easysubway.journey.application.StationTimetableSearchService.SourceIdentity;
import com.easysubway.journey.application.StationTimetableSnapshotPort;
import java.util.Objects;

/**
 * Projects the one active route-bundle generation into station timetable search (#476).
 *
 * <p>The station timetable reads the same bundle generation the Journey planner serves. Without an active
 * bundle it fails as {@code TIMETABLE_UNAVAILABLE}; once the bundle passes {@code freshUntil} it fails as
 * {@code TIMETABLE_STALE}. It never reads any other timetable source.</p>
 */
public final class RouteBundleStationTimetableAdapter implements StationTimetableSnapshotPort {

	private final RouteBundleActivationRegistry registry;

	public RouteBundleStationTimetableAdapter(RouteBundleActivationRegistry registry) {
		this.registry = Objects.requireNonNull(registry, "registry");
	}

	@Override
	public StationTimetableSnapshot loadStationTimetableSnapshot() {
		ActiveRouteBundleSnapshot active;
		try {
			active = registry.activeSnapshot();
		} catch (RouteBundleActivationException exception) {
			throw new FailureException(exception.reason() == RouteBundleActivationException.Reason.BUNDLE_STALE
				? Failure.TIMETABLE_STALE : Failure.TIMETABLE_UNAVAILABLE);
		}
		if (!(active.runtimeView() instanceof RouteBundleStationTimetableSource source)) {
			throw new FailureException(Failure.TIMETABLE_UNAVAILABLE);
		}
		RouteBundleIdentity identity = active.identity();
		// 응답 계약(StationTimetableSourceIdentity)의 필드에 번들 manifest identity를 그대로 옮긴다.
		return new StationTimetableSnapshot(
			new SourceIdentity(
				identity.bundleId(),
				identity.timetableSha256(),
				"sha256:" + identity.stationSetSha256(),
				identity.stationSetSha256(),
				identity.provenanceSha256(),
				active.admissionEvidence().manifestSha256(),
				identity.freshUntilInstant()),
			source.stationTimetable(),
			source.canonicalStationLines());
	}
}

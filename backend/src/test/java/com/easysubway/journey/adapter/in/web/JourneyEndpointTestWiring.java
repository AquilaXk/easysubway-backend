package com.easysubway.journey.adapter.in.web;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.easysubway.journey.analytics.InMemoryJourneySearchRecordStore;
import com.easysubway.journey.analytics.JourneySearchLatencyMetrics;
import com.easysubway.journey.analytics.JourneySearchRecorder;
import com.easysubway.journey.application.ActiveJourneySnapshotPort;
import com.easysubway.journey.application.FacilityAvailabilityPort;
import com.easysubway.journey.application.JourneyApplicationDeadlineExecutor;
import com.easysubway.journey.application.JourneyApplicationService;
import com.easysubway.journey.application.JourneyProfileResourcePolicy;
import com.easysubway.journey.application.JourneyRealtimePort;
import com.easysubway.journey.application.JourneySessionService;
import com.easysubway.route.application.service.JourneyRaptorAdapter;
import com.easysubway.route.application.service.RouteTimetableRaptorPlanner.ScanWorkspacePool;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;

/**
 * 실제 HTTP 엔드포인트를 내장 Tomcat에 올리는 수동 도구({@link JourneyEndpointLatencyMeasurement}, {@link JourneyLoadServer})가
 * 함께 쓰는 구성 조각. 정책 값이나 세션 목이 바뀌면 두 도구가 같이 바뀌도록 한곳에 둔다.
 */
final class JourneyEndpointTestWiring {

	private static final int SCAN_WORKSPACES = 16;
	private static final Duration SESSION_LIFETIME = Duration.ofHours(1);

	private JourneyEndpointTestWiring() {
	}

	/** platform 후보 정책 RAPTOR_RESOURCE_POLICY_V1 1.1.0(점·프로필·마지막 열차 마감 2/5/8초). */
	static JourneyProfileResourcePolicy resourcePolicy() {
		return new JourneyProfileResourcePolicy(
			new JourneyProfileResourcePolicy.Identity("RAPTOR_RESOURCE_POLICY_V1", "1.1.0", "a".repeat(64)),
			Duration.ofSeconds(3_600), 2, 10_000_000L, 2_048, 128, 128, Duration.ofSeconds(3_600),
			Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(8), 1, 2, 3, 4, 10);
	}

	/** 세션 검증(Play Integrity)만 고정 응답으로 대신한다. */
	static JourneySessionService authorizedSessions(Instant now) {
		var sessions = mock(JourneySessionService.class);
		when(sessions.authorize(anyString(), anyInt())).thenReturn(
			new JourneySessionService.AuthorizedSession("journey:v3", now.plus(SESSION_LIFETIME)));
		return sessions;
	}

	/** 기록 실행기는 데몬 스레드라 도구 종료를 막지 않는다. */
	static JourneySearchRecorder recorder(MeterRegistry meters, Clock clock) {
		var recorderThread = Executors.newSingleThreadExecutor(task -> {
			var thread = new Thread(task, "journey-search-recorder");
			thread.setDaemon(true);
			return thread;
		});
		return new JourneySearchRecorder(new InMemoryJourneySearchRecordStore(), recorderThread, clock, meters);
	}

	/** 운영 점 탐색 컨트롤러를 운영 엔진(작업 공간 풀 16개, 가상 스레드 실행기)으로 묶는다. 실시간 정책은 쓰지 않는다. */
	static JourneySearchController pointSearchController(
		ActiveJourneySnapshotPort snapshots, FacilityAvailabilityPort facility, boolean facilityStatusRequired, Clock clock,
		JourneySessionService sessions, JourneyProfileResourcePolicy policy, JourneySearchRecorder recorder,
		JourneySearchLatencyMetrics latency
	) {
		JourneyRealtimePort timetableOnly = (request, snapshot, instant) -> {
			throw new IllegalStateException("timetable-only measurement");
		};
		var raptor = new JourneyRaptorAdapter(new ScanWorkspacePool(SCAN_WORKSPACES), facility, facilityStatusRequired, clock);
		var service = new JourneyApplicationService(snapshots, timetableOnly, raptor, clock);
		var workers = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("journey-search-", 0).factory());
		var executor = new JourneyApplicationDeadlineExecutor(service, workers, workers, policy.pointSearchDeadline());
		return new JourneySearchController(sessions, executor, policy, recorder, latency);
	}
}

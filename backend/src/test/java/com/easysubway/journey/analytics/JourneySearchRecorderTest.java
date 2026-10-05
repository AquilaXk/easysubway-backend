package com.easysubway.journey.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.easysubway.journey.application.JourneyRaptorQuery;
import com.easysubway.journey.application.JourneyRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Journey V3 검색 기록기")
class JourneySearchRecorderTest {

	private static final String REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
	// 2026-09-30T16:00Z는 서울 기준 2026-10-01 01:00이다.
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-30T16:00:00Z"), ZoneOffset.UTC);

	private InMemoryStore store;
	private SimpleMeterRegistry meters;

	@BeforeEach
	void setUp() {
		store = new InMemoryStore();
		meters = new SimpleMeterRegistry();
	}

	@Test
	@DisplayName("실패 검색은 탐색 종류·결과 분류·이동 프로필을 기록하고 알 수 없는 값은 명시적으로 남긴다")
	void recordsFailureFactsAndExplicitUnknowns() {
		var recorder = new JourneySearchRecorder(store, Runnable::run, CLOCK, meters);

		recorder.recordPointFailure(pointRequest(), 422, "ROUTE_NOT_FOUND");

		assertThat(store.saved).hasSize(1);
		JourneySearchRecord record = store.saved.getFirst();
		assertThat(record.kind()).isEqualTo(JourneySearchKind.DEPART_AT);
		assertThat(record.outcome()).isEqualTo(JourneySearchOutcome.NO_ROUTE);
		assertThat(record.httpStatus()).isEqualTo(422);
		assertThat(record.machineCode()).isEqualTo("ROUTE_NOT_FOUND");
		assertThat(record.mobilityProfile()).isEqualTo("STEP_FREE");
		assertThat(record.engineVersion()).isEqualTo(JourneySearchRecord.UNKNOWN);
		assertThat(record.stairFreeStatus()).isEqualTo(JourneySearchRecord.NOT_APPLICABLE);
		assertThat(record.alternativeCategories()).isEmpty();
		assertThat(record.recordedOn()).isEqualTo(LocalDate.parse("2026-10-01"));
		assertThat(record.recordId()).isNotBlank();
		assertThat(recorder.failureCount()).isZero();
	}

	@Test
	@DisplayName("프로필 탐색 실패는 시간 질의 종류로 구분한다")
	void profileFailureUsesTemporalKind() {
		var recorder = new JourneySearchRecorder(store, Runnable::run, CLOCK, meters);
		var query = new JourneyRaptorQuery(REQUEST_ID, "a", "b",
			new JourneyRaptorQuery.LastConnection(LocalDate.parse("2026-10-01")),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STEP_FREE, JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE,
			1, 1, () -> false);

		recorder.recordProfileFailure(query, 422, "TEMPORAL_QUERY_TOO_COMPLEX", null);

		assertThat(store.saved).singleElement().satisfies(record -> {
			assertThat(record.kind()).isEqualTo(JourneySearchKind.LAST_CONNECTION);
			assertThat(record.outcome()).isEqualTo(JourneySearchOutcome.TOO_COMPLEX);
			assertThat(record.engineVersion()).isEqualTo(JourneySearchRecord.UNKNOWN);
		});
	}

	@Test
	@DisplayName("저장 실패는 호출자에게 전파되지 않고 실패 건수로 드러난다")
	void storeFailureIsCountedNotThrown() {
		store.failWith = new IllegalStateException("db down");
		var recorder = new JourneySearchRecorder(store, Runnable::run, CLOCK, meters);

		assertThatCode(() -> recorder.recordPointFailure(pointRequest(), 503, "ROUTE_SERVICE_UNAVAILABLE"))
			.doesNotThrowAnyException();

		assertThat(recorder.failureCount()).isEqualTo(1);
		assertThat(meters.get(JourneySearchRecorder.FAILURE_METRIC).counter().count()).isEqualTo(1.0);
		assertThat(store.saved).isEmpty();
	}

	@Test
	@DisplayName("실행기가 거절해도 검색 경로로 전파되지 않고 실패 건수로 드러난다")
	void rejectedExecutionIsCountedNotThrown() {
		Executor rejecting = command -> {
			throw new RejectedExecutionException("queue full");
		};
		var recorder = new JourneySearchRecorder(store, rejecting, CLOCK, meters);

		assertThatCode(() -> recorder.recordPointFailure(pointRequest(), 504, "JOURNEY_SEARCH_TIMEOUT"))
			.doesNotThrowAnyException();

		assertThat(recorder.failureCount()).isEqualTo(1);
		assertThat(meters.get(JourneySearchRecorder.FAILURE_METRIC).counter().count()).isEqualTo(1.0);
	}

	@Test
	@DisplayName("기록 구성 중 오류도 전파되지 않고 실패 건수로 드러난다")
	void buildFailureIsCountedNotThrown() {
		var recorder = new JourneySearchRecorder(store, Runnable::run, CLOCK, meters);

		assertThatCode(() -> recorder.recordPointFailure(null, 503, "ROUTE_SERVICE_UNAVAILABLE"))
			.doesNotThrowAnyException();

		assertThat(recorder.failureCount()).isEqualTo(1);
		assertThat(store.saved).isEmpty();
	}

	@Test
	@DisplayName("저장은 호출 스레드가 아닌 실행기에서 일어나 검색 경로를 기다리게 하지 않는다")
	void savingIsDeferredToTheExecutor() {
		var queued = new ArrayList<Runnable>();
		var recorder = new JourneySearchRecorder(store, queued::add, CLOCK, meters);

		recorder.recordPointFailure(pointRequest(), 422, "ROUTE_NOT_FOUND");

		assertThat(store.saved).isEmpty();
		assertThat(queued).hasSize(1);
		queued.getFirst().run();
		assertThat(store.saved).hasSize(1);
	}

	@Test
	@DisplayName("비동기 저장 중 발생한 실패도 실패 건수로 드러난다")
	void deferredStoreFailureIsCounted() {
		var queued = new ArrayList<Runnable>();
		store.failWith = new IllegalStateException("db down");
		var recorder = new JourneySearchRecorder(store, queued::add, CLOCK, meters);

		recorder.recordPointFailure(pointRequest(), 422, "ROUTE_NOT_FOUND");
		assertThat(recorder.failureCount()).isZero();
		assertThatCode(() -> queued.getFirst().run()).doesNotThrowAnyException();

		assertThat(recorder.failureCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("종료 시 대기 중인 기록을 제한 시간 안에 모두 저장하고 실패로 세지 않는다")
	void shutdownDrainsQueuedRecordsWithinBound() {
		var pool = new java.util.concurrent.ThreadPoolExecutor(1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
			new java.util.concurrent.LinkedBlockingQueue<>(10));
		var recorder = new JourneySearchRecorder(store, pool, CLOCK, meters);
		for (int i = 0; i < 3; i++) recorder.recordPointFailure(pointRequest(), 422, "ROUTE_NOT_FOUND");

		recorder.shutdown(java.time.Duration.ofSeconds(5));

		assertThat(pool.isTerminated()).isTrue();
		assertThat(store.saved).hasSize(3);
		assertThat(recorder.failureCount()).isZero();
	}

	@Test
	@DisplayName("종료 대기 시간이 지나면 저장하지 못한 대기 기록을 실패로 센다")
	void shutdownCountsUndrainedRecordsAsFailures() throws Exception {
		var pool = new java.util.concurrent.ThreadPoolExecutor(1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
			new java.util.concurrent.LinkedBlockingQueue<>(10));
		var started = new java.util.concurrent.CountDownLatch(1);
		var release = new java.util.concurrent.CountDownLatch(1);
		var blockingStore = new JourneySearchRecordStore() {
			@Override
			public void save(JourneySearchRecord record) {
				started.countDown();
				while (true) {
					try {
						release.await();
						return;
					} catch (InterruptedException ignored) {
						// 종료 시 인터럽트에도 저장을 끝내지 못하는 느린 저장소를 흉내 낸다.
					}
				}
			}

			@Override
			public List<JourneySearchAggregateRow> aggregate(LocalDate from, LocalDate to) {
				throw new UnsupportedOperationException();
			}

			@Override
			public int purgeRecordedBefore(LocalDate cutoff) {
				throw new UnsupportedOperationException();
			}
		};
		var recorder = new JourneySearchRecorder(blockingStore, pool, CLOCK, meters);
		for (int i = 0; i < 3; i++) recorder.recordPointFailure(pointRequest(), 422, "ROUTE_NOT_FOUND");
		assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

		try {
			recorder.shutdown(java.time.Duration.ofMillis(50));

			// 진행 중 1건은 계속 처리하고, 대기 중이던 2건은 버려졌으므로 실패로 센다.
			assertThat(recorder.failureCount()).isEqualTo(2);
			assertThat(meters.get(JourneySearchRecorder.FAILURE_METRIC).counter().count()).isEqualTo(2.0);
		} finally {
			release.countDown();
		}
	}

	private static JourneyRequest pointRequest() {
		return new JourneyRequest(REQUEST_ID, "origin", "destination", new JourneyRequest.Departure.Now(),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STEP_FREE, JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE,
			2, 1, () -> false);
	}

	static class InMemoryStore implements JourneySearchRecordStore {
		final List<JourneySearchRecord> saved = new ArrayList<>();
		RuntimeException failWith;

		@Override
		public void save(JourneySearchRecord record) {
			if (failWith != null) throw failWith;
			saved.add(record);
		}

		@Override
		public List<JourneySearchAggregateRow> aggregate(LocalDate fromInclusive, LocalDate toInclusive) {
			throw new UnsupportedOperationException();
		}

		@Override
		public int purgeRecordedBefore(LocalDate cutoff) {
			throw new UnsupportedOperationException();
		}
	}
}

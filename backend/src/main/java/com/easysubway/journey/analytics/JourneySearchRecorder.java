package com.easysubway.journey.analytics;

import com.easysubway.journey.application.JourneyAlternatives;
import com.easysubway.journey.application.JourneyCandidate;
import com.easysubway.journey.application.JourneyExecutionResult;
import com.easysubway.journey.application.JourneyProfileExecutionResult;
import com.easysubway.journey.application.JourneyRaptorPruningInventoryV1;
import com.easysubway.journey.application.JourneyRaptorQuery;
import com.easysubway.journey.application.JourneyRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Journey V3 검색 사실을 운영 분석 저장소에 남긴다. 저장은 별도 실행기에서 일어나므로 검색 응답을 기다리게 하지 않고,
 * 구성·제출·저장 중 어떤 실패도 검색 경로로 전파하지 않는다. 대신 실패 건수를 지표로 세고 로그로 남긴다.
 */
@Component
public class JourneySearchRecorder {

	public static final String FAILURE_METRIC = "easysubway.journey.search_record.failures";
	private static final Logger LOG = LoggerFactory.getLogger(JourneySearchRecorder.class);
	private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
	private static final int QUEUE_CAPACITY = 1_000;

	private final JourneySearchRecordStore store;
	private final Executor executor;
	private final ExecutorService ownedExecutor;
	private final Clock clock;
	private final Counter failures;

	@Autowired
	public JourneySearchRecorder(
		JourneySearchRecordStore store, ObjectProvider<Clock> clockProvider, MeterRegistry meterRegistry
	) {
		this(store, newExecutor(), clockProvider.getIfAvailable(() -> Clock.system(SERVICE_ZONE)), meterRegistry);
	}

	public JourneySearchRecorder(
		JourneySearchRecordStore store, Executor executor, Clock clock, MeterRegistry meterRegistry
	) {
		this.store = Objects.requireNonNull(store, "store");
		this.executor = Objects.requireNonNull(executor, "executor");
		this.ownedExecutor = executor instanceof ThreadPoolExecutor pool ? pool : null;
		this.clock = Objects.requireNonNull(clock, "clock");
		this.failures = Counter.builder(FAILURE_METRIC)
			.description("Journey V3 검색 분석 기록에 실패한 건수")
			.register(Objects.requireNonNull(meterRegistry, "meterRegistry"));
	}

	private static ThreadPoolExecutor newExecutor() {
		var pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(QUEUE_CAPACITY),
			runnable -> {
				var thread = new Thread(runnable, "journey-search-recorder");
				thread.setDaemon(true);
				return thread;
			}, new ThreadPoolExecutor.AbortPolicy());
		return pool;
	}

	@PreDestroy
	void shutdown() {
		if (ownedExecutor != null) ownedExecutor.shutdown();
	}

	/** 서버 시작 이후 기록에 실패한 건수. */
	public long failureCount() {
		return (long) failures.count();
	}

	public void recordPointSuccess(JourneyRequest request, JourneyExecutionResult.Success success) {
		submit(() -> {
			Objects.requireNonNull(request, "request");
			Objects.requireNonNull(success, "success");
			Set<JourneyAlternatives.Category> categories = EnumSet.noneOf(JourneyAlternatives.Category.class);
			for (JourneyCandidate journey : success.journeys()) categories.addAll(journey.alternativeCategories());
			return build(JourneySearchKind.DEPART_AT, JourneySearchOutcome.FOUND, 200, null,
				JourneySearchRecord.UNKNOWN, request.mobilityProfile().name(),
				categories.stream().map(Enum::name).toList(), success.stairFreeAlternative().status().name());
		});
	}

	public void recordPointFailure(JourneyRequest request, int httpStatus, String machineCode) {
		submit(() -> {
			Objects.requireNonNull(request, "request");
			return failure(JourneySearchKind.DEPART_AT, request.mobilityProfile().name(), httpStatus, machineCode,
				JourneySearchRecord.UNKNOWN);
		});
	}

	public void recordProfileSuccess(
		JourneyRaptorQuery query, JourneyProfileExecutionResult.Success success, Collection<String> objectiveTags
	) {
		submit(() -> {
			Objects.requireNonNull(query, "query");
			Objects.requireNonNull(success, "success");
			return build(JourneySearchKind.of(query.temporalQuery()), JourneySearchOutcome.FOUND, 200, null,
				engineVersion(success.countSnapshot()), query.mobilityProfile().name(),
				List.copyOf(new TreeSet<>(Objects.requireNonNull(objectiveTags, "objectiveTags"))),
				JourneySearchRecord.UNKNOWN);
		});
	}

	/** {@code countSnapshot}은 실패 응답에 엔진 식별이 있을 때만 넘기고, 없으면 null이며 엔진 버전은 UNKNOWN으로 남는다. */
	public void recordProfileFailure(
		JourneyRaptorQuery query, int httpStatus, String machineCode,
		JourneyRaptorPruningInventoryV1.CountSnapshot countSnapshot
	) {
		submit(() -> {
			Objects.requireNonNull(query, "query");
			return failure(JourneySearchKind.of(query.temporalQuery()), query.mobilityProfile().name(), httpStatus,
				machineCode, countSnapshot == null ? JourneySearchRecord.UNKNOWN : engineVersion(countSnapshot));
		});
	}

	private static String engineVersion(JourneyRaptorPruningInventoryV1.CountSnapshot snapshot) {
		var identity = snapshot.algorithmIdentity();
		return identity.algorithmSuiteId() + "/" + identity.queryAlgorithmId() + "/" + identity.semanticVersion();
	}

	private JourneySearchRecord failure(
		JourneySearchKind kind, String mobilityProfile, int httpStatus, String machineCode, String engineVersion
	) {
		return build(kind, JourneySearchOutcome.classifyFailure(httpStatus, machineCode), httpStatus,
			Objects.requireNonNull(machineCode, "machineCode"), engineVersion, mobilityProfile, List.of(),
			JourneySearchRecord.NOT_APPLICABLE);
	}

	private JourneySearchRecord build(
		JourneySearchKind kind, JourneySearchOutcome outcome, int httpStatus, String machineCode,
		String engineVersion, String mobilityProfile, List<String> categories, String stairFreeStatus
	) {
		Instant now = clock.instant();
		return new JourneySearchRecord(UUID.randomUUID().toString(), now, LocalDate.ofInstant(now, SERVICE_ZONE),
			kind, outcome, httpStatus, machineCode, engineVersion, mobilityProfile, categories, stairFreeStatus);
	}

	private void submit(Supplier<JourneySearchRecord> factory) {
		try {
			JourneySearchRecord record = factory.get();
			executor.execute(() -> persist(record));
		} catch (RuntimeException exception) {
			fail(exception);
		}
	}

	private void persist(JourneySearchRecord record) {
		try {
			store.save(record);
		} catch (RuntimeException exception) {
			fail(exception);
		}
	}

	private void fail(RuntimeException exception) {
		failures.increment();
		LOG.warn("Journey V3 검색 분석 기록에 실패했습니다: {}", exception.getClass().getSimpleName());
	}
}

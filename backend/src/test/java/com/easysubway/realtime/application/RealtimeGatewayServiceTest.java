package com.easysubway.realtime.application;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.realtime.adapter.out.persistence.InMemoryRealtimeMappingPort;
import com.easysubway.realtime.application.port.out.RealtimeArrivalArchivePort;
import com.easysubway.realtime.application.port.out.RealtimeMappingPort;
import com.easysubway.realtime.domain.RealtimeArrivalObservation;
import com.easysubway.realtime.domain.RealtimeMapping;
import com.easysubway.realtime.domain.RealtimeArrival;
import com.easysubway.realtime.domain.RealtimeStatus;
import com.easysubway.realtime.domain.RealtimeTrainPosition;
import com.easysubway.realtime.domain.RealtimeTripMapping;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("실시간 gateway cache와 fallback 정책")
class RealtimeGatewayServiceTest {

	@Test
	@DisplayName("Spring constructor는 archive 포트를 필수 의존성으로 받는다")
	void springConstructorRequiresProductionSafetyPorts() {
		var parameterTypes = Arrays.stream(RealtimeGatewayService.class.getConstructors())
			.filter(constructor -> constructor.isAnnotationPresent(Autowired.class))
			.findFirst()
			.map(constructor -> List.of(constructor.getParameterTypes()))
			.orElseThrow();

		assertThat(parameterTypes)
			.contains(RealtimeArrivalArchivePort.class)
			.contains(Executor.class)
			.doesNotContain(Optional.class);
	}

	@Test
	@DisplayName("provider raw cause는 공개 unavailable cause로 정규화한다")
	void normalizesProviderCauseForArrivalAndTrainPosition() {
		RealtimeProviderException exception = new RealtimeProviderException("PROVIDER_TIMEOUT");
		MutableClock clock = new MutableClock(Instant.parse("2026-06-26T08:00:00Z"));
		RealtimeProvider provider = new RealtimeProvider() {
			@Override
			public List<RealtimeArrival> arrivals(RealtimeQuery query) {
				throw new RealtimeProviderException("provider detail must not be public");
			}

			@Override
			public List<RealtimeTrainPosition> trainPositions(RealtimeQuery query) {
				throw new RealtimeProviderException(null);
			}
		};
		RealtimeGatewayService service = service(provider, clock);

		RealtimeArrivalResult arrivals = service.arrivals(sangnoksuQuery());
		clock.instant = Instant.parse("2026-06-26T08:01:00Z");
		RealtimeTrainPositionResult positions = service.trainPositions(line4Query());

		assertThat(exception.providerCause()).isEqualTo("PROVIDER_TIMEOUT");
		assertThat(arrivals.status()).hasToString("UNAVAILABLE");
		assertThat(arrivals.fallbackCode()).isEqualTo("PROVIDER_ERROR");
		assertThat(positions.status()).hasToString("UNAVAILABLE");
		assertThat(positions.fallbackCode()).isEqualTo("PROVIDER_ERROR");
	}

	@Test
	@DisplayName("provider의 TOPIS 로컬(KST) recptnDt는 경계에서 ISO providerReceivedAt으로 정규화되어 emit된다")
	void normalizesProviderTimestampToIsoAtBoundary() {
		// TOPIS recptnDt는 "yyyy-MM-dd HH:mm:ss"(KST). 17:00:00 KST = 08:00:00Z, clock과 20초 차 → fresh.
		RealtimeProvider provider = query -> List.of(new RealtimeArrival(
			"1004",
			"상록수",
			"사당",
			"상행",
			"T1001",
			150,
			"3분 후",
			"전역 출발",
			"2026-06-26 17:00:00",
			"일반"
		));
		RealtimeGatewayService service = service(
			provider,
			Clock.fixed(Instant.parse("2026-06-26T08:00:20Z"), ZoneOffset.UTC)
		);

		RealtimeArrivalResult result = service.arrivals(sangnoksuQuery());

		assertThat(result.arrivals()).hasSize(1);
		RealtimeArrival arrival = result.arrivals().getFirst();
		// 하류(resolver)가 Instant.parse 가능한 ISO로 정규화 → drop 버그의 근본(포맷 누출) 차단.
		assertThat(Instant.parse(arrival.providerReceivedAt())).isEqualTo(Instant.parse("2026-06-26T08:00:00Z"));
		// v1 표시용 수신지연 보정은 유지: 150 - 20초 delay = 130.
		assertThat(arrival.etaSeconds()).isEqualTo(130);
	}

	@Test
	@DisplayName("같은 도착 요청은 cache TTL 안에서 provider 호출을 반복하지 않는다")
	void arrivalsUseCacheWithinTtl() {
		CountingProvider provider = new CountingProvider();
		RealtimeGatewayService service = service(
			provider,
			Clock.fixed(Instant.parse("2026-06-26T08:00:00Z"), ZoneOffset.UTC)
		);
		RealtimeQuery query = sangnoksuQuery();

		RealtimeArrivalResult first = service.arrivals(query);
		RealtimeArrivalResult second = service.arrivals(query);

		assertThat(first.status()).hasToString("FRESH");
		assertThat(second.status()).hasToString("FRESH");
		assertThat(provider.arrivalCalls).hasValue(1);
	}

	@Test
	@DisplayName("cache는 정확히 20초까지 재사용하고 그 뒤에는 arrivals와 열차 위치를 새로 조회한다")
	void cacheFreshnessIncludesExactTtlButExcludesOlderResults() {
		MutableClock clock = new MutableClock(Instant.parse("2026-06-26T08:00:00Z"));
		CountingProvider provider = new CountingProvider();
		RealtimeGatewayService service = serviceWithAlwaysAvailableQuota(provider, clock);

		RealtimeArrivalResult initialArrivals = service.arrivals(sangnoksuQuery());
		RealtimeTrainPositionResult initialPositions = service.trainPositions(line4Query());
		clock.instant = Instant.parse("2026-06-26T08:00:20Z");
		RealtimeArrivalResult arrivalAtTtl = service.arrivals(sangnoksuQuery());
		RealtimeTrainPositionResult positionAtTtl = service.trainPositions(line4Query());
		clock.instant = Instant.parse("2026-06-26T08:00:21Z");
		provider.providerReceivedAt = clock.instant.toString();
		RealtimeArrivalResult arrivalAfterTtl = service.arrivals(sangnoksuQuery());
		RealtimeTrainPositionResult positionAfterTtl = service.trainPositions(line4Query());

		assertThat(arrivalAtTtl.status()).hasToString("FRESH");
		assertThat(positionAtTtl.status()).hasToString("FRESH");
		assertThat(arrivalAtTtl.receivedAt()).isEqualTo(initialArrivals.receivedAt());
		assertThat(arrivalAtTtl.arrivals()).isEqualTo(initialArrivals.arrivals());
		assertThat(positionAtTtl.receivedAt()).isEqualTo(initialPositions.receivedAt());
		assertThat(positionAtTtl.trainPositions()).isEqualTo(initialPositions.trainPositions());
		assertThat(arrivalAfterTtl.status()).hasToString("FRESH");
		assertThat(positionAfterTtl.status()).hasToString("FRESH");
		assertThat(arrivalAfterTtl.receivedAt()).isEqualTo("2026-06-26T08:00:21Z");
		assertThat(arrivalAfterTtl.receivedAt()).isNotEqualTo(initialArrivals.receivedAt());
		assertThat(positionAfterTtl.receivedAt()).isEqualTo("2026-06-26T08:00:21Z");
		assertThat(positionAfterTtl.receivedAt()).isNotEqualTo(initialPositions.receivedAt());
		assertThat(provider.arrivalCalls).hasValue(2);
		assertThat(provider.trainPositionCalls).hasValue(2);
	}

	@Test
	@DisplayName("clock rollback은 prior arrivals와 열차 위치 cache를 재사용하지 않고 provider 실패를 unavailable로 닫는다")
	void clockRollbackDoesNotReuseCachedResults() {
		MutableClock clock = new MutableClock(Instant.parse("2026-06-26T08:00:00Z"));
		CountingProvider provider = new CountingProvider();
		RealtimeGatewayService service = serviceWithAlwaysAvailableQuota(provider, clock);

		service.arrivals(sangnoksuQuery());
		service.trainPositions(line4Query());
		clock.instant = Instant.parse("2026-06-26T07:59:59Z");
		provider.failureCode = "PROVIDER_ERROR";
		RealtimeArrivalResult arrivals = service.arrivals(sangnoksuQuery());
		RealtimeTrainPositionResult positions = service.trainPositions(line4Query());
		clock.instant = Instant.parse("2026-06-26T08:00:00Z");
		RealtimeArrivalResult arrivalsAfterClockRecovery = service.arrivals(sangnoksuQuery());
		RealtimeTrainPositionResult positionsAfterClockRecovery = service.trainPositions(line4Query());

		assertThat(arrivals.status()).hasToString("UNAVAILABLE");
		assertThat(arrivals.arrivals()).isEmpty();
		assertThat(positions.status()).hasToString("UNAVAILABLE");
		assertThat(positions.trainPositions()).isEmpty();
		assertThat(arrivalsAfterClockRecovery.status()).hasToString("UNAVAILABLE");
		assertThat(arrivalsAfterClockRecovery.arrivals()).isEmpty();
		assertThat(positionsAfterClockRecovery.status()).hasToString("UNAVAILABLE");
		assertThat(positionsAfterClockRecovery.trainPositions()).isEmpty();
		assertThat(provider.arrivalCalls).hasValue(3);
		assertThat(provider.trainPositionCalls).hasValue(3);
	}

	@Test
	@DisplayName("future-dated provider 관측은 cache하지 않고 unavailable이며 정상 시각 관측만 새 cache를 만든다")
	void futureDatedProviderObservationsAreUnavailableAndNotCached() {
		MutableClock clock = new MutableClock(Instant.parse("2026-06-26T08:00:00Z"));
		CountingProvider provider = new CountingProvider();
		RealtimeGatewayService service = serviceWithAlwaysAvailableQuota(provider, clock);
		provider.providerReceivedAt = "2026-06-26T08:00:01Z";

		RealtimeArrivalResult futureArrivals = service.arrivals(sangnoksuQuery());
		RealtimeTrainPositionResult futurePositions = service.trainPositions(line4Query());
		clock.instant = Instant.parse("2026-06-26T08:00:01Z");
		provider.providerReceivedAt = clock.instant.toString();
		RealtimeArrivalResult correctedArrivals = service.arrivals(sangnoksuQuery());
		RealtimeTrainPositionResult correctedPositions = service.trainPositions(line4Query());

		assertThat(futureArrivals.status()).hasToString("UNAVAILABLE");
		assertThat(futureArrivals.arrivals()).isEmpty();
		assertThat(futurePositions.status()).hasToString("UNAVAILABLE");
		assertThat(futurePositions.trainPositions()).isEmpty();
		assertThat(correctedArrivals.status()).hasToString("FRESH");
		assertThat(correctedPositions.status()).hasToString("FRESH");
		assertThat(provider.arrivalCalls).hasValue(2);
		assertThat(provider.trainPositionCalls).hasValue(2);
	}

	@Test
	@DisplayName("fresh provider 도착 관측은 한 번 보존하고 cache hit에서는 추가 저장하지 않는다")
	void archivesFreshArrivalsWithoutExtraProviderCalls() {
		CountingProvider provider = new CountingProvider();
		CapturingArrivalArchive archive = new CapturingArrivalArchive();
		RealtimeGatewayService service = service(
			provider,
			Clock.fixed(Instant.parse("2026-06-26T08:00:00Z"), ZoneOffset.UTC),
			InMemoryRealtimeMappingPort.seededFixture(),
			archive
		);

		RealtimeArrivalResult first = service.arrivals(sangnoksuQuery());
		RealtimeArrivalResult cached = service.arrivals(sangnoksuQuery());

		assertThat(first.status()).hasToString("FRESH");
		assertThat(cached.status()).hasToString("FRESH");
		assertThat(provider.arrivalCalls).hasValue(1);
		assertThat(archive.saveCalls).hasValue(1);
		assertThat(archive.observations).singleElement().satisfies((observation) -> {
			assertThat(observation.providerId()).isEqualTo("seoul-topis");
			assertThat(observation.stationId()).isEqualTo("station-sangnoksu");
			assertThat(observation.lineId()).isEqualTo("seoul-4");
			assertThat(observation.providerLineId()).isEqualTo("1004");
			assertThat(observation.providerStationId()).isEqualTo("1004000448");
			assertThat(observation.trainNo()).isEqualTo("4123");
			assertThat(observation.rawEtaSeconds()).isEqualTo(180);
			assertThat(observation.adjustedEtaSeconds()).isEqualTo(180);
			assertThat(observation.providerObservedAt()).isEqualTo(Instant.parse("2026-06-26T08:00:00Z"));
			assertThat(observation.backendReceivedAt()).isEqualTo(Instant.parse("2026-06-26T08:00:00Z"));
			assertThat(observation.retainedUntil()).isEqualTo(Instant.parse("2026-07-26T08:00:00Z"));
		});
	}

	@Test
	@DisplayName("환승역 도착 응답의 다른 노선(subwayId) 열차는 조회 노선의 FRESH 결과와 archive에 귀속하지 않는다")
	void transferStationArrivalsOfOtherProviderLinesAreNotAttributedToQueriedLine() {
		RealtimeProvider provider = query -> List.of(
			euljiro3gaArrival("1002", "2001", "성수행 - 을지로4가방면", "내선", 60),
			euljiro3gaArrival("1003", "3007", "오금행 - 충무로방면", "하행", 120),
			euljiro3gaArrival("1002", "2002", "시청행 - 을지로입구방면", "외선", 150),
			euljiro3gaArrival("1003", "3008", "대화행 - 종로3가방면", "상행", 180)
		);
		CapturingArrivalArchive archive = new CapturingArrivalArchive();
		RealtimeGatewayService service = service(
			provider,
			Clock.fixed(Instant.parse("2026-06-26T08:00:00Z"), ZoneOffset.UTC),
			euljiro3gaLine3MappingPort(),
			archive
		);

		RealtimeArrivalResult result = service.arrivals(euljiro3gaLine3Query());

		assertThat(result.status()).isEqualTo(RealtimeStatus.FRESH);
		assertThat(result.arrivals())
			.extracting(RealtimeArrival::lineId, RealtimeArrival::trainNo)
			.containsExactly(
				org.assertj.core.api.Assertions.tuple("1003", "3007"),
				org.assertj.core.api.Assertions.tuple("1003", "3008")
			);
		assertThat(archive.observations)
			.extracting(RealtimeArrivalObservation::trainNo)
			.containsExactly("3007", "3008");
		assertThat(archive.observations)
			.extracting(RealtimeArrivalObservation::lineId, RealtimeArrivalObservation::providerLineId)
			.containsOnly(org.assertj.core.api.Assertions.tuple("seoul-3", "1003"));
	}

	@Test
	@DisplayName("환승역 응답에 조회 노선 열차가 하나도 없으면 다른 노선 열차로 채우지도, 도착 없음으로 단정하지도 않는다")
	void transferStationResponseWithoutQueriedLineIsUnavailableInsteadOfEmptyOrOtherLine() {
		RealtimeProvider provider = query -> List.of(
			euljiro3gaArrival("1002", "2001", "성수행 - 을지로4가방면", "내선", 60),
			euljiro3gaArrival("1002", "2002", "시청행 - 을지로입구방면", "외선", 150)
		);
		CapturingArrivalArchive archive = new CapturingArrivalArchive();
		RealtimeGatewayService service = service(
			provider,
			Clock.fixed(Instant.parse("2026-06-26T08:00:00Z"), ZoneOffset.UTC),
			euljiro3gaLine3MappingPort(),
			archive
		);

		RealtimeArrivalResult result = service.arrivals(euljiro3gaLine3Query());

		assertThat(result.status()).isEqualTo(RealtimeStatus.UNAVAILABLE);
		assertThat(result.fallbackCode()).isEqualTo("PROVIDER_ERROR");
		assertThat(result.arrivals()).isEmpty();
		assertThat(archive.observations).isEmpty();
	}

	@Test
	@DisplayName("운영 archive 저장은 fresh 응답 경로와 분리된다")
	void dispatchesArchiveWithoutBlockingFreshResponse() {
		CountingProvider provider = new CountingProvider();
		CapturingArrivalArchive archive = new CapturingArrivalArchive();
		CapturingExecutor executor = new CapturingExecutor();
		RealtimeGatewayService service = new RealtimeGatewayService(
			provider,
			Clock.fixed(Instant.parse("2026-06-26T08:00:00Z"), ZoneOffset.UTC),
			InMemoryRealtimeMappingPort.seededFixture(),
			new RealtimeProviderControl(),
			archive,
			executor
		);

		RealtimeArrivalResult result = service.arrivals(sangnoksuQuery());

		assertThat(result.status()).hasToString("FRESH");
		assertThat(archive.saveCalls).hasValue(0);
		executor.runPending();
		assertThat(archive.saveCalls).hasValue(1);
	}

	@Test
	@DisplayName("archive executor가 작업을 거부해도 fresh 응답과 cache는 유지된다")
	void archiveDispatchRejectionDoesNotBreakFreshResponse() {
		CountingProvider provider = new CountingProvider();
		RealtimeGatewayService service = new RealtimeGatewayService(
			provider,
			Clock.fixed(Instant.parse("2026-06-26T08:00:00Z"), ZoneOffset.UTC),
			InMemoryRealtimeMappingPort.seededFixture(),
			new RealtimeProviderControl(),
			new CapturingArrivalArchive(),
			command -> { throw new IllegalStateException("archive executor unavailable"); }
		);

		RealtimeArrivalResult first = service.arrivals(sangnoksuQuery());
		RealtimeArrivalResult cached = service.arrivals(sangnoksuQuery());

		assertThat(first.status()).hasToString("FRESH");
		assertThat(cached.status()).hasToString("FRESH");
		assertThat(provider.arrivalCalls).hasValue(1);
		assertThat(service.providerHealthSnapshot().archiveFailureCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("도착 관측 archive 실패는 fresh 응답을 막지 않고 health counter에 기록한다")
	void archiveFailureDoesNotBreakFreshResponse() {
		RealtimeArrivalArchivePort failingArchive = new RealtimeArrivalArchivePort() {
			@Override
			public void saveAll(List<RealtimeArrivalObservation> observations) {
				throw new IllegalStateException("archive unavailable");
			}

			@Override
			public int deleteExpired(Instant now) {
				return 0;
			}
		};
		CountingProvider provider = new CountingProvider();
		RealtimeGatewayService service = service(
			provider,
			Clock.fixed(Instant.parse("2026-06-26T08:00:00Z"), ZoneOffset.UTC),
			InMemoryRealtimeMappingPort.seededFixture(),
			failingArchive
		);

		RealtimeArrivalResult first = service.arrivals(sangnoksuQuery());
		RealtimeArrivalResult cached = service.arrivals(sangnoksuQuery());

		assertThat(first.status()).hasToString("FRESH");
		assertThat(cached.status()).hasToString("FRESH");
		assertThat(cached).isSameAs(first);
		assertThat(cached.receivedAt()).isEqualTo(first.receivedAt());
		assertThat(cached.arrivals()).isEqualTo(first.arrivals());
		assertThat(provider.arrivalCalls).hasValue(1);
		assertThat(service.providerHealthSnapshot().archiveFailureCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("archive 관측 생성 실패는 fresh 응답과 cache를 막지 않는다")
	void archiveObservationFailureDoesNotBreakFreshResponse() {
		RealtimeProvider provider = query -> List.of(new RealtimeArrival(
			"1004", "상록수", "당고개", "상행", "", 180, "3분 후", "전역 출발", "2026-06-26T08:00:00Z"
		));
		CapturingArrivalArchive archive = new CapturingArrivalArchive();
		RealtimeGatewayService service = service(
			provider,
			Clock.fixed(Instant.parse("2026-06-26T08:00:00Z"), ZoneOffset.UTC),
			InMemoryRealtimeMappingPort.seededFixture(),
			archive
		);

		RealtimeArrivalResult first = service.arrivals(sangnoksuQuery());
		RealtimeArrivalResult cached = service.arrivals(sangnoksuQuery());

		assertThat(first.status()).hasToString("FRESH");
		assertThat(cached.status()).hasToString("FRESH");
		assertThat(first.arrivals()).hasSize(1);
		assertThat(archive.saveCalls).hasValue(0);
		assertThat(service.providerHealthSnapshot().archiveFailureCount()).isEqualTo(1);
	}


	@Test
	@DisplayName("같은 도착 요청의 동시 cache miss는 provider 호출을 공유한다")
	void concurrentArrivalMissesShareProviderCall() throws Exception {
		BlockingProvider provider = new BlockingProvider();
		RealtimeGatewayService service = service(
			provider,
			Clock.fixed(Instant.parse("2026-06-26T08:00:00Z"), ZoneOffset.UTC)
		);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			CompletableFuture<RealtimeArrivalResult> first = CompletableFuture.supplyAsync(
				() -> service.arrivals(sangnoksuQuery()),
				executor
			);
			assertThat(provider.arrivalEntered.await(1, TimeUnit.SECONDS)).isTrue();
			CompletableFuture<RealtimeArrivalResult> second = CompletableFuture.supplyAsync(
				() -> service.arrivals(sangnoksuQuery()),
				executor
			);
			Thread.sleep(100);

			assertThat(provider.arrivalCalls).hasValue(1);
			provider.releaseArrivals.countDown();

			assertThat(first.get(1, TimeUnit.SECONDS).status()).hasToString("FRESH");
			assertThat(second.get(1, TimeUnit.SECONDS).status()).hasToString("FRESH");
			assertThat(provider.arrivalCalls).hasValue(1);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	@DisplayName("같은 열차 위치 요청의 동시 cache miss는 provider 호출을 공유한다")
	void concurrentTrainPositionMissesShareProviderCall() throws Exception {
		BlockingProvider provider = new BlockingProvider();
		RealtimeGatewayService service = service(
			provider,
			Clock.fixed(Instant.parse("2026-06-26T08:00:00Z"), ZoneOffset.UTC)
		);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			CompletableFuture<RealtimeTrainPositionResult> first = CompletableFuture.supplyAsync(
				() -> service.trainPositions(line4Query()),
				executor
			);
			assertThat(provider.trainPositionEntered.await(1, TimeUnit.SECONDS)).isTrue();
			CompletableFuture<RealtimeTrainPositionResult> second = CompletableFuture.supplyAsync(
				() -> service.trainPositions(line4Query()),
				executor
			);
			Thread.sleep(100);

			assertThat(provider.trainPositionCalls).hasValue(1);
			provider.releaseTrainPositions.countDown();

			assertThat(first.get(1, TimeUnit.SECONDS).status()).hasToString("FRESH");
			assertThat(second.get(1, TimeUnit.SECONDS).status()).hasToString("FRESH");
			assertThat(provider.trainPositionCalls).hasValue(1);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	@DisplayName("provider timeout은 cache가 있어도 payload 없는 unavailable로 종료한다")
	void timeoutReturnsUnavailableWithoutStaleCache() {
		MutableClock clock = new MutableClock(Instant.parse("2026-06-26T08:00:00Z"));
		CountingProvider provider = new CountingProvider();
		RealtimeGatewayService service = serviceWithAlwaysAvailableQuota(provider, clock);

		RealtimeArrivalResult initialArrivals = service.arrivals(sangnoksuQuery());
		RealtimeTrainPositionResult initialPositions = service.trainPositions(line4Query());

		assertThat(initialArrivals.status()).isEqualTo(RealtimeStatus.FRESH);
		assertThat(initialArrivals.arrivals()).isNotEmpty();
		assertThat(initialPositions.status()).isEqualTo(RealtimeStatus.FRESH);
		assertThat(initialPositions.trainPositions()).isNotEmpty();

		clock.instant = Instant.parse("2026-06-26T08:00:20.001Z");
		provider.failureCode = "PROVIDER_TIMEOUT";
		RealtimeArrivalResult unavailableArrivals = service.arrivals(sangnoksuQuery());
		RealtimeTrainPositionResult unavailablePositions = service.trainPositions(line4Query());

		assertThat(unavailableArrivals.status()).hasToString("UNAVAILABLE");
		assertThat(unavailableArrivals.fallbackCode()).isEqualTo("PROVIDER_TIMEOUT");
		assertThat(unavailableArrivals.arrivals()).isEmpty();
		assertThat(unavailablePositions.status()).hasToString("UNAVAILABLE");
		assertThat(unavailablePositions.fallbackCode()).isEqualTo("PROVIDER_TIMEOUT");
		assertThat(unavailablePositions.trainPositions()).isEmpty();
		assertThat(provider.arrivalCalls).hasValue(2);
		assertThat(provider.trainPositionCalls).hasValue(2);
		assertThat(service.providerHealthSnapshot().staleResultRatio()).isZero();
	}

	@Test
	@DisplayName("provider timestamp가 오래된 도착 정보는 fresh로 승격하지 않는다")
	void staleProviderTimestampDoesNotReturnFreshArrivals() {
		CountingProvider provider = new CountingProvider();
		provider.providerReceivedAt = "2026-06-26T07:58:00Z";
		RealtimeGatewayService service = service(
			provider,
			Clock.fixed(Instant.parse("2026-06-26T08:00:00Z"), ZoneOffset.UTC)
		);

		RealtimeArrivalResult result = service.arrivals(sangnoksuQuery());

		assertThat(result.status()).hasToString("UNAVAILABLE");
		assertThat(result.fallbackCode()).isEqualTo("PROVIDER_ERROR");
		assertThat(provider.arrivalCalls).hasValue(1);
	}

	@Test
	@DisplayName("provider timestamp 지연은 도착 ETA와 메시지에 반영한다")
	void providerTimestampDelayAdjustsArrivalEta() {
		CountingProvider provider = new CountingProvider();
		provider.providerReceivedAt = "2026-06-26T07:59:30Z";
		RealtimeGatewayService service = service(
			provider,
			Clock.fixed(Instant.parse("2026-06-26T08:00:00Z"), ZoneOffset.UTC)
		);

		RealtimeArrivalResult result = service.arrivals(sangnoksuQuery());

		assertThat(result.status()).hasToString("FRESH");
		assertThat(result.arrivals().getFirst().etaSeconds()).isEqualTo(150);
		assertThat(result.arrivals().getFirst().message()).isEqualTo("3분 후");
	}

	@Test
	@DisplayName("provider raw trip 표기는 canonical 값으로 변환하되 raw evidence도 보존한다")
	void arrivalTripMappingCanonicalizesDirectionAndPreservesRawEvidence() {
		CountingProvider provider = new CountingProvider();
		RealtimeGatewayService service = service(
			provider,
			Clock.fixed(Instant.parse("2026-06-26T08:00:00Z"), ZoneOffset.UTC)
		);

		RealtimeArrivalResult result = service.arrivals(sangnoksuQuery());

		assertThat(result.status()).hasToString("FRESH");
		RealtimeArrival arrival = result.arrivals().getFirst();
		assertThat(arrival.direction()).isEqualTo("당고개 방면");
		assertThat(arrival.destination()).isEqualTo("당고개");
		assertThat(arrival.rawDirection()).isEqualTo("상행");
		assertThat(arrival.rawDestination()).isEqualTo("당고개");
	}

	@Test
	@DisplayName("provider trip mapping 실패는 도착 row를 버리지 않고 metric으로 계측한다")
	void arrivalTripMappingMissIsCountedWithoutDroppingArrival() {
		CountingProvider provider = new CountingProvider();
		StubMappingPort mappingPort = new StubMappingPort();
		mappingPort.add(mapping("station-sangnoksu", "seoul-4", "1004", "1004000448", "상록수", true, true, "OFFICIAL"));
		RealtimeGatewayService service = service(
			provider,
			Clock.fixed(Instant.parse("2026-06-26T08:00:00Z"), ZoneOffset.UTC),
			mappingPort
		);

		RealtimeArrivalResult result = service.arrivals(sangnoksuQuery());
		RealtimeProviderHealthSnapshot snapshot = service.providerHealthSnapshot();

		assertThat(result.status()).hasToString("FRESH");
		assertThat(result.arrivals()).hasSize(1);
		assertThat(snapshot.tripMappingFailureCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("quota 초과 circuit은 만료 cache를 재사용하지 않고 unavailable로 종료한다")
	void quotaExhaustionOpensCircuit() {
		MutableClock clock = new MutableClock(Instant.parse("2026-06-26T08:00:00Z"));
		CountingProvider provider = new CountingProvider();
		RealtimeGatewayService service = service(provider, clock);
		RealtimeQuery arrivalQuery = sangnoksuQuery();
		RealtimeQuery trainPositionQuery = line4Query();

		assertThat(service.arrivals(arrivalQuery).status()).hasToString("FRESH");
		assertThat(service.trainPositions(trainPositionQuery).status()).hasToString("FRESH");
		clock.instant = Instant.parse("2026-06-26T08:00:30Z");
		provider.failureCode = "PROVIDER_QUOTA_EXCEEDED";

		RealtimeArrivalResult first = service.arrivals(arrivalQuery);
		RealtimeArrivalResult second = service.arrivals(arrivalQuery);
		RealtimeTrainPositionResult trainPositions = service.trainPositions(trainPositionQuery);

		assertThat(first.status()).hasToString("UNAVAILABLE");
		assertThat(first.fallbackCode()).isEqualTo("PROVIDER_QUOTA_EXCEEDED");
		assertThat(first.arrivals()).isEmpty();
		assertThat(second.status()).hasToString("UNAVAILABLE");
		assertThat(second.fallbackCode()).isEqualTo("PROVIDER_QUOTA_EXCEEDED");
		assertThat(second.arrivals()).isEmpty();
		assertThat(trainPositions.status()).hasToString("UNAVAILABLE");
		assertThat(trainPositions.fallbackCode()).isEqualTo("PROVIDER_QUOTA_EXCEEDED");
		assertThat(trainPositions.trainPositions()).isEmpty();
		assertThat(provider.arrivalCalls).hasValue(2);
		assertThat(provider.trainPositionCalls).hasValue(1);
	}

	@Test
	@DisplayName("quota 초과 응답 뒤 다른 캐시 키 요청도 circuit에 의해 원천 호출 없이 PROVIDER_QUOTA_EXCEEDED로 차단된다")
	void quotaExceededCircuitBlocksSubsequentRequestsAcrossCacheKeys() {
		MutableClock clock = new MutableClock(Instant.parse("2026-06-26T08:00:00Z"));
		CountingProvider provider = new CountingProvider();
		StubMappingPort mappingPort = new StubMappingPort();
		mappingPort.add(mapping("station-sangnoksu", "seoul-4", "1004", "1004000448", "상록수", true, true, "OFFICIAL"));
		mappingPort.add(mapping("station-euljiro-3ga", "seoul-3", "1003", "1003000329", "을지로3가", true, true, "OFFICIAL"));
		RealtimeGatewayService service = service(provider, clock, mappingPort);

		provider.failureCode = "PROVIDER_QUOTA_EXCEEDED";

		RealtimeArrivalResult first = service.arrivals(sangnoksuQuery());
		assertThat(first.status()).hasToString("UNAVAILABLE");
		assertThat(first.fallbackCode()).isEqualTo("PROVIDER_QUOTA_EXCEEDED");
		assertThat(provider.arrivalCalls).hasValue(1);

		clock.instant = clock.instant.plusSeconds(10);
		RealtimeArrivalResult second = service.arrivals(euljiro3gaLine3Query());
		assertThat(second.status()).hasToString("UNAVAILABLE");
		assertThat(second.fallbackCode()).isEqualTo("PROVIDER_QUOTA_EXCEEDED");
		assertThat(provider.arrivalCalls).hasValue(1);
	}

	@Test
	@DisplayName("provider fallback code는 allowlist 밖 값을 API/metric으로 노출하지 않는다")
	void providerFallbackCodeIsAllowlistedBeforeExposure() {
		CountingProvider provider = new CountingProvider();
		provider.failureCode = "PROVIDER_TIMEOUT serviceKey=raw-secret stationQueryName=상록수 trainNo=4123";
		RealtimeGatewayService service = service(
			provider,
			Clock.fixed(Instant.parse("2026-06-26T08:00:00Z"), ZoneOffset.UTC)
		);

		RealtimeArrivalResult result = service.arrivals(sangnoksuQuery());
		RealtimeProviderHealthSnapshot snapshot = service.providerHealthSnapshot();

		assertThat(result.status()).hasToString("UNAVAILABLE");
		assertThat(result.fallbackCode()).isEqualTo("PROVIDER_ERROR");
		assertThat(result.toString())
			.doesNotContain("raw-secret")
			.doesNotContain("상록수")
			.doesNotContain("4123");
		assertThat(snapshot.toString())
			.doesNotContain("raw-secret")
			.doesNotContain("상록수")
			.doesNotContain("4123");
	}



	@Test
	@DisplayName("열차 위치 provider fallback code도 allowlist 밖 값을 API/metric으로 노출하지 않는다")
	void trainPositionProviderFallbackCodeIsAllowlistedBeforeExposure() {
		CountingProvider provider = new CountingProvider();
		provider.failureCode = "PROVIDER_TIMEOUT serviceKey=raw-secret lineName=4호선 trainNo=4123";
		RealtimeGatewayService service = service(
			provider,
			Clock.fixed(Instant.parse("2026-06-26T08:00:00Z"), ZoneOffset.UTC)
		);

		RealtimeTrainPositionResult result = service.trainPositions(line4Query());
		RealtimeProviderHealthSnapshot snapshot = service.providerHealthSnapshot();

		assertThat(result.status()).hasToString("UNAVAILABLE");
		assertThat(result.fallbackCode()).isEqualTo("PROVIDER_ERROR");
		assertThat(result.toString())
			.doesNotContain("raw-secret")
			.doesNotContain("4호선")
			.doesNotContain("4123");
		assertThat(snapshot.toString())
			.doesNotContain("raw-secret")
			.doesNotContain("4호선")
			.doesNotContain("4123");
		assertThat(provider.trainPositionCalls).hasValue(1);
	}

	@Test
	@DisplayName("열차 위치 quota 초과도 circuit을 열고 다음 요청에서 provider를 호출하지 않는다")
	void trainPositionQuotaExhaustionOpensCircuit() {
		MutableClock clock = new MutableClock(Instant.parse("2026-06-26T08:00:00Z"));
		CountingProvider provider = new CountingProvider();
		RealtimeGatewayService service = service(provider, clock);
		RealtimeQuery query = line4Query();
		provider.failureCode = "PROVIDER_QUOTA_EXCEEDED";

		RealtimeTrainPositionResult first = service.trainPositions(query);
		RealtimeTrainPositionResult second = service.trainPositions(query);

		assertThat(first.status()).hasToString("UNAVAILABLE");
		assertThat(second.status()).hasToString("UNAVAILABLE");
		assertThat(second.fallbackCode()).isEqualTo("PROVIDER_QUOTA_EXCEEDED");
		assertThat(provider.trainPositionCalls).hasValue(1);
	}

	@Test
	@DisplayName("지원 범위 밖 역은 provider를 호출하지 않고 unsupported로 끝난다")
	void unsupportedSkipsProviderCall() {
		CountingProvider provider = new CountingProvider();
		RealtimeGatewayService service = service(
			provider,
			Clock.fixed(Instant.parse("2026-06-26T08:00:00Z"), ZoneOffset.UTC)
		);

		RealtimeArrivalResult result = service.arrivals(new RealtimeQuery(
			"station-outside",
			"other",
			null,
			"외부역",
			null
		));

		assertThat(result.status()).hasToString("UNSUPPORTED");
		assertThat(result.fallbackCode()).isEqualTo("MAPPING_MISSING");
		assertThat(provider.arrivalCalls).hasValue(0);
	}

	@Test
	@DisplayName("실시간 mapping이 없는 도착 요청은 provider를 호출하지 않고 MAPPING_MISSING으로 끝난다")
	void missingArrivalMappingSkipsProviderCall() {
		CountingProvider provider = new CountingProvider();
		StubMappingPort mappingPort = new StubMappingPort();
		RealtimeGatewayService service = service(
			provider,
			Clock.fixed(Instant.parse("2026-06-26T08:00:00Z"), ZoneOffset.UTC),
			mappingPort
		);

		RealtimeArrivalResult result = service.arrivals(new RealtimeQuery(
			"station-sadang",
			"seoul-4",
			null,
			"사당",
			null
		));

		assertThat(result.status()).hasToString("UNSUPPORTED");
		assertThat(result.fallbackCode()).isEqualTo("MAPPING_MISSING");
		assertThat(provider.arrivalCalls).hasValue(0);
	}

	@Test
	@DisplayName("도착 mapping이 arrivals를 지원하지 않으면 provider를 호출하지 않는다")
	void unsupportedArrivalCapabilitySkipsProviderCall() {
		CountingProvider provider = new CountingProvider();
		StubMappingPort mappingPort = new StubMappingPort();
		mappingPort.add(mapping("station-sadang", "seoul-4", "2004", "1004000433", "사당", false, true, "OFFICIAL"));
		RealtimeGatewayService service = service(
			provider,
			Clock.fixed(Instant.parse("2026-06-26T08:00:00Z"), ZoneOffset.UTC),
			mappingPort
		);

		RealtimeArrivalResult result = service.arrivals(new RealtimeQuery(
			"station-sadang",
			"seoul-4",
			null,
			"사당",
			null
		));

		assertThat(result.status()).hasToString("UNSUPPORTED");
		assertThat(result.fallbackCode()).isEqualTo("UNSUPPORTED_CAPABILITY");
		assertThat(provider.arrivalCalls).hasValue(0);
	}

	@Test
	@DisplayName("HEURISTIC/UNKNOWN 도착 mapping은 production live query에서 provider를 호출하지 않는다")
	void lowConfidenceArrivalMappingSkipsProviderCall() {
		CountingProvider provider = new CountingProvider();
		StubMappingPort mappingPort = new StubMappingPort();
		mappingPort.add(mapping("station-sadang", "seoul-4", "1004", "1004000433", "사당", true, true, "HEURISTIC"));
		RealtimeGatewayService service = service(
			provider,
			Clock.fixed(Instant.parse("2026-06-26T08:00:00Z"), ZoneOffset.UTC),
			mappingPort
		);

		RealtimeArrivalResult result = service.arrivals(new RealtimeQuery(
			"station-sadang",
			"seoul-4",
			null,
			"사당",
			null
		));

		assertThat(result.status()).hasToString("UNSUPPORTED");
		assertThat(result.fallbackCode()).isEqualTo("MAPPING_LOW_CONFIDENCE");
		assertThat(provider.arrivalCalls).hasValue(0);
	}

	@Test
	@DisplayName("도착 요청은 provider station query alias를 사용한다")
	void arrivalUsesProviderStationQueryAlias() {
		CapturingProvider provider = new CapturingProvider();
		StubMappingPort mappingPort = new StubMappingPort();
		mappingPort.add(mapping("station-sadang", "seoul-4", "1004", "1004000433", "사당역", true, true, "OFFICIAL"));
		RealtimeGatewayService service = service(
			provider,
			Clock.fixed(Instant.parse("2026-06-26T08:00:00Z"), ZoneOffset.UTC),
			mappingPort
		);

		RealtimeArrivalResult result = service.arrivals(new RealtimeQuery(
			"station-sadang",
			"seoul-4",
			null,
			"사당",
			null
		));

		assertThat(result.status()).hasToString("FRESH");
		assertThat(provider.lastArrivalQuery.stationQueryName()).isEqualTo("사당역");
		assertThat(provider.lastArrivalQuery.providerLineId()).isEqualTo("1004");
	}

	@Test
	@DisplayName("도착 요청은 TOPIS station code providerLineId alias를 유지한다")
	void arrivalAcceptsStationCodeProviderLineAlias() {
		CountingProvider provider = new CountingProvider();
		RealtimeGatewayService service = service(
			provider,
			Clock.fixed(Instant.parse("2026-06-26T08:00:00Z"), ZoneOffset.UTC)
		);

		RealtimeArrivalResult result = service.arrivals(new RealtimeQuery(
			"station-sangnoksu",
			"seoul-4",
			"448",
			"상록수",
			null
		));

		assertThat(result.status()).hasToString("FRESH");
		assertThat(provider.arrivalCalls).hasValue(1);
	}

	@Test
	@DisplayName("도착 요청은 legacy shorthand lineId를 유지한다")
	void arrivalAcceptsLegacyShorthandLineId() {
		CountingProvider provider = new CountingProvider();
		RealtimeGatewayService service = service(
			provider,
			Clock.fixed(Instant.parse("2026-06-26T08:00:00Z"), ZoneOffset.UTC)
		);

		RealtimeArrivalResult result = service.arrivals(new RealtimeQuery(
			"station-sangnoksu",
			"4",
			"448",
			"상록수",
			null
		));

		assertThat(result.status()).hasToString("FRESH");
		assertThat(provider.arrivalCalls).hasValue(1);
	}

	@Test
	@DisplayName("불일치한 provider line 도착 query는 provider를 호출하지 않고 mapping missing으로 끝난다")
	void mismatchedArrivalQuerySkipsProviderCall() {
		CountingProvider provider = new CountingProvider();
		RealtimeGatewayService service = service(
			provider,
			Clock.fixed(Instant.parse("2026-06-26T08:00:00Z"), ZoneOffset.UTC)
		);

		RealtimeArrivalResult result = service.arrivals(new RealtimeQuery(
			"station-sangnoksu",
			"seoul-4",
			"9999",
			"상록수",
			null
		));

		assertThat(result.status()).hasToString("UNSUPPORTED");
		assertThat(result.fallbackCode()).isEqualTo("MAPPING_MISSING");
		assertThat(provider.arrivalCalls).hasValue(0);
	}

	@Test
	@DisplayName("불일치한 provider line 열차 위치 query는 provider를 호출하지 않고 mapping missing으로 끝난다")
	void mismatchedTrainPositionQuerySkipsProviderCall() {
		CountingProvider provider = new CountingProvider();
		RealtimeGatewayService service = service(
			provider,
			Clock.fixed(Instant.parse("2026-06-26T08:00:00Z"), ZoneOffset.UTC)
		);

		RealtimeTrainPositionResult result = service.trainPositions(new RealtimeQuery(
			null,
			"seoul-4",
			"9999",
			null,
			"4호선"
		));

		assertThat(result.status()).hasToString("UNSUPPORTED");
		assertThat(result.fallbackCode()).isEqualTo("MAPPING_MISSING");
		assertThat(provider.trainPositionCalls).hasValue(0);
	}

	@Test
	@DisplayName("lineId 없는 열차 위치 요청은 provider line name으로 mapping을 찾는다")
	void trainPositionWithoutLineIdUsesProviderLineNameMapping() {
		CountingProvider provider = new CountingProvider();
		RealtimeGatewayService service = service(
			provider,
			Clock.fixed(Instant.parse("2026-06-26T08:00:00Z"), ZoneOffset.UTC)
		);

		RealtimeTrainPositionResult result = service.trainPositions(new RealtimeQuery(
			null,
			null,
			"1004",
			null,
			"4호선"
		));

		assertThat(result.status()).hasToString("FRESH");
		assertThat(provider.trainPositionCalls).hasValue(1);
	}

	@Test
	@DisplayName("열차 위치 요청은 legacy shorthand lineId를 유지한다")
	void trainPositionAcceptsLegacyShorthandLineId() {
		CountingProvider provider = new CountingProvider();
		RealtimeGatewayService service = service(
			provider,
			Clock.fixed(Instant.parse("2026-06-26T08:00:00Z"), ZoneOffset.UTC)
		);

		RealtimeTrainPositionResult result = service.trainPositions(new RealtimeQuery(
			null,
			"4",
			"1004",
			null,
			"4호선"
		));

		assertThat(result.status()).hasToString("FRESH");
		assertThat(provider.trainPositionCalls).hasValue(1);
	}

	@Test
	@DisplayName("provider empty 결과는 quota circuit을 열지 않는다")
	void emptyProviderResultDoesNotOpenQuotaCircuit() {
		MutableClock clock = new MutableClock(Instant.parse("2026-06-26T08:00:00Z"));
		CountingProvider provider = new CountingProvider();
		RealtimeGatewayService service = service(provider, clock);
		provider.emptyArrivals = true;

		RealtimeArrivalResult empty = service.arrivals(sangnoksuQuery());
		clock.instant = Instant.parse("2026-06-26T08:01:00Z");
		provider.providerReceivedAt = "2026-06-26T08:01:00Z";
		provider.emptyArrivals = false;
		RealtimeArrivalResult fresh = service.arrivals(sangnoksuQuery());

		assertThat(empty.status()).hasToString("UNAVAILABLE");
		assertThat(empty.fallbackCode()).isEqualTo("EMPTY_PROVIDER_RESULT");
		assertThat(fresh.status()).hasToString("FRESH");
		assertThat(provider.arrivalCalls).hasValue(2);
	}

	@Test
	@DisplayName("provider kill switch는 외부 호출을 막고 cache를 오염시키지 않는다")
	void providerKillSwitchSkipsProviderCallWithoutPoisoningCache() {
		CountingProvider provider = new CountingProvider();
		RealtimeProviderControl control = new RealtimeProviderControl();
		RealtimeGatewayService service = service(
			provider,
			Clock.fixed(Instant.parse("2026-06-26T08:00:00Z"), ZoneOffset.UTC),
			InMemoryRealtimeMappingPort.seededFixture(),
			control
		);

		control.disableProvider("seoul-topis", "MAINTENANCE");
		RealtimeArrivalResult disabled = service.arrivals(sangnoksuQuery());
		control.enableProvider("seoul-topis");
		RealtimeArrivalResult fresh = service.arrivals(sangnoksuQuery());

		assertThat(disabled.status()).hasToString("UNSUPPORTED");
		assertThat(disabled.fallbackCode()).isEqualTo("PROVIDER_DISABLED");
		assertThat(fresh.status()).hasToString("FRESH");
		assertThat(provider.arrivalCalls).hasValue(1);
	}

	@Test
	@DisplayName("provider health snapshot은 low-cardinality 집계만 노출한다")
	void providerHealthSnapshotExposesOnlySafeCounters() {
		MutableClock clock = new MutableClock(Instant.parse("2026-06-26T08:00:00Z"));
		CountingProvider provider = new CountingProvider();
		RealtimeGatewayService service = service(provider, clock);

		service.arrivals(sangnoksuQuery());
		clock.instant = Instant.parse("2026-06-26T08:01:31Z");
		provider.failureCode = "PROVIDER_TIMEOUT";
		service.arrivals(sangnoksuQuery());
		service.arrivals(new RealtimeQuery("station-outside", "other", null, "외부역", null));
		clock.instant = Instant.parse("2026-06-26T08:02:00Z");
		provider.failureCode = "PROVIDER_QUOTA_EXCEEDED";
		service.trainPositions(line4Query());

		RealtimeProviderHealthSnapshot snapshot = service.providerHealthSnapshot();

		assertThat(snapshot.providerId()).isEqualTo("seoul-topis");
		assertThat(snapshot.providerCallCount()).isEqualTo(3);
		assertThat(snapshot.providerTimeoutCount()).isEqualTo(1);
		assertThat(snapshot.providerQuotaExceededCount()).isEqualTo(1);
		assertThat(snapshot.freshResultRatio()).isPositive();
		assertThat(snapshot.staleResultRatio()).isZero();
		assertThat(snapshot.unsupportedRatio()).isPositive();
		assertThat(snapshot.toString())
			.doesNotContain("상록수")
			.doesNotContain("외부역")
			.doesNotContain("4123")
			.doesNotContain("1004");
	}

	@Test
	@DisplayName("provider health snapshot은 auth rejected 및 request rejected 카운트를 계측한다")
	void providerHealthSnapshotRecordsAuthAndRequestRejectedCounts() {
		MutableClock clock = new MutableClock(Instant.parse("2026-06-26T08:00:00Z"));
		CountingProvider provider = new CountingProvider();
		RealtimeGatewayService service = service(provider, clock);

		provider.failureCode = "PROVIDER_AUTH_REJECTED";
		RealtimeArrivalResult authRejected = service.arrivals(sangnoksuQuery());

		clock.instant = Instant.parse("2026-06-26T08:01:01Z");
		provider.failureCode = "PROVIDER_REQUEST_REJECTED";
		RealtimeTrainPositionResult requestRejected = service.trainPositions(line4Query());

		// 내부 원인은 공개 응답에서 PROVIDER_ERROR로만 나간다.
		assertThat(authRejected.status()).hasToString("UNAVAILABLE");
		assertThat(authRejected.fallbackCode()).isEqualTo("PROVIDER_ERROR");
		assertThat(requestRejected.status()).hasToString("UNAVAILABLE");
		assertThat(requestRejected.fallbackCode()).isEqualTo("PROVIDER_ERROR");

		RealtimeProviderHealthSnapshot snapshot = service.providerHealthSnapshot();
		assertThat(snapshot.providerAuthRejectedCount()).isEqualTo(1);
		assertThat(snapshot.providerRequestRejectedCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("이전에 반환된 시각보다 이른 열차 위치 이벤트는 결과에서 폐기되고 전부 폐기되면 unavailable로 닫는다")
	void trainPositionOlderThanPreviouslyServedIsDropped() {
		MutableClock clock = new MutableClock(Instant.parse("2026-06-26T08:01:00Z"));
		CountingProvider provider = new CountingProvider();
		RealtimeGatewayService service = serviceWithAlwaysAvailableQuota(provider, clock);

		provider.trainPositionsResponse = List.of(new RealtimeTrainPosition(
			"4", "상록수", "3101", "운행중", "상행", "당고개", "2026-06-26T08:00:50Z"
		));
		RealtimeTrainPositionResult first = service.trainPositions(line4Query());
		assertThat(first.status()).hasToString("FRESH");
		assertThat(first.trainPositions()).hasSize(1);

		clock.instant = Instant.parse("2026-06-26T08:01:25Z");
		provider.trainPositionsResponse = List.of(new RealtimeTrainPosition(
			"4", "상록수", "3101", "운행중", "상행", "당고개", "2026-06-26T08:00:40Z"
		));
		RealtimeTrainPositionResult second = service.trainPositions(line4Query());
		assertThat(second.status()).hasToString("UNAVAILABLE");
		assertThat(second.fallbackCode()).isEqualTo("PROVIDER_ERROR");
		assertThat(second.trainPositions()).isEmpty();
	}

	@Test
	@DisplayName("이전에 반환된 시각과 동일한 열차 위치 이벤트는 유지된다")
	void trainPositionWithSameReceivedAtIsKept() {
		MutableClock clock = new MutableClock(Instant.parse("2026-06-26T08:01:00Z"));
		CountingProvider provider = new CountingProvider();
		RealtimeGatewayService service = serviceWithAlwaysAvailableQuota(provider, clock);

		provider.trainPositionsResponse = List.of(new RealtimeTrainPosition(
			"4", "상록수", "3101", "운행중", "상행", "당고개", "2026-06-26T08:00:50Z"
		));
		RealtimeTrainPositionResult first = service.trainPositions(line4Query());
		assertThat(first.status()).hasToString("FRESH");
		assertThat(first.trainPositions()).hasSize(1);

		clock.instant = Instant.parse("2026-06-26T08:01:25Z");
		provider.trainPositionsResponse = List.of(new RealtimeTrainPosition(
			"4", "상록수", "3101", "운행중", "상행", "당고개", "2026-06-26T08:00:50Z"
		));
		RealtimeTrainPositionResult second = service.trainPositions(line4Query());
		assertThat(second.status()).hasToString("FRESH");
		assertThat(second.trainPositions()).hasSize(1);
	}

	@Test
	@DisplayName("한 응답 안에서 동일 열차 키가 중복될 경우 가장 늦은 providerReceivedAt 1건만 유지한다")
	void duplicateTrainInOneResponseKeepsLatest() {
		MutableClock clock = new MutableClock(Instant.parse("2026-06-26T08:01:00Z"));
		CountingProvider provider = new CountingProvider();
		RealtimeGatewayService service = serviceWithAlwaysAvailableQuota(provider, clock);

		provider.trainPositionsResponse = List.of(
			new RealtimeTrainPosition("4", "한대앞", "3101", "운행중", "상행", "당고개", "2026-06-26T08:00:40Z"),
			new RealtimeTrainPosition("4", "상록수", "3101", "운행중", "상행", "당고개", "2026-06-26T08:00:50Z")
		);
		RealtimeTrainPositionResult result = service.trainPositions(line4Query());
		assertThat(result.status()).hasToString("FRESH");
		assertThat(result.trainPositions()).hasSize(1);
		assertThat(result.trainPositions().getFirst().stationName()).isEqualTo("상록수");
		assertThat(result.trainPositions().getFirst().providerReceivedAt()).isEqualTo("2026-06-26T08:00:50Z");
	}

	@Test
	@DisplayName("한 응답 안에서 더 늦은 중복 건이 앞에 오고 이른 건이 뒤에 와도 가장 늦은 1건만 유지한다")
	void duplicateTrainWithLatestFirstKeepsLatest() {
		MutableClock clock = new MutableClock(Instant.parse("2026-06-26T08:01:00Z"));
		CountingProvider provider = new CountingProvider();
		RealtimeGatewayService service = serviceWithAlwaysAvailableQuota(provider, clock);

		provider.trainPositionsResponse = List.of(
			new RealtimeTrainPosition("4", "상록수", "3101", "운행중", "상행", "당고개", "2026-06-26T08:00:50Z"),
			new RealtimeTrainPosition("4", "한대앞", "3101", "운행중", "상행", "당고개", "2026-06-26T08:00:40Z")
		);
		RealtimeTrainPositionResult result = service.trainPositions(line4Query());

		assertThat(result.trainPositions()).hasSize(1);
		assertThat(result.trainPositions().getFirst().stationName()).isEqualTo("상록수");
		assertThat(result.trainPositions().getFirst().providerReceivedAt()).isEqualTo("2026-06-26T08:00:50Z");
	}

	@Test
	@DisplayName("providerReceivedAt을 해석할 수 없는 열차 위치는 추정 없이 결과에서 제외한다")
	void trainPositionWithUnparsableProviderReceivedAtIsDropped() {
		MutableClock clock = new MutableClock(Instant.parse("2026-06-26T08:01:00Z"));
		CountingProvider provider = new CountingProvider();
		RealtimeGatewayService service = serviceWithAlwaysAvailableQuota(provider, clock);

		provider.trainPositionsResponse = List.of(
			new RealtimeTrainPosition("4", "한대앞", "3101", "운행중", "상행", "당고개", "not-a-time"),
			new RealtimeTrainPosition("4", "중앙", "3102", "운행중", "상행", "당고개", null),
			new RealtimeTrainPosition("4", "고잔", "3103", "운행중", "상행", "당고개", " "),
			new RealtimeTrainPosition("4", "상록수", "3104", "운행중", "상행", "당고개", "2026-06-26T08:00:50Z")
		);
		RealtimeTrainPositionResult result = service.trainPositions(line4Query());

		assertThat(result.status()).hasToString("FRESH");
		assertThat(result.trainPositions()).hasSize(1);
		assertThat(result.trainPositions().getFirst().trainNo()).isEqualTo("3104");
	}

	@Test
	@DisplayName("lineId, trainNo, direction이 null인 열차 위치도 서로 다른 키로 충돌 없이 처리하고 같은 키만 중복 제거한다")
	void trainPositionsWithNullKeyPartsAreDeduplicatedPerKey() {
		MutableClock clock = new MutableClock(Instant.parse("2026-06-26T08:01:00Z"));
		CountingProvider provider = new CountingProvider();
		RealtimeGatewayService service = serviceWithAlwaysAvailableQuota(provider, clock);

		provider.trainPositionsResponse = List.of(
			new RealtimeTrainPosition(null, "한대앞", null, "운행중", null, "당고개", "2026-06-26T08:00:40Z"),
			new RealtimeTrainPosition(null, "상록수", null, "운행중", null, "당고개", "2026-06-26T08:00:50Z"),
			new RealtimeTrainPosition("4", "중앙", null, "운행중", "상행", "당고개", "2026-06-26T08:00:45Z"),
			new RealtimeTrainPosition("4", "고잔", "3101", "운행중", null, "당고개", "2026-06-26T08:00:46Z")
		);
		RealtimeTrainPositionResult result = service.trainPositions(line4Query());

		assertThat(result.trainPositions())
			.extracting(RealtimeTrainPosition::stationName)
			.containsExactlyInAnyOrder("상록수", "중앙", "고잔");
	}

	@Test
	@DisplayName("추적 키가 상한을 넘으면 가장 오래된 키부터 제거되어 그 키의 이른 시각 위치가 다시 허용된다")
	void lastServedStoreEvictsOldestKeysBeyondCapacity() {
		MutableClock clock = new MutableClock(Instant.parse("2026-06-26T08:00:30Z"));
		CountingProvider provider = new CountingProvider();
		RealtimeGatewayService service = serviceWithAlwaysAvailableQuota(provider, clock);

		// MAX_LAST_SERVED_POSITIONS(5000)를 1건 초과: 키 "T0"만 08:00:00으로 가장 오래됐다.
		List<RealtimeTrainPosition> initial = new java.util.ArrayList<>();
		initial.add(new RealtimeTrainPosition("4", "한대앞", "T0", "운행중", "상행", "당고개", "2026-06-26T08:00:00Z"));
		for (int i = 1; i <= 5000; i++) {
			initial.add(new RealtimeTrainPosition("4", "한대앞", "T" + i, "운행중", "상행", "당고개", "2026-06-26T08:00:10Z"));
		}
		provider.trainPositionsResponse = initial;
		assertThat(service.trainPositions(line4Query()).trainPositions()).hasSize(5001);

		// 캐시 TTL(20초) 경과 후 두 키가 각자 마지막 서빙 시각보다 이른 시각으로 재도착한다.
		// T0(서빙 08:00:00)은 07:59:58, T1(서빙 08:00:10)은 08:00:05. 상한 초과로 T0 기록이 제거됐을 때만 T0가 허용된다.
		clock.instant = Instant.parse("2026-06-26T08:00:55Z");
		provider.trainPositionsResponse = List.of(
			new RealtimeTrainPosition("4", "상록수", "T0", "운행중", "상행", "당고개", "2026-06-26T07:59:58Z"),
			new RealtimeTrainPosition("4", "상록수", "T1", "운행중", "상행", "당고개", "2026-06-26T08:00:05Z")
		);
		RealtimeTrainPositionResult replay = service.trainPositions(line4Query());

		assertThat(replay.trainPositions()).hasSize(1);
		assertThat(replay.trainPositions().getFirst().trainNo()).isEqualTo("T0");
		assertThat(service.providerHealthSnapshot().outOfOrderPositionDropCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("추적 키가 상한을 넘을 때 만료(신선도 TTL의 2배)된 키는 정리되고 살아 있는 키의 순서 보호는 유지된다")
	void lastServedStoreDropsExpiredKeysAndKeepsLiveKeys() {
		MutableClock clock = new MutableClock(Instant.parse("2026-06-26T08:00:30Z"));
		CountingProvider provider = new CountingProvider();
		RealtimeGatewayService service = serviceWithAlwaysAvailableQuota(provider, clock);

		// 08:00:00에 서빙된 키 5000건(상한 이내, 정리 없음).
		List<RealtimeTrainPosition> initial = new java.util.ArrayList<>();
		for (int i = 0; i < 5000; i++) {
			initial.add(new RealtimeTrainPosition("4", "한대앞", "E" + i, "운행중", "상행", "당고개", "2026-06-26T08:00:00Z"));
		}
		provider.trainPositionsResponse = initial;
		assertThat(service.trainPositions(line4Query()).trainPositions()).hasSize(5000);

		// 180초 초과 경과: 새 키 2건이 들어와 5002건이 되면 만료 키가 정리된다.
		clock.instant = Instant.parse("2026-06-26T08:03:10Z");
		provider.trainPositionsResponse = List.of(
			new RealtimeTrainPosition("4", "상록수", "L1", "운행중", "상행", "당고개", "2026-06-26T08:03:05Z"),
			new RealtimeTrainPosition("4", "중앙", "L2", "운행중", "상행", "당고개", "2026-06-26T08:03:06Z")
		);
		assertThat(service.trainPositions(line4Query()).trainPositions()).hasSize(2);

		// 정리 뒤에도 살아 있는 L1의 더 이른 시각 위치는 폐기되고 L2의 최신 위치는 유지된다.
		clock.instant = Instant.parse("2026-06-26T08:03:35Z");
		provider.trainPositionsResponse = List.of(
			new RealtimeTrainPosition("4", "고잔", "L1", "운행중", "상행", "당고개", "2026-06-26T08:03:00Z"),
			new RealtimeTrainPosition("4", "초지", "L2", "운행중", "상행", "당고개", "2026-06-26T08:03:20Z")
		);
		RealtimeTrainPositionResult after = service.trainPositions(line4Query());

		assertThat(after.trainPositions()).hasSize(1);
		assertThat(after.trainPositions().getFirst().stationName()).isEqualTo("초지");
		assertThat(service.providerHealthSnapshot().outOfOrderPositionDropCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("역전 폐기된 열차 위치 건수는 provider health snapshot의 outOfOrderPositionDropCount로 계측된다")
	void outOfOrderDropsAreCountedInHealthSnapshot() {
		MutableClock clock = new MutableClock(Instant.parse("2026-06-26T08:01:00Z"));
		CountingProvider provider = new CountingProvider();
		RealtimeGatewayService service = serviceWithAlwaysAvailableQuota(provider, clock);

		provider.trainPositionsResponse = List.of(new RealtimeTrainPosition(
			"4", "상록수", "3101", "운행중", "상행", "당고개", "2026-06-26T08:00:50Z"
		));
		service.trainPositions(line4Query());

		clock.instant = Instant.parse("2026-06-26T08:01:25Z");
		provider.trainPositionsResponse = List.of(new RealtimeTrainPosition(
			"4", "상록수", "3101", "운행중", "상행", "당고개", "2026-06-26T08:00:40Z"
		));
		service.trainPositions(line4Query());

		RealtimeProviderHealthSnapshot snapshot = service.providerHealthSnapshot();
		assertThat(snapshot.outOfOrderPositionDropCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("역전 폐기된 열차 위치 건수는 Micrometer 카운터 easysubway.realtime.positions.out_of_order.dropped로 노출된다")
	void outOfOrderDropsIncrementMicrometerCounter() {
		MutableClock clock = new MutableClock(Instant.parse("2026-06-26T08:01:00Z"));
		CountingProvider provider = new CountingProvider();
		SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
		RealtimeGatewayService service = new RealtimeGatewayService(
			provider,
			clock,
			InMemoryRealtimeMappingPort.seededFixture(),
			new RealtimeProviderControl(),
			RealtimeArrivalArchivePort.NO_OP,
			Runnable::run,
			meterRegistry
		);

		provider.trainPositionsResponse = List.of(new RealtimeTrainPosition(
			"4", "상록수", "3101", "운행중", "상행", "당고개", "2026-06-26T08:00:50Z"
		));
		service.trainPositions(line4Query());
		assertThat(meterRegistry.get("easysubway.realtime.positions.out_of_order.dropped").counter().count())
			.isEqualTo(0.0);

		clock.instant = Instant.parse("2026-06-26T08:01:25Z");
		provider.trainPositionsResponse = List.of(new RealtimeTrainPosition(
			"4", "상록수", "3101", "운행중", "상행", "당고개", "2026-06-26T08:00:40Z"
		));
		service.trainPositions(line4Query());

		assertThat(meterRegistry.get("easysubway.realtime.positions.out_of_order.dropped").counter().count())
			.isEqualTo(1.0);
	}

	@Test
	@DisplayName("공공 API 자체 호출 한도가 제거되어 여러 역 동시 요청이 제한 없이 처리된다")
	void multipleConcurrentRequestsAreProcessedWithoutInternalRateLimit() {
		MutableClock clock = new MutableClock(Instant.parse("2026-06-26T08:00:00Z"));
		CountingProvider provider = new CountingProvider();
		StubMappingPort mappingPort = new StubMappingPort();
		for (int index = 0; index < 5; index += 1) {
			mappingPort.add(mapping(
				"station-%02d".formatted(index),
				"seoul-4",
				"1004",
				"10040004%02d".formatted(index),
				"상록수%02d".formatted(index),
				true,
				true,
				"OFFICIAL"
			));
		}
		RealtimeGatewayService service = service(provider, clock, mappingPort);

		for (int index = 0; index < 5; index += 1) {
			RealtimeArrivalResult result = service.arrivals(new RealtimeQuery(
				"station-%02d".formatted(index),
				"seoul-4",
				"1004",
				"상록수%02d".formatted(index),
				"4호선"
			));
			assertThat(result.status()).hasToString("FRESH");
		}
		assertThat(provider.arrivalCalls.get()).isEqualTo(5);

		for (int index = 0; index < 5; index += 1) {
			clock.instant = clock.instant.plusSeconds(21);
			provider.providerReceivedAt = clock.instant.toString();
			RealtimeTrainPositionResult result = service.trainPositions(line4Query());
			assertThat(result.status()).hasToString("FRESH");
		}
		assertThat(provider.trainPositionCalls.get()).isEqualTo(5);
	}

	@Test
	@DisplayName("TOPIS provider는 backend service key가 없으면 unavailable로 낮춘다")
	void topisProviderWithoutBackendServiceKeyIsUnavailableByDefault() {
		TimeoutHttpClient httpClient = new TimeoutHttpClient();
		TopisRealtimeProvider provider = new TopisRealtimeProvider(
			"",
			new ObjectMapper(),
			httpClient
		);

		assertThatThrownBy(() -> provider.arrivals(sangnoksuQuery()))
			.isInstanceOf(RealtimeProviderException.class)
			.hasMessage("PROVIDER_UNAVAILABLE");
		assertThatThrownBy(() -> provider.trainPositions(line4Query()))
			.isInstanceOf(RealtimeProviderException.class)
			.hasMessage("PROVIDER_UNAVAILABLE");
		assertThat(httpClient.sendCalls).hasValue(0);
		assertThat(TopisRealtimeProvider.class.getDeclaredConstructors())
			.allSatisfy(constructor -> assertThat(constructor.getParameterTypes())
				.doesNotContain(RealtimeProvider.class, boolean.class));
	}

	@Test
	@DisplayName("TOPIS provider timeout은 realtime contract 값과 일치한다")
	void topisProviderTimeoutMatchesContract() throws Exception {
		java.lang.reflect.Field timeoutField = TopisRealtimeProvider.class.getDeclaredField("REQUEST_TIMEOUT");
		timeoutField.setAccessible(true);

		assertThat(timeoutField.get(null)).isEqualTo(Duration.ofMillis(1500));
	}

	@Test
	@DisplayName("TOPIS provider timeout 예외는 realtime timeout fallback 코드로 변환한다")
	void topisProviderMapsHttpTimeoutToProviderTimeout() {
		TimeoutHttpClient httpClient = new TimeoutHttpClient();
		TopisRealtimeProvider provider = new TopisRealtimeProvider(
			"backend-key",
			new ObjectMapper(),
			httpClient
		);

		assertThatThrownBy(() -> provider.arrivals(sangnoksuQuery()))
			.isInstanceOf(RealtimeProviderException.class)
			.hasMessage("PROVIDER_TIMEOUT");
		assertThat(httpClient.sendCalls).hasValue(1);
	}

	@Test
	@DisplayName("TOPIS INFO-200 empty result는 quota exception으로 처리하지 않는다")
	void topisInfo200DoesNotOpenQuotaCircuit() throws Exception {
		ObjectMapper objectMapper = new ObjectMapper();
		TopisRealtimeProvider provider = new TopisRealtimeProvider(
			"backend-key",
			objectMapper,
			java.net.http.HttpClient.newHttpClient()
		);

		provider.validateTopisStatus(objectMapper.readTree("""
			{
			  "errorMessage": {"code": "INFO-200", "message": "해당하는 데이터가 없습니다."}
			}
			"""));
	}

	@Test
	@DisplayName("TOPIS 도착 payload는 bstatnNm이 없으면 trainLineNm을 목적지 fallback으로 사용한다")
	void topisArrivalPayloadUsesTrainLineNameWhenDestinationNameIsMissing() throws Exception {
		ObjectMapper objectMapper = new ObjectMapper();
		TopisRealtimeProvider provider = new TopisRealtimeProvider(
			"backend-key",
			objectMapper,
			java.net.http.HttpClient.newHttpClient()
		);

		List<RealtimeArrival> arrivals = provider.arrivalsFromPayload(
			objectMapper.readTree("""
				{
				  "errorMessage": {"code": "INFO-000"},
				  "realtimeArrivalList": [
				    {
				      "subwayId": "1004",
				      "statnNm": "상록수",
				      "trainLineNm": "오이도행 - 중앙방면",
				      "updnLine": "하행",
				      "btrainNo": "4001",
				      "btrainSttus": "급행",
				      "barvlDt": "180",
				      "arvlMsg2": "3분 후"
				    }
				  ]
				}
				""")
		);

		assertThat(arrivals).hasSize(1);
		assertThat(arrivals.getFirst().destination()).isEqualTo("오이도행 - 중앙방면");
		assertThat(arrivals.getFirst().servicePattern()).isEqualTo("급행");
	}

	@Test
	@DisplayName("TOPIS barvlDt가 0이고 arvlMsg2가 전역 출발이면 etaSeconds는 null이고 메시지가 보존된다")
	void topisZeroBarvlDtWithDescriptiveMessageLeavesEtaNullAndPreservesMessage() throws Exception {
		ObjectMapper objectMapper = new ObjectMapper();
		TopisRealtimeProvider provider = new TopisRealtimeProvider(
			"backend-key",
			objectMapper,
			java.net.http.HttpClient.newHttpClient()
		);

		List<RealtimeArrival> arrivals = provider.arrivalsFromPayload(
			objectMapper.readTree("""
				{
				  "errorMessage": {"code": "INFO-000"},
				  "realtimeArrivalList": [
				    {
				      "subwayId": "1004",
				      "statnNm": "상록수",
				      "trainLineNm": "당고개행 - 반월방면",
				      "updnLine": "상행",
				      "btrainNo": "4002",
				      "barvlDt": "0",
				      "arvlMsg2": "전역 출발",
				      "recptnDt": "2026-06-26 17:00:00"
				    }
				  ]
				}
				""")
		);

		assertThat(arrivals).hasSize(1);
		assertThat(arrivals.getFirst().etaSeconds()).isNull();
		assertThat(arrivals.getFirst().message()).isEqualTo("전역 출발");

		// Gateway adjustArrivalEta도 etaSeconds==null이면 "곧 도착"으로 덮어쓰지 않고 메시지를 보존한다.
		MutableClock clock = new MutableClock(Instant.parse("2026-06-26T08:00:00Z"));
		RealtimeGatewayService service = service(q -> arrivals, clock);
		RealtimeArrivalResult result = service.arrivals(sangnoksuQuery());
		assertThat(result.arrivals()).hasSize(1);
		assertThat(result.arrivals().getFirst().etaSeconds()).isNull();
		assertThat(result.arrivals().getFirst().message()).isEqualTo("전역 출발");
	}

	@Test
	@DisplayName("TOPIS barvlDt가 0이어도 arvlMsg2에 분/초 패턴이 있으면 etaSeconds를 파싱한다")
	void topisZeroBarvlDtWithMinuteSecondsMessageParsesEtaSeconds() throws Exception {
		ObjectMapper objectMapper = new ObjectMapper();
		TopisRealtimeProvider provider = new TopisRealtimeProvider(
			"backend-key",
			objectMapper,
			java.net.http.HttpClient.newHttpClient()
		);

		List<RealtimeArrival> arrivals = provider.arrivalsFromPayload(
			objectMapper.readTree("""
				{
				  "errorMessage": {"code": "INFO-000"},
				  "realtimeArrivalList": [
				    {
				      "subwayId": "1004",
				      "statnNm": "상록수",
				      "trainLineNm": "오이도행",
				      "updnLine": "하행",
				      "btrainNo": "4003",
				      "barvlDt": "0",
				      "arvlMsg2": "3분 20초 후 (상록수)"
				    }
				  ]
				}
				""")
		);

		assertThat(arrivals).hasSize(1);
		assertThat(arrivals.getFirst().etaSeconds()).isEqualTo(200);
		assertThat(arrivals.getFirst().message()).isEqualTo("3분 20초 후 (상록수)");
	}

	@Test
	@DisplayName("parseEtaFromMessage는 다양한 한국어 시간 형식을 초로 파싱한다")
	void parseEtaFromMessageParsesKoreanTimeFormats() {
		assertThat(TopisRealtimeProvider.parseEtaFromMessage("3분 20초 후 (상록수)")).isEqualTo(200);
		assertThat(TopisRealtimeProvider.parseEtaFromMessage("4분 후")).isEqualTo(240);
		assertThat(TopisRealtimeProvider.parseEtaFromMessage("45초 후")).isEqualTo(45);
		assertThat(TopisRealtimeProvider.parseEtaFromMessage("1분")).isEqualTo(60);
		assertThat(TopisRealtimeProvider.parseEtaFromMessage("전역 출발")).isNull();
		assertThat(TopisRealtimeProvider.parseEtaFromMessage("[2]번째 전역")).isNull();
		assertThat(TopisRealtimeProvider.parseEtaFromMessage("당역 진입")).isNull();
		assertThat(TopisRealtimeProvider.parseEtaFromMessage("")).isNull();
		assertThat(TopisRealtimeProvider.parseEtaFromMessage(null)).isNull();
		assertThat(TopisRealtimeProvider.parseEtaFromMessage("0분 0초 후")).isNull();
		assertThat(TopisRealtimeProvider.parseEtaFromMessage("0초 후")).isNull();
		assertThat(TopisRealtimeProvider.parseEtaFromMessage("0분 후")).isNull();
	}

	@Test
	@DisplayName("positiveInt는 숫자, 문자열 숫자, 음수/0, 잘못된 포맷 및 누락 필드를 적절히 처리한다")
	void positiveIntHandlesAllNodeTypesAndBoundaries() {
		ObjectMapper mapper = new ObjectMapper();
		var intNode = mapper.createObjectNode().put("barvlDt", 120);
		assertThat(TopisRealtimeProvider.positiveInt(intNode, "barvlDt")).isEqualTo(120);

		var zeroIntNode = mapper.createObjectNode().put("barvlDt", 0);
		assertThat(TopisRealtimeProvider.positiveInt(zeroIntNode, "barvlDt")).isNull();

		var negativeIntNode = mapper.createObjectNode().put("barvlDt", -5);
		assertThat(TopisRealtimeProvider.positiveInt(negativeIntNode, "barvlDt")).isNull();

		var textNode = mapper.createObjectNode().put("barvlDt", " 180 ");
		assertThat(TopisRealtimeProvider.positiveInt(textNode, "barvlDt")).isEqualTo(180);

		var zeroTextNode = mapper.createObjectNode().put("barvlDt", "0");
		assertThat(TopisRealtimeProvider.positiveInt(zeroTextNode, "barvlDt")).isNull();

		var negativeTextNode = mapper.createObjectNode().put("barvlDt", "-10");
		assertThat(TopisRealtimeProvider.positiveInt(negativeTextNode, "barvlDt")).isNull();

		var invalidTextNode = mapper.createObjectNode().put("barvlDt", "not-a-number");
		assertThat(TopisRealtimeProvider.positiveInt(invalidTextNode, "barvlDt")).isNull();

		var booleanNode = mapper.createObjectNode().put("barvlDt", true);
		assertThat(TopisRealtimeProvider.positiveInt(booleanNode, "barvlDt")).isNull();

		var missingNode = mapper.createObjectNode();
		assertThat(TopisRealtimeProvider.positiveInt(missingNode, "barvlDt")).isNull();
	}

	private RealtimeQuery sangnoksuQuery() {
		return new RealtimeQuery("station-sangnoksu", "seoul-4", "1004", "상록수", null);
	}

	private RealtimeQuery line4Query() {
		return new RealtimeQuery(null, "seoul-4", "1004", null, "4호선");
	}

	private RealtimeQuery euljiro3gaLine3Query() {
		return new RealtimeQuery("station-euljiro-3ga", "seoul-3", "1003", "을지로3가", null);
	}

	private StubMappingPort euljiro3gaLine3MappingPort() {
		StubMappingPort mappingPort = new StubMappingPort();
		mappingPort.add(new RealtimeMapping(
			"seoul-topis",
			"station-euljiro-3ga",
			"seoul-3",
			"1003",
			"1003000322",
			"을지로3가",
			"3호선",
			true,
			true,
			"OFFICIAL",
			1L
		));
		return mappingPort;
	}

	private RealtimeArrival euljiro3gaArrival(
		String providerLineId,
		String trainNo,
		String destination,
		String direction,
		int etaSeconds
	) {
		return new RealtimeArrival(
			providerLineId,
			"을지로3가",
			destination,
			direction,
			trainNo,
			etaSeconds,
			"%d분 후".formatted(etaSeconds / 60),
			"전역 출발",
			"2026-06-26T08:00:00Z"
		);
	}

	private RealtimeGatewayService service(RealtimeProvider provider, Clock clock) {
		return service(provider, clock, InMemoryRealtimeMappingPort.seededFixture());
	}

	private RealtimeGatewayService serviceWithAlwaysAvailableQuota(RealtimeProvider provider, Clock clock) {
		return service(provider, clock);
	}

	private RealtimeGatewayService service(RealtimeProvider provider, Clock clock, RealtimeMappingPort mappingPort) {
		return new RealtimeGatewayService(provider, clock, mappingPort);
	}

	private RealtimeGatewayService service(
		RealtimeProvider provider,
		Clock clock,
		RealtimeMappingPort mappingPort,
		RealtimeArrivalArchivePort archivePort
	) {
		return new RealtimeGatewayService(provider, clock, mappingPort, archivePort);
	}

	private RealtimeGatewayService service(
		RealtimeProvider provider,
		Clock clock,
		RealtimeMappingPort mappingPort,
		RealtimeProviderControl control
	) {
		return new RealtimeGatewayService(provider, clock, mappingPort, control);
	}

	private RealtimeMapping mapping(
		String stationId,
		String lineId,
		String providerLineId,
		String providerStationId,
		String queryName,
		boolean supportsArrivals,
		boolean supportsTrainPositions,
		String mappingConfidence
	) {
		return new RealtimeMapping(
			"seoul-topis",
			stationId,
			lineId,
			providerLineId,
			providerStationId,
			queryName,
			"4호선",
			supportsArrivals,
			supportsTrainPositions,
			mappingConfidence,
			1L
		);
	}

	private static final class StubMappingPort implements RealtimeMappingPort {
		private final Map<String, RealtimeMapping> mappings = new HashMap<>();
		private final Map<String, RealtimeTripMapping> tripMappings = new HashMap<>();

		private void add(RealtimeMapping mapping) {
			mappings.put(arrivalKey(mapping.stationId(), mapping.lineId()), mapping);
			mappings.put(lineKey(mapping.lineId()), mapping);
		}

		@Override
		public Optional<RealtimeMapping> findArrivalMapping(String providerId, RealtimeQuery query) {
			return Optional.ofNullable(mappings.get(arrivalKey(query.stationId(), query.lineId())))
				.filter((mapping) -> providerId.equals(mapping.providerId()));
		}

		@Override
		public Optional<RealtimeMapping> findTrainPositionMapping(String providerId, RealtimeQuery query) {
			return Optional.ofNullable(mappings.get(lineKey(query.lineId())))
				.filter((mapping) -> providerId.equals(mapping.providerId()));
		}

		@Override
		public Optional<RealtimeTripMapping> findTripMapping(
			String providerId,
			String lineId,
			String providerLineId,
			String rawDirection,
			String rawDestination,
			String rawServicePattern
		) {
			return Optional.ofNullable(tripMappings.get("%s:%s:%s".formatted(lineId, rawDirection, rawDestination)))
				.filter((mapping) -> providerId.equals(mapping.providerId()));
		}

		private static String arrivalKey(String stationId, String lineId) {
			return "%s:%s".formatted(stationId, lineId);
		}

		private static String lineKey(String lineId) {
			return lineId;
		}
	}

	private static final class CapturingProvider extends CountingProvider {
		private RealtimeQuery lastArrivalQuery;

		@Override
		public List<RealtimeArrival> arrivals(RealtimeQuery query) {
			lastArrivalQuery = query;
			return super.arrivals(query);
		}
	}

	private static class CountingProvider implements RealtimeProvider {
		private final AtomicInteger arrivalCalls = new AtomicInteger();
		private final AtomicInteger trainPositionCalls = new AtomicInteger();
		private String failureCode;
		private boolean emptyArrivals;
		private String providerReceivedAt = "2026-06-26T08:00:00Z";
		private List<RealtimeTrainPosition> trainPositionsResponse;

		@Override
		public List<RealtimeArrival> arrivals(RealtimeQuery query) {
			arrivalCalls.incrementAndGet();
			if (failureCode != null) {
				throw new RealtimeProviderException(failureCode);
			}
			if (emptyArrivals) {
				return List.of();
			}
			return List.of(new RealtimeArrival(
				"1004",
				"상록수",
				"당고개",
				"상행",
				"4123",
				180,
				"3분 후",
				"전역 출발",
				providerReceivedAt
			));
		}

		@Override
		public List<RealtimeTrainPosition> trainPositions(RealtimeQuery query) {
			trainPositionCalls.incrementAndGet();
			if (failureCode != null) {
				throw new RealtimeProviderException(failureCode);
			}
			if (trainPositionsResponse != null) {
				return trainPositionsResponse;
			}
			return List.of(new RealtimeTrainPosition(
				"4",
				"상록수",
				"4123",
				"운행중",
				"상행",
				"당고개",
				providerReceivedAt
			));
		}
	}

	private static final class CapturingArrivalArchive implements RealtimeArrivalArchivePort {
		private final AtomicInteger saveCalls = new AtomicInteger();
		private List<RealtimeArrivalObservation> observations = List.of();

		@Override
		public void saveAll(List<RealtimeArrivalObservation> observations) {
			saveCalls.incrementAndGet();
			this.observations = List.copyOf(observations);
		}

		@Override
		public int deleteExpired(Instant now) {
			return 0;
		}
	}

	private static final class CapturingExecutor implements Executor {
		private Runnable pending;

		@Override
		public void execute(Runnable command) {
			pending = command;
		}

		private void runPending() {
			assertThat(pending).isNotNull();
			pending.run();
		}
	}

	private static final class BlockingProvider implements RealtimeProvider {
		private final AtomicInteger arrivalCalls = new AtomicInteger();
		private final AtomicInteger trainPositionCalls = new AtomicInteger();
		private final CountDownLatch arrivalEntered = new CountDownLatch(1);
		private final CountDownLatch trainPositionEntered = new CountDownLatch(1);
		private final CountDownLatch releaseArrivals = new CountDownLatch(1);
		private final CountDownLatch releaseTrainPositions = new CountDownLatch(1);

		@Override
		public List<RealtimeArrival> arrivals(RealtimeQuery query) {
			arrivalCalls.incrementAndGet();
			arrivalEntered.countDown();
			awaitRelease(releaseArrivals);
			return List.of(new RealtimeArrival(
				"1004",
				"상록수",
				"당고개",
				"상행",
				"4123",
				180,
				"3분 후",
				"전역 출발",
				"2026-06-26T08:00:00Z"
			));
		}

		@Override
		public List<RealtimeTrainPosition> trainPositions(RealtimeQuery query) {
			trainPositionCalls.incrementAndGet();
			trainPositionEntered.countDown();
			awaitRelease(releaseTrainPositions);
			return List.of(new RealtimeTrainPosition(
				"4",
				"상록수",
				"4123",
				"운행중",
				"상행",
				"당고개",
				"2026-06-26T08:00:00Z"
			));
		}

		private void awaitRelease(CountDownLatch latch) {
			try {
				if (!latch.await(1, TimeUnit.SECONDS)) {
					throw new IllegalStateException("Provider release latch timed out.");
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Provider wait interrupted.", exception);
			}
		}
	}

	private static final class TimeoutHttpClient extends java.net.http.HttpClient {
		private final AtomicInteger sendCalls = new AtomicInteger();
		@Override
		public Optional<java.net.CookieHandler> cookieHandler() {
			return Optional.empty();
		}

		@Override
		public Optional<Duration> connectTimeout() {
			return Optional.empty();
		}

		@Override
		public Redirect followRedirects() {
			return Redirect.NEVER;
		}

		@Override
		public Optional<java.net.ProxySelector> proxy() {
			return Optional.empty();
		}

		@Override
		public javax.net.ssl.SSLContext sslContext() {
			return null;
		}

		@Override
		public javax.net.ssl.SSLParameters sslParameters() {
			return null;
		}

		@Override
		public Optional<java.net.Authenticator> authenticator() {
			return Optional.empty();
		}

		@Override
		public Version version() {
			return Version.HTTP_2;
		}

		@Override
		public Optional<java.util.concurrent.Executor> executor() {
			return Optional.empty();
		}

		@Override
		public <T> java.net.http.HttpResponse<T> send(
			java.net.http.HttpRequest request,
			java.net.http.HttpResponse.BodyHandler<T> responseBodyHandler
		) throws IOException {
			sendCalls.incrementAndGet();
			throw new java.net.http.HttpTimeoutException("timeout");
		}

		@Override
		public <T> CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(
			java.net.http.HttpRequest request,
			java.net.http.HttpResponse.BodyHandler<T> responseBodyHandler
		) {
			return CompletableFuture.failedFuture(new java.net.http.HttpTimeoutException("timeout"));
		}

		@Override
		public <T> CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(
			java.net.http.HttpRequest request,
			java.net.http.HttpResponse.BodyHandler<T> responseBodyHandler,
			java.net.http.HttpResponse.PushPromiseHandler<T> pushPromiseHandler
		) {
			return CompletableFuture.failedFuture(new java.net.http.HttpTimeoutException("timeout"));
		}
	}

	private static final class MutableClock extends Clock {
		private Instant instant;

		private MutableClock(Instant instant) {
			this.instant = instant;
		}

		@Override
		public ZoneOffset getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return instant;
		}
	}
}

package com.easysubway.realtime.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.realtime.adapter.out.persistence.InMemoryRealtimeMappingPort;
import com.easysubway.realtime.application.port.out.RealtimeArrivalArchivePort;
import com.easysubway.realtime.application.port.out.RealtimeMappingPort;
import com.easysubway.realtime.application.port.out.RealtimeProviderCallQuotaPort;
import com.easysubway.realtime.domain.RealtimeArrivalObservation;
import com.easysubway.realtime.domain.RealtimeMapping;
import com.easysubway.realtime.domain.RealtimeArrival;
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
	@DisplayName("Spring constructor는 archive와 shared quota 포트를 필수 의존성으로 받는다")
	void springConstructorRequiresProductionSafetyPorts() {
		var parameterTypes = Arrays.stream(RealtimeGatewayService.class.getConstructors())
			.filter(constructor -> constructor.isAnnotationPresent(Autowired.class))
			.findFirst()
			.map(constructor -> List.of(constructor.getParameterTypes()))
			.orElseThrow();

		assertThat(parameterTypes)
			.contains(RealtimeArrivalArchivePort.class, RealtimeProviderCallQuotaPort.class)
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
			"seoul-4",
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
			(providerId, now, zone, perMinute, perDay) -> true,
			1,
			800,
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
			(providerId, now, zone, perMinute, perDay) -> true,
			1,
			800,
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
		RealtimeGatewayService service = service(
			new CountingProvider(),
			Clock.fixed(Instant.parse("2026-06-26T08:00:00Z"), ZoneOffset.UTC),
			InMemoryRealtimeMappingPort.seededFixture(),
			failingArchive
		);

		RealtimeArrivalResult result = service.arrivals(sangnoksuQuery());

		assertThat(result.status()).hasToString("FRESH");
		assertThat(service.providerHealthSnapshot().archiveFailureCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("archive 관측 생성 실패는 fresh 응답과 cache를 막지 않는다")
	void archiveObservationFailureDoesNotBreakFreshResponse() {
		RealtimeProvider provider = query -> List.of(new RealtimeArrival(
			"4", "상록수", "당고개", "상행", "", 180, "3분 후", "전역 출발", "2026-06-26T08:00:00Z"
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
	@DisplayName("quota store 장애는 provider를 호출하지 않고 unavailable로 닫는다")
	void quotaStoreFailureFailsClosedWithoutProviderCall() {
		CountingProvider provider = new CountingProvider();
		RealtimeProviderCallQuotaPort failingQuota = (providerId, now, zone, perMinute, perDay) -> {
			throw new IllegalStateException("quota store unavailable");
		};
		RealtimeGatewayService service = new RealtimeGatewayService(
			provider,
			Clock.fixed(Instant.parse("2026-06-26T08:00:00Z"), ZoneOffset.UTC),
			InMemoryRealtimeMappingPort.seededFixture(),
			new RealtimeProviderControl(),
			RealtimeArrivalArchivePort.NO_OP,
			failingQuota,
			1,
			800
		);

		RealtimeArrivalResult arrivals = service.arrivals(sangnoksuQuery());
		RealtimeTrainPositionResult positions = service.trainPositions(line4Query());

		assertThat(arrivals.status()).hasToString("UNAVAILABLE");
		assertThat(arrivals.fallbackCode()).isEqualTo("PROVIDER_UNAVAILABLE");
		assertThat(positions.status()).hasToString("UNAVAILABLE");
		assertThat(positions.fallbackCode()).isEqualTo("PROVIDER_UNAVAILABLE");
		assertThat(provider.arrivalCalls).hasValue(0);
		assertThat(provider.trainPositionCalls).hasValue(0);
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
		RealtimeGatewayService service = service(provider, clock);
		RealtimeQuery query = sangnoksuQuery();

		service.arrivals(query);
		clock.instant = Instant.parse("2026-06-26T08:01:31Z");
		provider.failureCode = "PROVIDER_TIMEOUT";
		RealtimeArrivalResult unavailable = service.arrivals(query);

		assertThat(unavailable.status()).hasToString("UNAVAILABLE");
		assertThat(unavailable.fallbackCode()).isEqualTo("PROVIDER_TIMEOUT");
		assertThat(unavailable.arrivals()).isEmpty();
		assertThat(provider.arrivalCalls).hasValue(2);
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
		RealtimeGatewayService service = new RealtimeGatewayService(
			provider,
			clock,
			InMemoryRealtimeMappingPort.seededFixture(),
			new RealtimeProviderControl(),
			RealtimeArrivalArchivePort.NO_OP,
			(providerId, now, zone, perMinute, perDay) -> true,
			1,
			800
		);
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
	@DisplayName("provider call rate limit은 cache가 있어도 payload 없는 unavailable로 종료한다")
	void providerRateLimitReturnsUnavailableWithoutStaleCache() {
		MutableClock clock = new MutableClock(Instant.parse("2026-06-26T08:00:00Z"));
		CountingProvider provider = new CountingProvider();
		RealtimeGatewayService service = service(
			provider,
			clock,
			InMemoryRealtimeMappingPort.seededFixture(),
			new RealtimeProviderControl(),
			1
		);
		RealtimeQuery query = sangnoksuQuery();

		RealtimeArrivalResult first = service.arrivals(query);
		clock.instant = Instant.parse("2026-06-26T08:00:30Z");
		provider.providerReceivedAt = "2026-06-26T08:00:30Z";
		RealtimeArrivalResult limited = service.arrivals(query);

		assertThat(first.status()).hasToString("FRESH");
		assertThat(limited.status()).hasToString("UNAVAILABLE");
		assertThat(limited.fallbackCode()).isEqualTo("PROVIDER_RATE_LIMITED");
		assertThat(limited.arrivals()).isEmpty();
		assertThat(provider.arrivalCalls).hasValue(1);
		assertThat(service.providerHealthSnapshot().staleResultRatio()).isZero();
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
	@DisplayName("provider call rate limit 설정은 안전 상한을 넘지 않는다")
	void providerRateLimitIsCappedAtSafeDefault() {
		MutableClock clock = new MutableClock(Instant.parse("2026-06-26T08:00:00Z"));
		CountingProvider provider = new CountingProvider();
		StubMappingPort mappingPort = new StubMappingPort();
		for (int index = 0; index < 2; index += 1) {
			mappingPort.add(mapping(
				"station-%02d".formatted(index),
				"seoul-4",
				"1004",
				"10040004%02d".formatted(index),
				"상록수%02d".formatted(index),
				true,
				false,
				"OFFICIAL"
			));
		}
		RealtimeGatewayService service = service(
			provider,
			clock,
			mappingPort,
			new RealtimeProviderControl(),
			999
		);

		RealtimeArrivalResult result = null;
		for (int index = 0; index < 2; index += 1) {
			result = service.arrivals(new RealtimeQuery(
				"station-%02d".formatted(index),
				"seoul-4",
				"1004",
				"상록수%02d".formatted(index),
				null
			));
		}

		assertThat(result.status()).hasToString("UNAVAILABLE");
		assertThat(result.fallbackCode()).isEqualTo("PROVIDER_RATE_LIMITED");
		assertThat(provider.arrivalCalls).hasValue(1);
	}

	@Test
	@DisplayName("provider call rate limit은 KST 일일 안전 한도도 넘지 않는다")
	void providerRateLimitBlocksDailyOverflow() {
		MutableClock clock = new MutableClock(Instant.parse("2026-06-26T08:00:00Z"));
		CountingProvider provider = new CountingProvider();
		StubMappingPort mappingPort = new StubMappingPort();
		for (int index = 0; index < 4; index += 1) {
			mappingPort.add(mapping(
				"station-day-%02d".formatted(index),
				"seoul-4",
				"1004",
				"10040005%02d".formatted(index),
				"상록수일일%02d".formatted(index),
				true,
				false,
				"OFFICIAL"
			));
		}
		RealtimeGatewayService service = service(
			provider,
			clock,
			mappingPort,
			new RealtimeProviderControl(),
			1,
			2
		);

		RealtimeArrivalResult result = null;
		List<Instant> sameKstDayInstants = List.of(
			Instant.parse("2026-06-26T08:00:00Z"),
			Instant.parse("2026-06-26T08:01:00Z"),
			Instant.parse("2026-06-26T08:02:00Z")
		);
		for (int index = 0; index < sameKstDayInstants.size(); index += 1) {
			clock.instant = sameKstDayInstants.get(index);
			provider.providerReceivedAt = clock.instant.toString();
			result = service.arrivals(new RealtimeQuery(
				"station-day-%02d".formatted(index),
				"seoul-4",
				"1004",
				"상록수일일%02d".formatted(index),
				null
			));
		}

		assertThat(result.status()).hasToString("UNAVAILABLE");
		assertThat(result.fallbackCode()).isEqualTo("PROVIDER_RATE_LIMITED");
		clock.instant = Instant.parse("2026-06-26T15:00:00Z");
		provider.providerReceivedAt = clock.instant.toString();
		RealtimeArrivalResult nextKstDay = service.arrivals(new RealtimeQuery(
			"station-day-03",
			"seoul-4",
			"1004",
			"상록수일일03",
			null
		));

		assertThat(nextKstDay.status()).hasToString("FRESH");
		assertThat(provider.arrivalCalls).hasValue(3);
	}

	@Test
	@DisplayName("열차 위치 provider call rate limit도 cache가 있어도 payload 없는 unavailable로 종료한다")
	void trainPositionProviderRateLimitReturnsUnavailableWithoutStaleCache() {
		MutableClock clock = new MutableClock(Instant.parse("2026-06-26T08:00:00Z"));
		CountingProvider provider = new CountingProvider();
		RealtimeGatewayService service = service(
			provider,
			clock,
			InMemoryRealtimeMappingPort.seededFixture(),
			new RealtimeProviderControl(),
			1
		);
		RealtimeQuery query = line4Query();

		RealtimeTrainPositionResult first = service.trainPositions(query);
		clock.instant = Instant.parse("2026-06-26T08:00:30Z");
		provider.providerReceivedAt = "2026-06-26T08:00:30Z";
		RealtimeTrainPositionResult limited = service.trainPositions(query);

		assertThat(first.status()).hasToString("FRESH");
		assertThat(limited.status()).hasToString("UNAVAILABLE");
		assertThat(limited.fallbackCode()).isEqualTo("PROVIDER_RATE_LIMITED");
		assertThat(limited.trainPositions()).isEmpty();
		assertThat(provider.trainPositionCalls).hasValue(1);
		assertThat(service.providerHealthSnapshot().staleResultRatio()).isZero();
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
				"""),
			sangnoksuQuery()
		);

		assertThat(arrivals).hasSize(1);
		assertThat(arrivals.getFirst().destination()).isEqualTo("오이도행 - 중앙방면");
		assertThat(arrivals.getFirst().servicePattern()).isEqualTo("급행");
	}

	private RealtimeQuery sangnoksuQuery() {
		return new RealtimeQuery("station-sangnoksu", "seoul-4", "1004", "상록수", null);
	}

	private RealtimeQuery line4Query() {
		return new RealtimeQuery(null, "seoul-4", "1004", null, "4호선");
	}

	private RealtimeGatewayService service(RealtimeProvider provider, Clock clock) {
		return service(provider, clock, InMemoryRealtimeMappingPort.seededFixture());
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

	private RealtimeGatewayService service(
		RealtimeProvider provider,
		Clock clock,
		RealtimeMappingPort mappingPort,
		RealtimeProviderControl control,
		int providerCallLimitPerMinute
	) {
		return new RealtimeGatewayService(provider, clock, mappingPort, control, providerCallLimitPerMinute);
	}

	private RealtimeGatewayService service(
		RealtimeProvider provider,
		Clock clock,
		RealtimeMappingPort mappingPort,
		RealtimeProviderControl control,
		int providerCallLimitPerMinute,
		int providerCallLimitPerDay
	) {
		return new RealtimeGatewayService(
			provider,
			clock,
			mappingPort,
			control,
			providerCallLimitPerMinute,
			providerCallLimitPerDay
		);
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
				"4",
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
				"4",
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

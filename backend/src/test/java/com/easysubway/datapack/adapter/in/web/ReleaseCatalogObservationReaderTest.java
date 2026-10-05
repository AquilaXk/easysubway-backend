package com.easysubway.datapack.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.datapack.application.port.out.DatapackReleaseCatalogPort;
import com.easysubway.datapack.application.port.out.DatapackReleaseCatalogPort.CatalogIdentity;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("관리자 화면용 공개 목록 조회기")
class ReleaseCatalogObservationReaderTest {

	private static final String SHA = "a".repeat(64);
	private static final CatalogIdentity PRODUCTION = new CatalogIdentity(126, SHA, "production", "", true, SHA);

	private final MutableClock clock = new MutableClock();
	private ReleaseCatalogObservationReader reader;

	@AfterEach
	void shutdown() {
		if (reader != null) {
			reader.shutdown();
		}
	}

	@Test
	@DisplayName("운영 채널을 명시해 조회하고 서명이 유효한 응답만 돌려준다")
	void readsProductionExplicitly() {
		var channels = new ArrayList<String>();
		reader = new ReleaseCatalogObservationReader(port(channel -> {
			channels.add(channel);
			return PRODUCTION;
		}), Duration.ofSeconds(1), Duration.ofSeconds(60), clock);

		assertThat(reader.readProduction()).contains(PRODUCTION);
		assertThat(channels).containsExactly("production");
	}

	@Test
	@DisplayName("60초 안의 재조회는 캐시를 쓰고 지난 뒤에는 다시 조회한다")
	void cachesWithinTtl() {
		var calls = new AtomicInteger();
		reader = new ReleaseCatalogObservationReader(port(channel -> {
			calls.incrementAndGet();
			return PRODUCTION;
		}), Duration.ofSeconds(1), Duration.ofSeconds(60), clock);

		reader.readProduction();
		clock.advance(Duration.ofSeconds(59));
		reader.readProduction();
		assertThat(calls).hasValue(1);

		clock.advance(Duration.ofSeconds(2));
		reader.readProduction();
		assertThat(calls).hasValue(2);
	}

	@Test
	@DisplayName("전체 조회 시간이 제한을 넘으면 읽지 못한 것으로 돌려주고 실패도 캐시한다")
	void timesOutAndCachesFailure() {
		var calls = new AtomicInteger();
		reader = new ReleaseCatalogObservationReader(port(channel -> {
			calls.incrementAndGet();
			sleepUntilInterrupted();
			return PRODUCTION;
		}), Duration.ofMillis(200), Duration.ofSeconds(60), clock);

		long start = System.nanoTime();
		assertThat(reader.readProduction()).isEmpty();
		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));

		assertThat(reader.readProduction()).isEmpty();
		assertThat(calls).hasValue(1);
	}

	@Test
	@DisplayName("서명이 유효하지 않거나 어댑터가 실패하면 비어 있다")
	void invalidOrUnavailableIsEmpty() {
		reader = new ReleaseCatalogObservationReader(port(channel ->
			new CatalogIdentity(126, SHA, "production", "", false, SHA)),
			Duration.ofSeconds(1), Duration.ofSeconds(60), clock);
		assertThat(reader.readProduction()).isEmpty();

		reader.clear();
		reader.shutdown();
		reader = new ReleaseCatalogObservationReader(port(channel -> {
			throw new DatapackReleaseCatalogPort.Unavailable();
		}), Duration.ofSeconds(1), Duration.ofSeconds(60), clock);
		assertThat(reader.readProduction()).isEmpty();
	}

	private static void sleepUntilInterrupted() {
		try {
			Thread.sleep(30_000);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new DatapackReleaseCatalogPort.Unavailable();
		}
	}

	private interface CurrentCatalog {
		CatalogIdentity fetchCurrent(String channel);
	}

	private static DatapackReleaseCatalogPort port(CurrentCatalog current) {
		return new DatapackReleaseCatalogPort() {
			@Override
			public CatalogIdentity fetch(String channel, long releaseSequence) {
				throw new UnsupportedOperationException();
			}

			@Override
			public CatalogIdentity fetchCurrent(String channel) {
				return current.fetchCurrent(channel);
			}

			@Override
			public Optional<CatalogIdentity> findByRequest(String channel, String releaseRequestId) {
				throw new UnsupportedOperationException();
			}
		};
	}

	private static final class MutableClock extends Clock {
		private Instant now = Instant.parse("2026-10-05T00:00:00Z");

		void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public java.time.ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}
}

package com.easysubway.datapack.adapter.in.web;

import com.easysubway.datapack.application.port.out.DatapackReleaseCatalogPort;
import com.easysubway.datapack.application.port.out.DatapackReleaseCatalogPort.CatalogIdentity;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 관리자 화면이 운영 공개 목록을 읽는 조회기. 헤더·본문을 합친 전체 조회 시간을 하나의 제한으로 묶고,
 * 결과(실패 포함)를 짧게 캐시해 새로고침이 요청 스레드를 붙잡지 않게 한다. 읽지 못하면 빈 값을 돌려
 * 화면이 "읽지 못함"을 명시적으로 표시한다.
 */
@Component
class ReleaseCatalogObservationReader {

	static final String PRODUCTION = "production";

	private final DatapackReleaseCatalogPort catalogPort;
	private final Duration timeout;
	private final Duration ttl;
	private final Clock clock;
	private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
		var thread = new Thread(task, "admin-release-catalog-reader");
		thread.setDaemon(true);
		return thread;
	});
	private Optional<CatalogIdentity> cached;
	private Instant cachedAt;

	@Autowired
	ReleaseCatalogObservationReader(
		DatapackReleaseCatalogPort catalogPort,
		@Value("${easysubway.datapack.admin-catalog-read-timeout:PT5S}") Duration timeout
	) {
		this(catalogPort, timeout, Duration.ofSeconds(60), Clock.systemUTC());
	}

	ReleaseCatalogObservationReader(
		DatapackReleaseCatalogPort catalogPort, Duration timeout, Duration ttl, Clock clock
	) {
		this.catalogPort = catalogPort;
		this.timeout = timeout;
		this.ttl = ttl;
		this.clock = clock;
	}

	synchronized Optional<CatalogIdentity> readProduction() {
		Instant now = clock.instant();
		if (cached != null && now.isBefore(cachedAt.plus(ttl))) {
			return cached;
		}
		cached = fetch();
		cachedAt = now;
		return cached;
	}

	synchronized void clear() {
		cached = null;
		cachedAt = null;
	}

	@PreDestroy
	void shutdown() {
		executor.shutdownNow();
	}

	private Optional<CatalogIdentity> fetch() {
		Future<Optional<CatalogIdentity>> pending =
			executor.submit(() -> ReleaseObservationView.readCatalog(catalogPort, PRODUCTION));
		try {
			return pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
		} catch (TimeoutException | ExecutionException failure) {
			pending.cancel(true);
			return Optional.empty();
		} catch (InterruptedException interrupted) {
			pending.cancel(true);
			Thread.currentThread().interrupt();
			return Optional.empty();
		}
	}
}

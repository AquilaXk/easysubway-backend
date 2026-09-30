package com.easysubway.transit.adapter.out.seoul;

import com.easysubway.common.http.BoundedResponseBody;
import com.easysubway.transit.adapter.out.seoul.SeoulMetroElevatorFeed.Classification;
import com.easysubway.transit.adapter.out.seoul.SeoulMetroElevatorFeed.UnidentifiableReason;
import com.easysubway.transit.application.port.out.FacilityOperationalStatusStore;
import com.easysubway.transit.application.port.out.FacilityOperationalStatusStore.FeedApplyResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 서울교통공사 공식 엘리베이터 가동 정보({@code getFcElvtr}, {@code oprtngSitu})를 주기 수집해 시설 운영 상태 테이블에
 * 반영한다(#419).
 *
 * <ul>
 *   <li>한 회차는 모든 페이지를 받고 형식을 검사한 뒤에만 한 트랜잭션으로 반영하고, 그 커밋과 함께 심장박동을 옮긴다.
 *       그 회차 관측에 없는 시설의 원천 행은 같은 트랜잭션에서 지우고, 관리자 확인 행은 남겨 수만 지표로 낸다.</li>
 *   <li>전송·HTTP·결과 코드·응답 형식·저장 오류는 그 회차를 반영하지 않고 실패 지표·로그로 드러낸다. 이전 값을 새 관측으로
 *       다시 쓰지 않는다.</li>
 *   <li>자체 호출 한도는 두지 않는다(QA 결정). 수집 주기만 설정한다.</li>
 *   <li>인증키({@code EASYSUBWAY_SEOUL_METRO_ELEVATOR_SERVICE_KEY})가 없으면 원천을 호출하지 않는다. 이때 심장박동이 옮겨지지
 *       않으므로 신선도 판단에서 명시적으로 드러난다.</li>
 * </ul>
 */
@Component
public class SeoulMetroElevatorStatusCollector {

	static final URI DEFAULT_ENDPOINT = URI.create("https://apis.data.go.kr/B553766/facility/getFcElvtr");
	private static final Logger log = LoggerFactory.getLogger(SeoulMetroElevatorStatusCollector.class);
	private static final String FEED = FacilityOperationalStatusStore.SEOUL_METRO_ELEVATOR_FEED;
	private static final String METRIC_PREFIX = "easysubway.facility_status.feed.";
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
	private static final int DEFAULT_PAGE_SIZE = 1000;
	private static final int DEFAULT_MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

	private final String serviceKey;
	private final URI endpoint;
	private final FacilityOperationalStatusStore store;
	private final ObjectMapper objectMapper;
	private final MeterRegistry meterRegistry;
	private final HttpClient httpClient;
	private final Clock clock;
	private final int pageSize;
	private final int maxResponseBytes;
	private final Map<String, AtomicInteger> facilitiesByCode = new ConcurrentHashMap<>();
	private final Map<UnidentifiableReason, AtomicInteger> unidentifiable = new EnumMap<>(UnidentifiableReason.class);
	private final AtomicInteger adminVerifiedKept = new AtomicInteger();
	private final AtomicInteger adminVerifiedAbsent = new AtomicInteger();
	private final AtomicReference<Instant> lastSuccessAt = new AtomicReference<>();
	private final Counter unknownCodeCounter;

	@Autowired
	public SeoulMetroElevatorStatusCollector(
		@Value("${EASYSUBWAY_SEOUL_METRO_ELEVATOR_SERVICE_KEY:}") String serviceKey,
		FacilityOperationalStatusStore store,
		ObjectMapper objectMapper,
		MeterRegistry meterRegistry
	) {
		this(
			serviceKey,
			DEFAULT_ENDPOINT,
			store,
			objectMapper,
			meterRegistry,
			HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build(),
			Clock.systemUTC(),
			DEFAULT_PAGE_SIZE,
			DEFAULT_MAX_RESPONSE_BYTES
		);
	}

	SeoulMetroElevatorStatusCollector(
		String serviceKey,
		URI endpoint,
		FacilityOperationalStatusStore store,
		ObjectMapper objectMapper,
		MeterRegistry meterRegistry,
		HttpClient httpClient,
		Clock clock,
		int pageSize,
		int maxResponseBytes
	) {
		this.serviceKey = decodedServiceKey(serviceKey);
		this.endpoint = endpoint;
		this.store = store;
		this.objectMapper = objectMapper;
		this.meterRegistry = meterRegistry;
		this.httpClient = httpClient;
		this.clock = clock;
		this.pageSize = pageSize;
		this.maxResponseBytes = maxResponseBytes;
		for (String code : SeoulMetroElevatorFeed.REPORTED_CODES) {
			AtomicInteger count = new AtomicInteger();
			facilitiesByCode.put(code, count);
			Gauge.builder(METRIC_PREFIX + "facilities", count, AtomicInteger::get)
				.description("Facilities by provider oprtngSitu code in the last successful collection")
				.tags("feed", FEED, "code", code)
				.register(meterRegistry);
		}
		for (UnidentifiableReason reason : UnidentifiableReason.values()) {
			AtomicInteger count = new AtomicInteger();
			unidentifiable.put(reason, count);
			Gauge.builder(METRIC_PREFIX + "unidentifiable", count, AtomicInteger::get)
				.description("Provider rows without a status because the smrt-elev facility id is unidentifiable")
				.tags("feed", FEED, "reason", reason.name())
				.register(meterRegistry);
		}
		Gauge.builder(METRIC_PREFIX + "admin_verified_kept", adminVerifiedKept, AtomicInteger::get)
			.description("Facilities kept at a more recent admin-verified status in the last successful collection")
			.tag("feed", FEED)
			.register(meterRegistry);
		Gauge.builder(METRIC_PREFIX + "admin_verified_absent", adminVerifiedAbsent, AtomicInteger::get)
			.description("Admin-verified facilities kept although absent from the last successful collection")
			.tag("feed", FEED)
			.register(meterRegistry);
		Gauge.builder(METRIC_PREFIX + "seconds_since_last_success", lastSuccessAt, this::secondsSinceLastSuccess)
			.description("Seconds since the last successful collection in this process; NaN before the first success")
			.tag("feed", FEED)
			.register(meterRegistry);
		this.unknownCodeCounter = Counter.builder(METRIC_PREFIX + "unknown_code")
			.description("Identified facilities skipped because the provider code is unknown or missing")
			.tag("feed", FEED)
			.register(meterRegistry);
		if (this.serviceKey.isBlank()) {
			log.warn("Seoul Metro elevator status collection is disabled: EASYSUBWAY_SEOUL_METRO_ELEVATOR_SERVICE_KEY is not set");
		}
	}

	@Scheduled(
		initialDelayString = "${easysubway.facility-status.seoul-metro.initial-delay-ms:10000}",
		fixedDelayString = "${easysubway.facility-status.seoul-metro.fixed-delay-ms:60000}"
	)
	public void collect() {
		if (serviceKey.isBlank()) {
			return;
		}
		Instant observedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
		try {
			Classification classification = SeoulMetroElevatorFeed.classify(fetchAllRows());
			FeedApplyResult result = apply(classification, observedAt);
			recordSuccess(classification, result, observedAt);
		} catch (CollectionFailure failure) {
			fail(failure.reason, failure.getCause());
		} catch (SeoulMetroElevatorFeedException exception) {
			fail("MALFORMED_RESPONSE", exception);
		}
	}

	private FeedApplyResult apply(Classification classification, Instant observedAt) {
		try {
			return store.applyFeedCollection(FEED, classification.observations(), observedAt);
		} catch (RuntimeException exception) {
			throw new CollectionFailure("STORE_WRITE", exception);
		}
	}

	private void recordSuccess(Classification classification, FeedApplyResult result, Instant observedAt) {
		classification.facilitiesByCode().forEach((code, count) -> facilitiesByCode.get(code).set(count));
		classification.unidentifiable().forEach((reason, count) -> unidentifiable.get(reason).set(count));
		adminVerifiedKept.set(result.keptAdminVerified());
		adminVerifiedAbsent.set(result.adminVerifiedAbsent());
		unknownCodeCounter.increment(classification.unknownCodeFacilities());
		lastSuccessAt.set(observedAt);
		collections("success", "NONE").increment();
		log.info(
			"Seoul Metro elevator status collected: observations={}, written={}, adminVerifiedKept={}, removed={}, "
				+ "adminVerifiedAbsent={}, unidentifiable={}, unknownCode={}",
			classification.observations().size(),
			result.written(),
			result.keptAdminVerified(),
			result.removed(),
			result.adminVerifiedAbsent(),
			classification.unidentifiableTotal(),
			classification.unknownCodeFacilities()
		);
	}

	private void fail(String reason, Throwable cause) {
		collections("failure", reason).increment();
		log.warn(
			"Seoul Metro elevator status collection failed: reason={}, cause={}",
			reason,
			cause == null ? "none" : cause.getClass().getSimpleName()
		);
	}

	private Counter collections(String outcome, String reason) {
		return Counter.builder(METRIC_PREFIX + "collections")
			.description("Seoul Metro elevator status collection attempts by outcome")
			.tags("feed", FEED, "outcome", outcome, "reason", reason)
			.register(meterRegistry);
	}

	private double secondsSinceLastSuccess(AtomicReference<Instant> reference) {
		Instant last = reference.get();
		return last == null ? Double.NaN : Duration.between(last, clock.instant()).toSeconds();
	}

	private List<JsonNode> fetchAllRows() {
		List<JsonNode> rows = new ArrayList<>();
		Integer totalCount = null;
		int pageNo = 1;
		while (totalCount == null || rows.size() < totalCount) {
			JsonNode body = fetchPage(pageNo);
			int pageTotal = totalCount(body);
			if (totalCount != null && pageTotal != totalCount) {
				throw new CollectionFailure("MALFORMED_RESPONSE", null);
			}
			totalCount = pageTotal;
			JsonNode items = body.path("items").path("item");
			if (!items.isArray()) {
				throw new CollectionFailure("MALFORMED_RESPONSE", null);
			}
			items.forEach(rows::add);
			if (rows.size() > totalCount || rows.size() < totalCount && items.isEmpty()) {
				throw new CollectionFailure("MALFORMED_RESPONSE", null);
			}
			pageNo++;
		}
		if (rows.isEmpty()) {
			throw new CollectionFailure("MALFORMED_RESPONSE", null);
		}
		return rows;
	}

	private static int totalCount(JsonNode body) {
		JsonNode totalCount = body.path("totalCount");
		if (!totalCount.isInt() || totalCount.intValue() < 0) {
			throw new CollectionFailure("MALFORMED_RESPONSE", null);
		}
		return totalCount.intValue();
	}

	private JsonNode fetchPage(int pageNo) {
		URI uri = URI.create(endpoint + "?serviceKey=" + URLEncoder.encode(serviceKey, StandardCharsets.UTF_8)
			+ "&pageNo=" + pageNo + "&numOfRows=" + pageSize + "&dataType=JSON");
		HttpRequest request = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).GET().build();
		byte[] bytes;
		try {
			HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
			try (InputStream body = response.body()) {
				if (response.statusCode() != 200) {
					throw new CollectionFailure("HTTP_STATUS", null);
				}
				bytes = BoundedResponseBody.read(
					body,
					maxResponseBytes,
					REQUEST_TIMEOUT,
					() -> new CollectionFailure("MALFORMED_RESPONSE", null)
				);
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new CollectionFailure("INTERRUPTED", exception);
		} catch (IOException exception) {
			throw new CollectionFailure("TRANSPORT", exception);
		}
		JsonNode payload;
		try {
			payload = objectMapper.readTree(bytes);
		} catch (IOException exception) {
			throw new CollectionFailure("MALFORMED_RESPONSE", exception);
		}
		if (!"00".equals(payload.path("response").path("header").path("resultCode").asText(null))) {
			throw new CollectionFailure("PROVIDER_RESULT_CODE", null);
		}
		return payload.path("response").path("body");
	}

	private static String decodedServiceKey(String value) {
		String trimmed = value.trim();
		return trimmed.matches(".*%[0-9A-Fa-f]{2}.*") ? URLDecoder.decode(trimmed, StandardCharsets.UTF_8) : trimmed;
	}

	/** 한 회차 실패. 사유 코드는 지표 태그로만 쓰고 원천 응답·요청 URI(인증키 포함)는 담지 않는다. */
	private static final class CollectionFailure extends RuntimeException {

		private static final long serialVersionUID = 1L;

		private final String reason;

		private CollectionFailure(String reason, Throwable cause) {
			super(reason, cause, false, false);
			this.reason = reason;
		}
	}
}

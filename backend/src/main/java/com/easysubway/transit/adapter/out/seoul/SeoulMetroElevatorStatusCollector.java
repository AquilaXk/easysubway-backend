package com.easysubway.transit.adapter.out.seoul;

import com.easysubway.common.http.BoundedResponseBody;
import com.easysubway.transit.application.port.out.LoadTransitMasterPort;
import com.easysubway.transit.application.port.out.SaveAccessibilityFacilityStatusPort;
import com.easysubway.transit.application.port.out.SourceCollectionHeartbeatPort;
import com.easysubway.transit.domain.AccessibilityFacility;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import com.easysubway.transit.domain.AccessibilityFacilityType;
import com.easysubway.transit.domain.DataConfidenceLevel;
import com.easysubway.transit.domain.DataSourceType;
import com.easysubway.transit.domain.StationLine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically collects live elevator operating status from the official Seoul Metro OpenAPI
 * (getFcElvtr) and updates master facilities accordingly (#419).
 */
@Component
public final class SeoulMetroElevatorStatusCollector implements SourceCollectionHeartbeatPort {

	private static final Logger log = LoggerFactory.getLogger(SeoulMetroElevatorStatusCollector.class);
	private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(10);
	private static final int DEFAULT_MAX_RESPONSE_BYTES = 2_097_152; // 2MB
	private static final String UNKNOWN_CODE = "UNKNOWN";
	private static final Pattern EXIT_PATTERN = Pattern.compile("^(\\d+)번\\s*출입구$");

	public record Configuration(
		String serviceKey,
		String endpoint,
		Duration requestTimeout,
		int maxResponseBytes
	) {
		public Configuration(String serviceKey, String endpoint) {
			this(serviceKey, endpoint, DEFAULT_REQUEST_TIMEOUT, DEFAULT_MAX_RESPONSE_BYTES);
		}
	}

	private final String serviceKey;
	private final String endpoint;
	private final LoadTransitMasterPort loadTransitMasterPort;
	private final SaveAccessibilityFacilityStatusPort saveAccessibilityFacilityStatusPort;
	private final ObjectMapper objectMapper;
	private final HttpClient httpClient;
	private final Clock clock;
	private final Duration requestTimeout;
	private final int maxResponseBytes;

	private final Counter successCounter;
	private final Counter failureCounter;
	private final Counter unknownCodeCounter;
	private final Map<String, AtomicInteger> codeCounts = new ConcurrentHashMap<>();
	private final AtomicInteger linkedElevatorsCount = new AtomicInteger(0);
	private final AtomicInteger unlinkedElevatorsCount = new AtomicInteger(0);
	private volatile Instant lastSuccessfulCollectionAt = null;

	@Autowired
	public SeoulMetroElevatorStatusCollector(
		@Value("${DATA_GO_KR_SERVICE_KEY:${easysubway.seoul-metro.elevator.service-key:}}") String serviceKey,
		@Value("${easysubway.seoul-metro.elevator.endpoint:https://apis.data.go.kr/B553766/facility/getFcElvtr}") String endpoint,
		ObjectProvider<LoadTransitMasterPort> loadTransitMasterPortProvider,
		ObjectProvider<SaveAccessibilityFacilityStatusPort> saveAccessibilityFacilityStatusPortProvider,
		ObjectMapper objectMapper,
		ObjectProvider<Clock> clockProvider,
		ObjectProvider<MeterRegistry> meterRegistryProvider
	) {
		this(
			new Configuration(serviceKey, endpoint),
			loadTransitMasterPortProvider.getIfAvailable(),
			saveAccessibilityFacilityStatusPortProvider.getIfAvailable(),
			objectMapper,
			HttpClient.newBuilder().connectTimeout(DEFAULT_REQUEST_TIMEOUT).build(),
			clockProvider.getIfAvailable(Clock::systemDefaultZone),
			meterRegistryProvider.getIfAvailable(SimpleMeterRegistry::new)
		);
	}

	public SeoulMetroElevatorStatusCollector(
		Configuration config,
		LoadTransitMasterPort loadTransitMasterPort,
		SaveAccessibilityFacilityStatusPort saveAccessibilityFacilityStatusPort,
		ObjectMapper objectMapper,
		HttpClient httpClient,
		Clock clock,
		MeterRegistry meterRegistry
	) {
		Configuration effectiveConfig = config != null ? config : new Configuration("", "");
		this.serviceKey = effectiveConfig.serviceKey() != null ? effectiveConfig.serviceKey().trim() : "";
		this.endpoint = effectiveConfig.endpoint() != null && !effectiveConfig.endpoint().isBlank()
			? effectiveConfig.endpoint().trim()
			: "https://apis.data.go.kr/B553766/facility/getFcElvtr";
		this.loadTransitMasterPort = loadTransitMasterPort;
		this.saveAccessibilityFacilityStatusPort = saveAccessibilityFacilityStatusPort;
		this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
		this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
		this.clock = Objects.requireNonNull(clock, "clock");
		MeterRegistry registry = meterRegistry != null ? meterRegistry : new SimpleMeterRegistry();
		this.requestTimeout = effectiveConfig.requestTimeout() != null ? effectiveConfig.requestTimeout() : DEFAULT_REQUEST_TIMEOUT;
		this.maxResponseBytes = effectiveConfig.maxResponseBytes() > 0 ? effectiveConfig.maxResponseBytes() : DEFAULT_MAX_RESPONSE_BYTES;

		this.successCounter = registry.counter("easysubway.collection.seoul-metro.elevator.sync", "status", "success");
		this.failureCounter = registry.counter("easysubway.collection.seoul-metro.elevator.sync", "status", "failure");
		this.unknownCodeCounter = registry.counter("easysubway.collection.seoul-metro.elevator.unknown-codes");

		for (String code : List.of("M", "S", "T", "I", "B", "D", UNKNOWN_CODE)) {
			AtomicInteger count = new AtomicInteger(0);
			this.codeCounts.put(code, count);
			Gauge.builder("easysubway.collection.seoul-metro.elevator.code-count", count, AtomicInteger::get)
				.tag("code", code)
				.description("Count of elevator facilities observed for code " + code)
				.register(registry);
		}

		Gauge.builder("easysubway.collection.seoul-metro.elevator.linked-count", this, collector -> (double) collector.linkedElevatorsCount())
			.description("Count of elevator facilities successfully linked to master data")
			.register(registry);
		Gauge.builder("easysubway.collection.seoul-metro.elevator.unlinked-count", this, collector -> (double) collector.unlinkedElevatorsCount())
			.description("Count of elevator facilities not linked to master data")
			.register(registry);
		Gauge.builder("easysubway.collection.seoul-metro.elevator.total-count", this, collector -> (double) collector.totalElevatorsCount())
			.description("Total count of active elevator facilities observed")
			.register(registry);
		Gauge.builder("easysubway.collection.seoul-metro.elevator.unlinked-ratio", this, collector -> {
				int total = collector.totalElevatorsCount();
				if (total == 0) {
					return 0.0;
				}
				return (double) collector.unlinkedElevatorsCount() / total;
			})
			.description("Ratio of unlinked elevators to total active elevators")
			.register(registry);

		Gauge.builder("easysubway.collection.seoul-metro.elevator.seconds-since-last-success", this, collector -> {
				Instant last = collector.lastSuccessfulSourceCollectionAt();
				if (last == null) {
					return Double.NaN;
				}
				return (double) Math.max(0, Duration.between(last, collector.clock.instant()).toSeconds());
			})
			.description("Seconds since the last successful Seoul Metro elevator status collection")
			.register(registry);
	}

	@Override
	public Instant lastSuccessfulSourceCollectionAt() {
		return lastSuccessfulCollectionAt;
	}

	public int linkedElevatorsCount() {
		return linkedElevatorsCount.get();
	}

	public int unlinkedElevatorsCount() {
		return unlinkedElevatorsCount.get();
	}

	public int totalElevatorsCount() {
		return linkedElevatorsCount.get() + unlinkedElevatorsCount.get();
	}

	@Scheduled(fixedDelayString = "${easysubway.seoul-metro.elevator.refresh-interval-ms:60000}")
	public synchronized void collect() {
		if (serviceKey.isBlank()) {
			log.debug("Seoul Metro elevator collection skipped: serviceKey is empty");
			return;
		}

		try {
			List<RawElevatorItem> items = fetchAllElevatorItems();
			processElevatorItems(items);
			this.lastSuccessfulCollectionAt = clock.instant();
			this.successCounter.increment();
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			log.warn("Seoul Metro elevator status collection interrupted: {}", exception.getMessage());
			this.failureCounter.increment();
		} catch (Exception exception) {
			log.warn("Seoul Metro elevator status collection failed: {}", exception.getMessage());
			this.failureCounter.increment();
		}
	}

	private List<RawElevatorItem> fetchAllElevatorItems() throws Exception {
		List<RawElevatorItem> collected = new ArrayList<>();
		int pageNo = 1;
		int numOfRows = 1000;
		int totalCount = -1;

		while (totalCount == -1 || collected.size() < totalCount) {
			PageResult page = fetchPage(pageNo, numOfRows);
			totalCount = page.totalCount();
			collected.addAll(page.items());
			if (page.items().isEmpty() || collected.size() >= totalCount) {
				break;
			}
			pageNo++;
		}
		return collected;
	}

	private record PageResult(int totalCount, List<RawElevatorItem> items) {
	}

	private PageResult fetchPage(int pageNo, int numOfRows) throws Exception {
		String uriStr = endpoint + "?serviceKey=" + URLEncoder.encode(serviceKey, StandardCharsets.UTF_8)
			+ "&pageNo=" + pageNo
			+ "&numOfRows=" + numOfRows
			+ "&dataType=JSON";
		HttpRequest request = HttpRequest.newBuilder(URI.create(uriStr))
			.timeout(requestTimeout)
			.GET()
			.build();

		long startedAt = System.nanoTime();
		HttpResponse<?> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
		if (response.statusCode() != 200) {
			throw new IllegalStateException("HTTP status " + response.statusCode());
		}

		byte[] bytes = readResponseBody(response, startedAt);
		JsonNode root = objectMapper.readTree(bytes);
		JsonNode header = root.path("response").path("header");
		String resultCode = header.path("resultCode").asText("");
		if (!"00".equals(resultCode)) {
			throw new IllegalStateException("API error: resultCode=" + resultCode + ", msg=" + header.path("resultMsg").asText(""));
		}

		JsonNode bodyNode = root.path("response").path("body");
		int totalCount = bodyNode.path("totalCount").asInt(0);
		List<RawElevatorItem> items = parsePageItems(bodyNode.path("items").path("item"));
		return new PageResult(totalCount, items);
	}

	private byte[] readResponseBody(HttpResponse<?> response, long startedAt) throws IOException {
		Object rawBody = response.body();
		Duration remainingTimeout = requestTimeout.minusNanos(System.nanoTime() - startedAt);
		if (remainingTimeout.isNegative() || remainingTimeout.isZero()) {
			remainingTimeout = Duration.ofMillis(100);
		}
		if (rawBody instanceof InputStream inputStream) {
			try (inputStream) {
				return BoundedResponseBody.read(
					inputStream,
					maxResponseBytes,
					remainingTimeout,
					() -> new IllegalStateException("Response body exceeded " + maxResponseBytes + " bytes")
				);
			}
		}
		if (rawBody instanceof byte[] byteArray) {
			return byteArray;
		}
		throw new IllegalStateException("Unexpected response body type: " + rawBody.getClass());
	}

	private List<RawElevatorItem> parsePageItems(JsonNode itemsNode) {
		List<RawElevatorItem> items = new ArrayList<>();
		if (itemsNode == null || itemsNode.isMissingNode() || itemsNode.isNull()) {
			return items;
		}
		if (itemsNode.isArray()) {
			for (JsonNode item : itemsNode) {
				items.add(parseItemNode(item));
			}
		} else if (itemsNode.isObject()) {
			items.add(parseItemNode(itemsNode));
		}
		return items;
	}

	private RawElevatorItem parseItemNode(JsonNode item) {
		return new RawElevatorItem(
			item.path("stnCd").asText("").trim(),
			item.path("stnNm").asText("").trim(),
			item.path("lineNm").asText("").trim(),
			item.path("dtlPstn").asText("").trim(),
			item.path("oprtngSitu").asText("").trim()
		);
	}

	private void processElevatorItems(List<RawElevatorItem> items) {
		updateCodeMetrics(items);

		// 1. Exclude 'D' (deleted) and records with missing station code or position
		List<RawElevatorItem> activeItems = new ArrayList<>();
		for (RawElevatorItem item : items) {
			if (!"D".equals(item.oprtngSitu()) && !item.stnCd().isEmpty() && !item.dtlPstn().isEmpty()) {
				activeItems.add(item);
			}
		}

		LocalDate observationDate = LocalDate.now(clock);
		linkedElevatorsCount.set(0);
		unlinkedElevatorsCount.set(0);

		List<StationLine> stationLines = loadTransitMasterPort != null
			? loadTransitMasterPort.loadStationLines()
			: Collections.emptyList();
		List<AccessibilityFacility> allFacilities = loadTransitMasterPort != null
			? loadTransitMasterPort.loadAccessibilityFacilities()
			: Collections.emptyList();

		Map<String, Set<String>> stationCodeToStationIds = new HashMap<>();
		for (StationLine sl : stationLines) {
			if (sl.stationCode() != null && !sl.stationCode().isBlank()) {
				stationCodeToStationIds.computeIfAbsent(sl.stationCode().trim(), k -> new HashSet<>()).add(sl.stationId());
			}
		}

		for (RawElevatorItem item : activeItems) {
			processSingleItem(item, observationDate, stationCodeToStationIds, allFacilities);
		}
	}

	private void processSingleItem(
		RawElevatorItem item,
		LocalDate observationDate,
		Map<String, Set<String>> stationCodeToStationIds,
		List<AccessibilityFacility> allFacilities
	) {
		Matcher matcher = EXIT_PATTERN.matcher(item.dtlPstn().trim());
		if (!matcher.matches()) {
			unlinkedElevatorsCount.incrementAndGet();
			return;
		}
		String exitNumber = matcher.group(1);

		Set<String> stationIds = stationCodeToStationIds.get(item.stnCd().trim());
		if (stationIds == null || stationIds.isEmpty()) {
			unlinkedElevatorsCount.incrementAndGet();
			return;
		}

		AccessibilityFacilityStatus status = parseOperationStatus(item.oprtngSitu());
		if (status == null) {
			unknownCodeCounter.increment();
			unlinkedElevatorsCount.incrementAndGet();
			return;
		}

		List<AccessibilityFacility> matchingFacilities = new ArrayList<>();
		for (AccessibilityFacility facility : allFacilities) {
			if (stationIds.contains(facility.stationId())
				&& facility.type() == AccessibilityFacilityType.ELEVATOR
				&& matchesExit(facility, exitNumber)) {
				matchingFacilities.add(facility);
			}
		}

		if (matchingFacilities.size() != 1) {
			unlinkedElevatorsCount.incrementAndGet();
			return;
		}

		AccessibilityFacility matchedFacility = matchingFacilities.get(0);
		linkedElevatorsCount.incrementAndGet();

		AccessibilityFacility freshFacility = loadTransitMasterPort != null
			? loadTransitMasterPort.loadAccessibilityFacility(matchedFacility.id()).orElse(matchedFacility)
			: matchedFacility;

		if (shouldApplyOfficialStatus(freshFacility, observationDate)) {
			persistFacility(freshFacility, status, observationDate);
		}
	}

	private boolean matchesExit(AccessibilityFacility facility, String exitNumber) {
		if (Objects.equals(facility.exitId(), exitNumber)) {
			return true;
		}
		// Format: kric-elev:<railOprIsttCd>:<lnCd>:<stinCd>:<exitNo>:<층구간>:<위치해시>
		String id = facility.id();
		if (id != null && id.startsWith("kric-elev:")) {
			String[] parts = id.split(":");
			if (parts.length >= 5 && Objects.equals(parts[4], exitNumber)) {
				return true;
			}
		}
		return false;
	}

	private void updateCodeMetrics(List<RawElevatorItem> items) {
		Map<String, Integer> currentCounts = new LinkedHashMap<>();
		for (String code : List.of("M", "S", "T", "I", "B", "D", UNKNOWN_CODE)) {
			currentCounts.put(code, 0);
		}

		for (RawElevatorItem item : items) {
			String code = item.oprtngSitu();
			if (currentCounts.containsKey(code)) {
				currentCounts.put(code, currentCounts.get(code) + 1);
			} else {
				currentCounts.put(UNKNOWN_CODE, currentCounts.get(UNKNOWN_CODE) + 1);
			}
		}
		currentCounts.forEach((code, count) -> {
			AtomicInteger gaugeVal = codeCounts.get(code);
			if (gaugeVal != null) {
				gaugeVal.set(count);
			}
		});
	}

	private void persistFacility(
		AccessibilityFacility existing,
		AccessibilityFacilityStatus status,
		LocalDate observationDate
	) {
		if (saveAccessibilityFacilityStatusPort == null || existing == null) {
			return;
		}
		AccessibilityFacility updated = new AccessibilityFacility(
			existing.id(),
			existing.stationId(),
			existing.exitId(),
			existing.type(),
			existing.name(),
			existing.floorFrom(),
			existing.floorTo(),
			existing.latitude(),
			existing.longitude(),
			existing.description(),
			status,
			DataConfidenceLevel.HIGH,
			DataSourceType.OFFICIAL_API,
			observationDate
		);
		try {
			saveAccessibilityFacilityStatusPort.saveAccessibilityFacility(updated, "seoul-metro-collector");
		} catch (UnsupportedOperationException ignored) {
			saveAccessibilityFacilityStatusPort.saveFacilityStatus(
				existing.id(),
				status,
				observationDate,
				"seoul-metro-collector"
			);
		}
	}

	public static AccessibilityFacilityStatus parseOperationStatus(String code) {
		if (code == null) {
			return null;
		}
		return switch (code.trim()) {
			case "M" -> AccessibilityFacilityStatus.NORMAL;
			case "S", "T", "I" -> AccessibilityFacilityStatus.BROKEN;
			case "B" -> AccessibilityFacilityStatus.UNDER_CONSTRUCTION;
			default -> null;
		};
	}

	public static boolean isAdminVerified(AccessibilityFacility facility) {
		if (facility == null) {
			return false;
		}
		return facility.status() == AccessibilityFacilityStatus.ADMIN_VERIFIED
			|| facility.dataSourceType() == DataSourceType.ADMIN_VERIFIED;
	}

	public static boolean shouldApplyOfficialStatus(
		AccessibilityFacility existingFacility,
		LocalDate observationDate
	) {
		if (existingFacility == null || !isAdminVerified(existingFacility)) {
			return true;
		}
		LocalDate adminUpdatedAt = existingFacility.lastUpdatedAt();
		return adminUpdatedAt == null || observationDate.isAfter(adminUpdatedAt);
	}

	private record RawElevatorItem(
		String stnCd,
		String stnNm,
		String lineNm,
		String dtlPstn,
		String oprtngSitu
	) {
	}
}

package com.easysubway.realtime.application;

import com.easysubway.common.http.BoundedResponseBody;
import com.easysubway.realtime.domain.RealtimeArrival;
import com.easysubway.realtime.domain.RealtimeTrainPosition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class TopisRealtimeProvider implements RealtimeProvider {

	private static final Logger log = LoggerFactory.getLogger(TopisRealtimeProvider.class);
	private static final URI TOPIS_BASE_URI = URI.create("http://swopenapi.seoul.go.kr/api/subway/");
	private static final Duration REQUEST_TIMEOUT = Duration.ofMillis(1500);
	private static final int MAX_RESPONSE_BYTES = 1_048_576;
	private static final Pattern ETA_PATTERN = Pattern.compile("(\\d+)\\s*분(?:\\s*(\\d+)\\s*초)?|(\\d+)\\s*초");

	private final String serviceKey;
	private final ObjectMapper objectMapper;
	private final HttpClient httpClient;

	@Autowired
	TopisRealtimeProvider(
		@Value("${EASYSUBWAY_SEOUL_TOPIS_SERVICE_KEY:}") String serviceKey,
		ObjectMapper objectMapper
	) {
		this(
			serviceKey,
			objectMapper,
			HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build()
		);
	}

	TopisRealtimeProvider(
		String serviceKey,
		ObjectMapper objectMapper,
		HttpClient httpClient
	) {
		this.serviceKey = serviceKey == null ? "" : serviceKey.trim();
		this.objectMapper = objectMapper;
		this.httpClient = httpClient;
	}

	@Override
	public List<RealtimeArrival> arrivals(RealtimeQuery query) {
		if (serviceKey.isBlank()) {
			throw new RealtimeProviderException("PROVIDER_UNAVAILABLE");
		}
		// 역 단위 응답이라 환승역에서는 다른 노선 행이 섞인다. gateway가 조회 노선만 남기므로 조회 노선 행이
		// 행 제한 밖으로 밀리지 않게 한 번의 호출(quota 동일)로 0~20행을 받는다.
		JsonNode payload = request("realtimeStationArrival/0/20/%s".formatted(pathSegment(query.stationQueryName())));
		return arrivalsFromPayload(payload);
	}

	List<RealtimeArrival> arrivalsFromPayload(JsonNode payload) {
		JsonNode items = payload.path("realtimeArrivalList");
		if (!items.isArray()) {
			return emptyWhenNoData(payload);
		}
		List<RealtimeArrival> arrivals = new ArrayList<>();
		for (JsonNode item : items) {
			String lineId = stringOrEmpty(item, "subwayId");
			String stationName = stringOrEmpty(item, "statnNm");
			if (lineId.isBlank() || stationName.isBlank()) {
				continue;
			}
			String arvlMsg2 = stringOrEmpty(item, "arvlMsg2");
			Integer barvlDt = positiveInt(item, "barvlDt");
			Integer etaSeconds = barvlDt != null ? barvlDt : parseEtaFromMessage(arvlMsg2);
			arrivals.add(new RealtimeArrival(
				lineId,
				stationName,
				destination(item),
				stringOrEmpty(item, "updnLine"),
				stringOrEmpty(item, "btrainNo"),
				etaSeconds,
				arvlMsg2,
				stringOrEmpty(item, "arvlMsg3"),
				stringOrEmpty(item, "recptnDt"),
				stringOrEmpty(item, "btrainSttus")
			));
		}
		return requiredItemsOnly("ARRIVALS", items.size(), arrivals);
	}

	@Override
	public List<RealtimeTrainPosition> trainPositions(RealtimeQuery query) {
		if (serviceKey.isBlank()) {
			throw new RealtimeProviderException("PROVIDER_UNAVAILABLE");
		}
		JsonNode payload = request("realtimePosition/0/10/%s".formatted(pathSegment(query.lineName())));
		JsonNode items = payload.path("realtimePositionList");
		if (!items.isArray()) {
			return emptyWhenNoData(payload);
		}
		List<RealtimeTrainPosition> positions = new ArrayList<>();
		for (JsonNode item : items) {
			String lineId = stringOrEmpty(item, "subwayId");
			String stationName = stringOrEmpty(item, "statnNm");
			String trainNo = stringOrEmpty(item, "trainNo");
			if (lineId.isBlank() || stationName.isBlank() || trainNo.isBlank()) {
				continue;
			}
			positions.add(new RealtimeTrainPosition(
				lineId,
				stationName,
				trainNo,
				stringOrEmpty(item, "trainSttus"),
				stringOrEmpty(item, "updnLine"),
				stringOrEmpty(item, "statnTnm"),
				stringOrEmpty(item, "recptnDt")
			));
		}
		return requiredItemsOnly("TRAIN_POSITIONS", items.size(), positions);
	}

	/**
	 * 필수 필드가 빠진 항목은 질의값·빈 문자열로 채우지 않고 버린다. 비어 있지 않은 원천 목록이 전부 버려지면
	 * 스키마 이상을 "데이터 없음"으로 덮지 않도록 원천 불가로 닫는다. 로그에는 건수만 남기고 원천 값은 남기지 않는다.
	 */
	private <T> List<T> requiredItemsOnly(String capability, int receivedCount, List<T> kept) {
		int droppedCount = receivedCount - kept.size();
		if (droppedCount > 0) {
			log.warn(
				"TOPIS realtime items dropped for missing required fields. capability={}, receivedCount={}, droppedCount={}",
				capability,
				receivedCount,
				droppedCount
			);
			if (kept.isEmpty()) {
				throw new RealtimeProviderException("PROVIDER_UNAVAILABLE");
			}
		}
		return List.copyOf(kept);
	}

	private JsonNode request(String capabilityPath) {
		URI uri = TOPIS_BASE_URI.resolve("%s/json/%s".formatted(pathSegment(serviceKey), capabilityPath));
		HttpRequest request = HttpRequest.newBuilder(uri)
			.timeout(REQUEST_TIMEOUT)
			.GET()
			.build();
		long startedAt = System.nanoTime();
		try {
			HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
			try (InputStream body = response.body()) {
				if (response.statusCode() == 429) {
					throw new RealtimeProviderException("PROVIDER_QUOTA_EXCEEDED");
				}
				if (response.statusCode() < 200 || response.statusCode() >= 300) {
					throw new RealtimeProviderException("PROVIDER_UNAVAILABLE");
				}
				// 헤더까지 쓴 시간을 뺀 요청 예산 안에서만 본문을 받는다. 넘기면 HttpTimeoutException → PROVIDER_TIMEOUT.
				byte[] bytes = BoundedResponseBody.read(
					body,
					MAX_RESPONSE_BYTES,
					REQUEST_TIMEOUT.minusNanos(System.nanoTime() - startedAt),
					() -> new RealtimeProviderException("PROVIDER_UNAVAILABLE")
				);
				JsonNode payload = objectMapper.readTree(new String(bytes, StandardCharsets.UTF_8));
				validateTopisStatus(payload);
				return payload;
			}
		} catch (RealtimeProviderException exception) {
			throw exception;
		} catch (HttpTimeoutException exception) {
			throw new RealtimeProviderException("PROVIDER_TIMEOUT");
		} catch (IOException exception) {
			throw new RealtimeProviderException("PROVIDER_UNAVAILABLE");
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new RealtimeProviderException("PROVIDER_UNAVAILABLE");
		}
	}

	/**
	 * INFO-000(정상)과 INFO-200(해당 데이터 없음)만 받아들인다. 결과 코드가 아예 없는 응답은 envelope가 빠진
	 * 비정상 응답으로 보고 목록 형태와 상관없이 원천 불가로 닫는다.
	 */
	void validateTopisStatus(JsonNode payload) {
		String code = providerResultCode(payload);
		if ("INFO-000".equals(code) || "INFO-200".equals(code)) {
			return;
		}
		throw new RealtimeProviderException("PROVIDER_UNAVAILABLE");
	}

	private String pathSegment(String value) {
		return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
	}

	/**
	 * 정상 응답은 {@code errorMessage.code}에, 해당 데이터 없음·오류 응답은 최상위 {@code code}에 결과 코드를 싣는다.
	 */
	private String providerResultCode(JsonNode payload) {
		String code = stringOrEmpty(payload.path("errorMessage"), "code");
		return code.isBlank() ? stringOrEmpty(payload, "code") : code;
	}

	private <T> List<T> emptyWhenNoData(JsonNode payload) {
		if ("INFO-200".equals(providerResultCode(payload))) {
			return List.of();
		}
		throw new RealtimeProviderException("PROVIDER_UNAVAILABLE");
	}

	private String destination(JsonNode node) {
		String destination = stringOrEmpty(node, "bstatnNm");
		return destination.isBlank() ? stringOrEmpty(node, "trainLineNm") : destination;
	}

	private String stringOrEmpty(JsonNode node, String fieldName) {
		JsonNode value = node.path(fieldName);
		return value.isTextual() || value.isNumber() ? value.asText() : "";
	}

	static Integer positiveInt(JsonNode node, String fieldName) {
		JsonNode value = node.path(fieldName);
		if (value.isInt()) {
			int val = value.asInt();
			return val > 0 ? val : null;
		}
		if (value.isTextual()) {
			try {
				int val = Integer.parseInt(value.asText().trim());
				return val > 0 ? val : null;
			} catch (NumberFormatException exception) {
				return null;
			}
		}
		return null;
	}

	static Integer parseEtaFromMessage(String message) {
		if (message == null || message.isBlank()) {
			return null;
		}
		var matcher = ETA_PATTERN.matcher(message);
		if (!matcher.find()) {
			return null;
		}
		if (matcher.group(1) != null) {
			int minutes = Integer.parseInt(matcher.group(1));
			int seconds = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : 0;
			int total = minutes * 60 + seconds;
			return total > 0 ? total : null;
		}
		int seconds = Integer.parseInt(matcher.group(3));
		return seconds > 0 ? seconds : null;
	}

}

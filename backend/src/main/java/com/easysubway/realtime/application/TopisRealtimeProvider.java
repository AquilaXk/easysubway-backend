package com.easysubway.realtime.application;

import com.easysubway.realtime.domain.RealtimeArrival;
import com.easysubway.realtime.domain.RealtimeTrainPosition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class TopisRealtimeProvider implements RealtimeProvider {

	private static final URI TOPIS_BASE_URI = URI.create("http://swopenapi.seoul.go.kr/api/subway/");
	private static final Duration REQUEST_TIMEOUT = Duration.ofMillis(1500);
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
		JsonNode payload = request("realtimeStationArrival/0/5/%s".formatted(pathSegment(query.stationQueryName())));
		return arrivalsFromPayload(payload, query);
	}

	List<RealtimeArrival> arrivalsFromPayload(JsonNode payload, RealtimeQuery query) {
		JsonNode items = payload.path("realtimeArrivalList");
		if (!items.isArray()) {
			return List.of();
		}
		List<RealtimeArrival> arrivals = new ArrayList<>();
		for (JsonNode item : items) {
			String arvlMsg2 = stringOrEmpty(item, "arvlMsg2");
			Integer barvlDt = positiveInt(item, "barvlDt");
			Integer etaSeconds = barvlDt != null ? barvlDt : parseEtaFromMessage(arvlMsg2);
			arrivals.add(new RealtimeArrival(
				stringOrFallback(item, "subwayId", query.lineId()),
				stringOrFallback(item, "statnNm", query.stationQueryName()),
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
		return List.copyOf(arrivals);
	}

	@Override
	public List<RealtimeTrainPosition> trainPositions(RealtimeQuery query) {
		if (serviceKey.isBlank()) {
			throw new RealtimeProviderException("PROVIDER_UNAVAILABLE");
		}
		JsonNode payload = request("realtimePosition/0/10/%s".formatted(pathSegment(query.lineName())));
		JsonNode items = payload.path("realtimePositionList");
		if (!items.isArray()) {
			return List.of();
		}
		List<RealtimeTrainPosition> positions = new ArrayList<>();
		for (JsonNode item : items) {
			positions.add(new RealtimeTrainPosition(
				stringOrFallback(item, "subwayId", query.lineId()),
				stringOrEmpty(item, "statnNm"),
				stringOrEmpty(item, "trainNo"),
				stringOrEmpty(item, "trainSttus"),
				stringOrEmpty(item, "updnLine"),
				stringOrEmpty(item, "statnTnm"),
				stringOrEmpty(item, "recptnDt")
			));
		}
		return List.copyOf(positions);
	}

	private JsonNode request(String capabilityPath) {
		URI uri = TOPIS_BASE_URI.resolve("%s/json/%s".formatted(pathSegment(serviceKey), capabilityPath));
		HttpRequest request = HttpRequest.newBuilder(uri)
			.timeout(REQUEST_TIMEOUT)
			.GET()
			.build();
		try {
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (response.statusCode() == 429) {
				throw new RealtimeProviderException("PROVIDER_QUOTA_EXCEEDED");
			}
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				throw new RealtimeProviderException("PROVIDER_UNAVAILABLE");
			}
			JsonNode payload = objectMapper.readTree(response.body());
			validateTopisStatus(payload);
			return payload;
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

	void validateTopisStatus(JsonNode payload) {
		String code = stringOrEmpty(payload.path("errorMessage"), "code");
		if (code.isBlank() || "INFO-000".equals(code)) {
			return;
		}
		if ("INFO-200".equals(code)) {
			return;
		}
		throw new RealtimeProviderException("PROVIDER_UNAVAILABLE");
	}

	private String pathSegment(String value) {
		return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
	}

	private String stringOrFallback(JsonNode node, String fieldName, String fallback) {
		String value = stringOrEmpty(node, fieldName);
		return value.isBlank() ? fallback : value;
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

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
final class TopisRealtimeProvider implements RealtimeProvider {

	private static final URI TOPIS_BASE_URI = URI.create("http://swopenapi.seoul.go.kr/api/subway/");
	private static final Duration REQUEST_TIMEOUT = Duration.ofMillis(1500);

	private final String serviceKey;
	private final ObjectMapper objectMapper;
	private final HttpClient httpClient;
	private final RealtimeProvider fallbackProvider;
	private final boolean fixtureEnabled;

	@Autowired
	TopisRealtimeProvider(
		@Value("${EASYSUBWAY_SEOUL_TOPIS_SERVICE_KEY:}") String serviceKey,
		ObjectMapper objectMapper,
		@Value("${EASYSUBWAY_SEOUL_TOPIS_FIXTURE_ENABLED:false}") boolean fixtureEnabled,
		@Value("${EASYSUBWAY_DEPLOY_ENV:}") String deployEnv,
		Environment environment
	) {
		this(
			serviceKey,
			objectMapper,
			HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build(),
			new FixtureRealtimeProvider(),
			fixtureEnabled,
			deployEnv,
			runtimeProfiles(environment)
		);
	}

	TopisRealtimeProvider(
		String serviceKey,
		ObjectMapper objectMapper,
		HttpClient httpClient,
		RealtimeProvider fallbackProvider
	) {
		this(serviceKey, objectMapper, httpClient, fallbackProvider, false);
	}

	TopisRealtimeProvider(
		String serviceKey,
		ObjectMapper objectMapper,
		HttpClient httpClient,
		RealtimeProvider fallbackProvider,
		boolean fixtureEnabled
	) {
		this(serviceKey, objectMapper, httpClient, fallbackProvider, fixtureEnabled, "", "");
	}

	TopisRealtimeProvider(
		String serviceKey,
		ObjectMapper objectMapper,
		HttpClient httpClient,
		RealtimeProvider fallbackProvider,
		boolean fixtureEnabled,
		String deployEnv,
		String activeProfiles
	) {
		this.serviceKey = serviceKey == null ? "" : serviceKey.trim();
		this.objectMapper = objectMapper;
		this.httpClient = httpClient;
		this.fallbackProvider = fallbackProvider;
		this.fixtureEnabled = fixtureEnabled;
		if (this.fixtureEnabled && !fixtureAllowedRuntime(deployEnv, activeProfiles)) {
			throw new IllegalStateException("TOPIS fixture is only allowed in local/dev/test runtime.");
		}
	}

	@Override
	public List<RealtimeArrival> arrivals(RealtimeQuery query) {
		if (serviceKey.isBlank()) {
			if (!fixtureEnabled) {
				throw new RealtimeProviderException("PROVIDER_UNAVAILABLE");
			}
			return fallbackProvider.arrivals(query);
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
			arrivals.add(new RealtimeArrival(
				stringOrFallback(item, "subwayId", query.lineId()),
				stringOrFallback(item, "statnNm", query.stationQueryName()),
				destination(item),
				stringOrEmpty(item, "updnLine"),
				stringOrEmpty(item, "btrainNo"),
				optionalInt(item, "barvlDt"),
				stringOrEmpty(item, "arvlMsg2"),
				stringOrEmpty(item, "arvlMsg3"),
				stringOrEmpty(item, "recptnDt")
			));
		}
		return List.copyOf(arrivals);
	}

	@Override
	public List<RealtimeTrainPosition> trainPositions(RealtimeQuery query) {
		if (serviceKey.isBlank()) {
			if (!fixtureEnabled) {
				throw new RealtimeProviderException("PROVIDER_UNAVAILABLE");
			}
			return fallbackProvider.trainPositions(query);
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

	private Integer optionalInt(JsonNode node, String fieldName) {
		JsonNode value = node.path(fieldName);
		if (value.isInt()) {
			return value.asInt();
		}
		if (value.isTextual()) {
			try {
				return Integer.parseInt(value.asText());
			} catch (NumberFormatException exception) {
				return null;
			}
		}
		return null;
	}

	private boolean fixtureAllowedRuntime(String deployEnv, String activeProfiles) {
		String runtime = "%s,%s".formatted(
			firstNonBlank(deployEnv, propertyOrEnv("EASYSUBWAY_DEPLOY_ENV")),
			firstNonBlank(activeProfiles, propertyOrEnv("spring.profiles.active"))
		);
		return !containsAny(runtime, "prod", "staging", "release", "prod-like")
			&& containsAny(runtime, "local", "dev", "test");
	}

	private static String runtimeProfiles(Environment environment) {
		String[] activeProfiles = environment.getActiveProfiles();
		if (activeProfiles.length > 0) {
			return String.join(",", activeProfiles);
		}
		return String.join(",", environment.getDefaultProfiles());
	}

	private String propertyOrEnv(String key) {
		String property = System.getProperty(key, "");
		if (!property.isBlank()) {
			return property;
		}
		String envKey = key.toUpperCase().replace('.', '_').replace('-', '_');
		return System.getenv().getOrDefault(key, System.getenv().getOrDefault(envKey, ""));
	}

	private String firstNonBlank(String first, String second) {
		return first == null || first.isBlank() ? second : first;
	}

	private boolean containsAny(String value, String... targets) {
		List<String> targetList = List.of(targets);
		for (String item : value.split(",")) {
			String normalized = item.trim().toLowerCase();
			if (targetList.contains(normalized)) {
				return true;
			}
		}
		return false;
	}
}

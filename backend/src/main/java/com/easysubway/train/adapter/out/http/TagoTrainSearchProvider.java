package com.easysubway.train.adapter.out.http;

import com.easysubway.train.application.TrainSearchProvider;
import com.easysubway.train.application.TrainSearchProvider.Catalog;
import com.easysubway.train.application.TrainSearchProvider.ProviderFailure;
import com.easysubway.train.application.TrainSearchProviderCallBudget;
import com.easysubway.train.domain.TrainSearchModels.Journey;
import com.easysubway.train.domain.TrainSearchModels.LegQuery;
import com.easysubway.train.domain.TrainSearchModels.Station;
import com.easysubway.train.domain.TrainSearchModels.TrainType;
import com.easysubway.train.domain.TrainSearchScopePolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class TagoTrainSearchProvider implements TrainSearchProvider {

	private static final URI DEFAULT_BASE_URI = URI.create("https://apis.data.go.kr/1613000/TrainInfo/");
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
	private static final Duration DEFAULT_SEARCH_BUDGET = Duration.ofSeconds(30);
	private static final Duration DEFAULT_CATALOG_BUDGET = Duration.ofMinutes(5);
	private static final Duration RETRY_DELAY = Duration.ofMillis(250);
	private static final int PAGE_SIZE = 100;
	private static final int MAX_TOTAL_COUNT = 1_000;
	private static final ZoneId PROVIDER_ZONE = ZoneId.of("Asia/Seoul");
	private static final DateTimeFormatter PROVIDER_TIME = DateTimeFormatter.ofPattern("uuuuMMddHHmmss")
		.withResolverStyle(ResolverStyle.STRICT);
	private static final DateTimeFormatter PROVIDER_DATE = DateTimeFormatter.ofPattern("uuuuMMdd");
	private static final Map<String, String> TRAIN_TYPES = Map.ofEntries(
		Map.entry("KTX", "KTX"),
		Map.entry("KTX산천", "KTX_SANCHEON"),
		Map.entry("SRT", "SRT"),
		Map.entry("ITX마음", "ITX_MAUM"),
		Map.entry("ITX새마을", "ITX_SAEMAEUL"),
		Map.entry("ITX청춘", "ITX_CHEONGCHUN"),
		Map.entry("새마을", "SAEMAEUL"),
		Map.entry("새마을호", "SAEMAEUL"),
		Map.entry("무궁화", "MUGUNGHWA"),
		Map.entry("무궁화호", "MUGUNGHWA"),
		Map.entry("누리로", "NURIRO")
	);

	private final String serviceKey;
	private final ObjectMapper objectMapper;
	private final HttpClient httpClient;
	private final Clock clock;
	private final URI baseUri;
	private final TrainSearchProviderCallBudget callBudget;
	private final Duration retryDelay;

	@Autowired
	TagoTrainSearchProvider(
		@Value("${EASYSUBWAY_TAGO_TRAIN_SERVICE_KEY:}") String serviceKey,
		ObjectMapper objectMapper,
		TrainSearchProviderCallBudget callBudget
	) {
		this(
			serviceKey,
			objectMapper,
			HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build(),
			Clock.systemUTC(),
			DEFAULT_BASE_URI,
			callBudget,
			RETRY_DELAY
		);
	}

	TagoTrainSearchProvider(String serviceKey, ObjectMapper objectMapper, HttpClient httpClient, Clock clock) {
		this(serviceKey, objectMapper, httpClient, clock, DEFAULT_BASE_URI, () -> {}, RETRY_DELAY);
	}

	TagoTrainSearchProvider(
		String serviceKey,
		ObjectMapper objectMapper,
		HttpClient httpClient,
		Clock clock,
		URI baseUri
	) {
		this(serviceKey, objectMapper, httpClient, clock, baseUri, () -> {}, RETRY_DELAY);
	}

	TagoTrainSearchProvider(
		String serviceKey,
		ObjectMapper objectMapper,
		HttpClient httpClient,
		Clock clock,
		URI baseUri,
		TrainSearchProviderCallBudget callBudget
	) {
		this(serviceKey, objectMapper, httpClient, clock, baseUri, callBudget, RETRY_DELAY);
	}

	TagoTrainSearchProvider(
		String serviceKey,
		ObjectMapper objectMapper,
		HttpClient httpClient,
		Clock clock,
		URI baseUri,
		TrainSearchProviderCallBudget callBudget,
		Duration retryDelay
	) {
		this.serviceKey = decodedServiceKey(serviceKey);
		this.objectMapper = objectMapper;
		this.httpClient = httpClient;
		this.clock = clock;
		this.baseUri = baseUri;
		this.callBudget = callBudget;
		this.retryDelay = retryDelay;
	}

	@Override
	public Catalog catalog() {
		return catalog(clock.instant().plus(DEFAULT_CATALOG_BUDGET));
	}

	@Override
	public Catalog catalog(Instant deadline) {
		try {
			return loadCatalog(deadline);
		} catch (ProviderFailure failure) {
			throw failure;
		} catch (RuntimeException exception) {
			throw new ProviderFailure("TRAIN_SEARCH_PROVIDER_ERROR");
		}
	}

	private Catalog loadCatalog(Instant deadline) {
		List<JsonNode> cities = nonPaginated("GetCtyCodeList", Map.of(), deadline);
		List<JsonNode> grades = nonPaginated("GetVhcleKndList", Map.of(), deadline);
		if (cities.isEmpty() || grades.isEmpty()) {
			throw new ProviderFailure("TRAIN_SEARCH_PROVIDER_ERROR");
		}
		Map<String, Station> stations = new LinkedHashMap<>();
		for (JsonNode city : cities) {
			String cityCode = requiredText(city, "citycode");
			for (JsonNode station : paginated("GetCtyAcctoTrainSttnList", Map.of("cityCode", cityCode), deadline)) {
				String id = requiredText(station, "nodeid");
				Station candidate = new Station(id, requiredText(station, "nodename"));
				Station existing = stations.putIfAbsent(id, candidate);
				if (existing != null && !existing.equals(candidate)) {
					throw new ProviderFailure("TRAIN_SEARCH_PROVIDER_ERROR");
				}
			}
		}

		Map<String, List<ProviderTrainGrade>> gradesByTrainType = new LinkedHashMap<>();
		for (JsonNode grade : grades) {
			String name = requiredText(grade, "vehiclekndnm");
			String code = trainType(name);
			if (TrainSearchScopePolicy.supportedTrainTypes().contains(code)) {
				gradesByTrainType.computeIfAbsent(code, ignored -> new ArrayList<>())
					.add(new ProviderTrainGrade(name, requiredText(grade, "vehiclekndid")));
			}
		}
		if (stations.isEmpty() || !gradesByTrainType.keySet().equals(TrainSearchScopePolicy.supportedTrainTypes())) {
			throw new ProviderFailure("TRAIN_SEARCH_PROVIDER_ERROR");
		}
		List<TrainType> trainTypes = gradesByTrainType.entrySet().stream()
			.map(entry -> new TrainType(
				entry.getKey(),
				entry.getValue().stream().map(ProviderTrainGrade::name).min(Comparator.naturalOrder()).orElseThrow(),
				entry.getValue().stream().map(ProviderTrainGrade::code).distinct().sorted().toList()
			))
			.sorted(Comparator.comparing(TrainType::code))
			.toList();
		return new Catalog(
			clock.instant(),
			stations.values().stream().sorted(Comparator.comparing(Station::name).thenComparing(Station::id)).toList(),
			trainTypes
		);
	}

	@Override
	public List<Journey> search(LegQuery query) {
		return search(query, clock.instant().plus(DEFAULT_SEARCH_BUDGET));
	}

	@Override
	public List<Journey> search(LegQuery query, Instant deadline) {
		if (query == null
			|| query.departureStationId() == null
			|| query.departureStationId().isBlank()
			|| query.arrivalStationId() == null
			|| query.arrivalStationId().isBlank()
			|| query.departureDate() == null
			|| query.trainType() == null
			|| !TrainSearchScopePolicy.supportedTrainTypes().contains(query.trainType())
			|| normalizeStationName(query.departureStationName()).isBlank()
			|| normalizeStationName(query.arrivalStationName()).isBlank()) {
			throw new ProviderFailure("TRAIN_SEARCH_PROVIDER_ERROR");
		}
		List<String> providerCodes = query.providerTrainGradeCodes().stream()
			.map(String::trim)
			.distinct()
			.sorted()
			.toList();
		if (providerCodes.isEmpty() || providerCodes.stream().anyMatch(String::isBlank)) {
			throw new ProviderFailure("TRAIN_SEARCH_PROVIDER_ERROR");
		}
		Map<String, Journey> unique = new LinkedHashMap<>();
		for (String providerCode : providerCodes) {
			var providerKeys = new HashSet<String>();
			for (Journey journey : search(query, providerCode, deadline)) {
				String key = journeyKey(journey);
				if (!providerKeys.add(key)) {
					throw new ProviderFailure("TRAIN_SEARCH_PROVIDER_ERROR");
				}
				Journey existing = unique.putIfAbsent(key, journey);
				if (existing != null && !existing.equals(journey)) {
					throw new ProviderFailure("TRAIN_SEARCH_PROVIDER_ERROR");
				}
			}
		}
		return unique.values().stream()
			.sorted(Comparator.comparing(Journey::departureAt)
				.thenComparing(Journey::arrivalAt)
				.thenComparing(Journey::trainType)
				.thenComparing(Journey::trainNumber))
			.toList();
	}

	private List<Journey> search(LegQuery query, String providerCode, Instant deadline) {
		try {
			return java.util.stream.Stream.of(query.departureDate(), query.departureDate().plusDays(1))
				.flatMap(calendarDate -> searchCalendarDate(query, providerCode, calendarDate, deadline).stream())
				.sorted(Comparator.comparing(Journey::departureAt)
					.thenComparing(Journey::arrivalAt)
					.thenComparing(Journey::trainType)
					.thenComparing(Journey::trainNumber))
				.toList();
		} catch (ProviderFailure failure) {
			throw failure;
		} catch (RuntimeException exception) {
			throw new ProviderFailure("TRAIN_SEARCH_NO_VALID_ROWS");
		}
	}

	private List<Journey> searchCalendarDate(
		LegQuery query,
		String providerCode,
		LocalDate calendarDate,
		Instant deadline
	) {
		Map<String, String> parameters = Map.of(
			"depPlaceId", query.departureStationId(),
			"arrPlaceId", query.arrivalStationId(),
			"depPlandTime", PROVIDER_DATE.format(calendarDate),
			"trainGradeCode", providerCode
		);
		return paginated("GetStrtpntAlocFndTrainInfo", parameters, deadline).stream()
			.map(row -> journeyForCalendarDate(row, query, calendarDate))
			.filter(journey -> TrainSearchScopePolicy.serviceDay(journey.departureAt()).equals(query.departureDate()))
			.toList();
	}

	private Journey journeyForCalendarDate(JsonNode row, LegQuery query, LocalDate calendarDate) {
		Journey journey = journey(row, query);
		if (!journey.departureAt().toLocalDate().equals(calendarDate)) {
			throw new IllegalArgumentException("TRAIN_SEARCH_NO_VALID_ROWS");
		}
		return journey;
	}

	private String journeyKey(Journey journey) {
		return String.join(
			"|",
			journey.trainType(),
			journey.trainNumber(),
			journey.departureStationId(),
			journey.departureAt().toString(),
			journey.arrivalStationId(),
			journey.arrivalAt().toString()
		);
	}

	List<Journey> parseJourneys(JsonNode payload, LegQuery query) {
		if (!"00".equals(payload.path("response").path("header").path("resultCode").asText())) {
			throw new IllegalArgumentException("TRAIN_SEARCH_PROVIDER_ERROR");
		}
		JsonNode item = payload.path("response").path("body").path("items").path("item");
		List<JsonNode> rows = new ArrayList<>();
		if (item.isArray()) {
			item.forEach(rows::add);
		} else if (item.isObject()) {
			rows.add(item);
		} else if (!item.isMissingNode() && !item.isNull()) {
			throw new IllegalArgumentException("TRAIN_SEARCH_PROVIDER_ERROR");
		}
		List<Journey> journeys = rows.stream().map(row -> journey(row, query)).toList();
		return TrainSearchScopePolicy.retainSupported(journeys, Journey::trainType).stream()
			.flatMap(journey -> journeysForRequestedServiceDay(journey, query.departureDate()))
			.sorted(java.util.Comparator.comparing(Journey::departureAt)
				.thenComparing(Journey::arrivalAt)
				.thenComparing(Journey::trainType)
				.thenComparing(Journey::trainNumber))
			.toList();
	}

	private Journey journey(JsonNode row, LegQuery query) {
		String trainType = trainType(requiredText(row, "traingradename"));
		var departureAt = LocalDateTime.parse(requiredText(row, "depplandtime"), PROVIDER_TIME)
			.atZone(PROVIDER_ZONE).toOffsetDateTime();
		var arrivalAt = LocalDateTime.parse(requiredText(row, "arrplandtime"), PROVIDER_TIME)
			.atZone(PROVIDER_ZONE).toOffsetDateTime();
		long durationMinutes = Duration.between(departureAt, arrivalAt).toMinutes();
		int fare = integer(row, "adultcharge");
		String departureStationName = requiredText(row, "depplacename");
		String arrivalStationName = requiredText(row, "arrplacename");
		if ((query.trainType() != null && !query.trainType().equals(trainType))
			|| !stationNameMatches(query.departureStationName(), departureStationName)
			|| !stationNameMatches(query.arrivalStationName(), arrivalStationName)
			|| durationMinutes <= 0
			|| durationMinutes > Integer.MAX_VALUE
			|| fare < 0) {
			throw new IllegalArgumentException("TRAIN_SEARCH_NO_VALID_ROWS");
		}
		return new Journey(
			canonicalTrainNumber(row),
			trainType,
			query.departureStationId(),
			departureStationName,
			departureAt,
			query.arrivalStationId(),
			arrivalStationName,
			arrivalAt,
			(int) durationMinutes,
			fare
		);
	}

	private String canonicalTrainNumber(JsonNode row) {
		String raw = requiredText(row, "trainno");
		if (!raw.matches("[0-9]+")) {
			throw new IllegalArgumentException("TRAIN_SEARCH_NO_VALID_ROWS");
		}
		String canonical = raw.replaceFirst("^0+", "");
		if (canonical.isEmpty()) {
			throw new IllegalArgumentException("TRAIN_SEARCH_NO_VALID_ROWS");
		}
		return canonical;
	}

	private java.util.stream.Stream<Journey> journeysForRequestedServiceDay(
		Journey journey,
		LocalDate requestedServiceDay
	) {
		LocalDate calendarDay = journey.departureAt().toLocalDate();
		if (TrainSearchScopePolicy.serviceDay(journey.departureAt()).equals(requestedServiceDay)) {
			return java.util.stream.Stream.of(journey);
		}
		if (calendarDay.equals(requestedServiceDay)) {
			return java.util.stream.Stream.empty();
		}
		throw new IllegalArgumentException("TRAIN_SEARCH_NO_VALID_ROWS");
	}

	private boolean stationNameMatches(String expected, String actual) {
		return expected == null || normalizeStationName(expected).equals(normalizeStationName(actual));
	}

	private String normalizeStationName(String value) {
		return value == null
			? ""
			: value.toLowerCase(Locale.KOREAN).replaceAll("[^\\p{L}\\p{N}]+", "");
	}

	private String trainType(String value) {
		String normalized = value.replaceAll("[^0-9A-Za-z가-힣]", "").toUpperCase(Locale.ROOT);
		if (normalized.startsWith("KTX산천")) return "KTX_SANCHEON";
		return TRAIN_TYPES.getOrDefault(normalized, normalized);
	}

	private String requiredText(JsonNode row, String field) {
		JsonNode value = row.path(field);
		if (!value.isTextual() || value.asText().isBlank()) {
			throw new IllegalArgumentException("TRAIN_SEARCH_NO_VALID_ROWS");
		}
		return value.asText().trim();
	}

	private int integer(JsonNode row, String field) {
		JsonNode value = row.path(field);
		if (value.isIntegralNumber() && value.canConvertToInt()) {
			return value.intValue();
		}
		try {
			if (!value.isTextual() || value.asText().isBlank()) {
				throw new NumberFormatException();
			}
			return Integer.parseInt(value.asText().trim());
		} catch (NumberFormatException exception) {
			throw new IllegalArgumentException("TRAIN_SEARCH_NO_VALID_ROWS");
		}
	}

	private List<JsonNode> nonPaginated(String operation, Map<String, String> parameters, Instant deadline) {
		return itemRows(request(operation, parameters, deadline).path("response").path("body"));
	}

	private List<JsonNode> paginated(String operation, Map<String, String> parameters, Instant deadline) {
		List<JsonNode> rows = new ArrayList<>();
		int page = 1;
		Integer expectedTotalCount = null;
		int expectedPages = 1;
		while (page <= expectedPages) {
			Map<String, String> pageParameters = new LinkedHashMap<>(parameters);
			pageParameters.put("pageNo", Integer.toString(page));
			pageParameters.put("numOfRows", Integer.toString(PAGE_SIZE));
			JsonNode body = request(operation, pageParameters, deadline).path("response").path("body");
			int responsePage = requiredInteger(body, "pageNo");
			int responsePageSize = requiredInteger(body, "numOfRows");
			int totalCount = requiredInteger(body, "totalCount");
			if (responsePage != page || responsePageSize != PAGE_SIZE || totalCount > MAX_TOTAL_COUNT) {
				throw new ProviderFailure("TRAIN_SEARCH_PROVIDER_ERROR");
			}
			if (expectedTotalCount == null) {
				expectedTotalCount = totalCount;
				expectedPages = Math.max(1, (totalCount + PAGE_SIZE - 1) / PAGE_SIZE);
			} else if (expectedTotalCount != totalCount) {
				throw new ProviderFailure("TRAIN_SEARCH_PROVIDER_ERROR");
			}
			List<JsonNode> pageRows = itemRows(body);
			int expectedRowsOnPage = Math.min(PAGE_SIZE, totalCount - rows.size());
			if (pageRows.size() != expectedRowsOnPage) {
				throw new ProviderFailure("TRAIN_SEARCH_PROVIDER_ERROR");
			}
			rows.addAll(pageRows);
			page++;
		}
		if (expectedTotalCount == null || rows.size() != expectedTotalCount) {
			throw new ProviderFailure("TRAIN_SEARCH_PROVIDER_ERROR");
		}
		return rows;
	}

	private JsonNode request(String operation, Map<String, String> parameters, Instant deadline) {
		if (serviceKey.isBlank()) {
			throw new ProviderFailure("TRAIN_SEARCH_PROVIDER_ERROR");
		}
		HttpResponse<String> response;
		try {
			response = sendWithOneRetry(uri(operation, parameters), deadline);
		} catch (IOException exception) {
			throw new ProviderFailure("TRAIN_SEARCH_UNAVAILABLE");
		}
		try {
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				throw new ProviderFailure("TRAIN_SEARCH_PROVIDER_ERROR");
			}
			JsonNode payload = objectMapper.readTree(response.body());
			if (!"00".equals(payload.path("response").path("header").path("resultCode").asText())) {
				throw new ProviderFailure("TRAIN_SEARCH_PROVIDER_ERROR");
			}
			return payload;
		} catch (IOException | IllegalArgumentException exception) {
			throw new ProviderFailure("TRAIN_SEARCH_PROVIDER_ERROR");
		}
	}

	private HttpResponse<String> sendWithOneRetry(URI uri, Instant deadline) throws IOException {
		for (int attempt = 0; attempt < 2; attempt++) {
			try {
				callBudget.acquire();
				HttpRequest request = HttpRequest.newBuilder(uri)
					.timeout(requestTimeout(deadline))
					.GET()
					.build();
				HttpResponse<String> response = httpClient.send(
					request,
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
				);
				requestTimeout(deadline);
				if (attempt == 0 && retryable(response.statusCode())) {
					waitBeforeRetry(deadline);
					continue;
				}
				return response;
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new ProviderFailure("TRAIN_SEARCH_UNAVAILABLE");
			} catch (IOException exception) {
				if (attempt == 1) {
					throw exception;
				}
				waitBeforeRetry(deadline);
			}
		}
		throw new ProviderFailure("TRAIN_SEARCH_PROVIDER_ERROR");
	}

	private Duration requestTimeout(Instant deadline) {
		Duration remaining = Duration.between(clock.instant(), deadline);
		if (remaining.isZero() || remaining.isNegative()) {
			throw new ProviderFailure("TRAIN_SEARCH_UNAVAILABLE");
		}
		return remaining.compareTo(REQUEST_TIMEOUT) < 0 ? remaining : REQUEST_TIMEOUT;
	}

	private boolean retryable(int status) {
		return status == 408 || status == 429 || status >= 500;
	}

	private void waitBeforeRetry(Instant deadline) {
		try {
			Duration remaining = Duration.between(clock.instant(), deadline);
			if (remaining.compareTo(retryDelay) <= 0) {
				throw new ProviderFailure("TRAIN_SEARCH_UNAVAILABLE");
			}
			Thread.sleep(retryDelay);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new ProviderFailure("TRAIN_SEARCH_UNAVAILABLE");
		}
	}

	private URI uri(String operation, Map<String, String> parameters) {
		Map<String, String> query = new LinkedHashMap<>();
		query.put("serviceKey", serviceKey);
		query.put("_type", "json");
		query.putAll(parameters);
		String encodedQuery = query.entrySet().stream()
			.map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
			.collect(java.util.stream.Collectors.joining("&"));
		return URI.create(baseUri.resolve(operation).toString() + "?" + encodedQuery);
	}

	private List<JsonNode> itemRows(JsonNode body) {
		JsonNode item = body.path("items").path("item");
		List<JsonNode> rows = new ArrayList<>();
		if (item.isArray()) {
			item.forEach(rows::add);
		} else if (item.isObject()) {
			rows.add(item);
		} else if (!item.isMissingNode() && !item.isNull()) {
			throw new ProviderFailure("TRAIN_SEARCH_PROVIDER_ERROR");
		}
		return rows;
	}

	private int requiredInteger(JsonNode row, String field) {
		JsonNode value = row.path(field);
		if (value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= 0) {
			return value.intValue();
		}
		if (value.isTextual() && value.textValue().matches("[0-9]+")) {
			try {
				return Integer.parseInt(value.textValue());
			} catch (NumberFormatException ignored) {
				// Fall through to the stable provider error for values outside the int range.
			}
		}
		throw new ProviderFailure("TRAIN_SEARCH_PROVIDER_ERROR");
	}

	private static String decodedServiceKey(String value) {
		String trimmed = value == null ? "" : value.trim();
		return trimmed.matches(".*%[0-9A-Fa-f]{2}.*")
			? URLDecoder.decode(trimmed, StandardCharsets.UTF_8)
			: trimmed;
	}

	private String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
	}

	private record ProviderTrainGrade(String name, String code) {}
}

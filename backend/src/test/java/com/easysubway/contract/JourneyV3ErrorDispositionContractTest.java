package com.easysubway.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Journey V3 user-error disposition contract")
class JourneyV3ErrorDispositionContractTest {

	private static final Path CONTRACTS = Path.of("..", "contracts", "api");
	private static final Path CATALOG = CONTRACTS.resolve("journey-v3-error-catalog.json");
	private static final Path DISPOSITION = CONTRACTS.resolve("journey-v3-error-disposition.json");
	private static final Path DIGESTS = CONTRACTS.resolve("journey-v3-contract-digests.json");
	private static final ObjectMapper JSON = new ObjectMapper();

	private static final List<ExpectedDisposition> EXPECTED = List.of(
		entry("searchJourneys", 400, "INVALID_JOURNEY_REQUEST", "REQUEST_CORRECTION",
			"출발역·도착역과 이동 조건을 확인해 주세요.", "journey.action.editRequest"),
		entry("searchJourneys", 404, "STATION_NOT_FOUND", "REQUEST_CORRECTION",
			"선택한 역 정보를 찾을 수 없어요.", "journey.action.reselectStation"),
		entry("searchJourneys", 422, "ROUTE_NOT_FOUND", "ROUTE_ABSENT",
			"현재 조건에 맞는 경로가 없어요.", "journey.action.editRequest"),
		entry("searchJourneys", 422, "ACCESSIBILITY_CONSTRAINT_UNSATISFIED", "ACCESSIBILITY_UNSATISFIED",
			"요청한 접근성 조건을 만족하는 검증된 경로가 없어요.", "journey.action.editAccessibility"),
		entry("searchJourneys", 503, "ROUTING_BUNDLE_UNAVAILABLE", "ROUTING_DATA_UNAVAILABLE",
			"경로 데이터를 준비하지 못했어요.", "journey.action.newSearch"),
		entry("searchJourneys", 503, "ROUTING_BUNDLE_STALE", "ROUTING_DATA_STALE",
			"최신 경로 데이터를 확인할 수 없어요.", "journey.action.newSearch"),
		entry("searchJourneys", 503, "TIMETABLE_UNAVAILABLE", "ROUTING_DATA_UNAVAILABLE",
			"시간표를 확인할 수 없어요.", "journey.action.newSearch"),
		entry("searchJourneys", 503, "TIMETABLE_STALE", "ROUTING_DATA_STALE",
			"최신 시간표를 확인할 수 없어요.", "journey.action.newSearch"),
		entry("searchJourneys", 503, "REALTIME_REQUIRED_UNAVAILABLE", "ROUTING_DATA_UNAVAILABLE",
			"필요한 실시간 정보를 확인할 수 없어요.", "journey.action.newSearch"),
		entry("searchJourneys", 503, "ROUTING_IDENTITY_MISMATCH", "ROUTING_IDENTITY_FAILURE",
			"경로 데이터 확인 중 문제가 발생했어요.", "journey.action.newSearch"),
		entry("searchJourneys", 503, "ROUTE_SERVICE_UNAVAILABLE", "SERVICE_UNAVAILABLE",
			"경로를 불러오지 못했어요. 잠시 후 다시 검색해 주세요.", "journey.action.newSearch"),
		entry("searchJourneys", 504, "JOURNEY_SEARCH_TIMEOUT", "SEARCH_TIMEOUT",
			"경로 검색 시간이 초과되었어요. 다시 검색해 주세요.", "journey.action.newSearch"),
		entry("searchJourneys", 401, "ROUTE_SESSION_REQUIRED", "SESSION_AUTHENTICATION",
			"경로 검색 인증이 만료되었어요.", "journey.action.reauthenticate"),
		entry("searchJourneys", 429, "ROUTE_RATE_LIMITED", "RATE_LIMIT",
			"요청이 많아요. 잠시 후 다시 검색해 주세요.", null),
		entry("searchStationTimetables", 400, "INVALID_JOURNEY_REQUEST", "REQUEST_CORRECTION",
			"역과 노선, 조회 조건을 확인해 주세요.", "journey.action.editRequest"),
		entry("searchStationTimetables", 404, "STATION_LINE_NOT_FOUND", "REQUEST_CORRECTION",
			"선택한 역의 노선 정보를 찾을 수 없어요.", "journey.action.reselectStation"),
		entry("searchStationTimetables", 404, "TIMETABLE_NOT_COVERED", "ROUTE_ABSENT",
			"선택한 역의 노선 시간표가 아직 준비되지 않았어요.", "journey.action.reselectStation"),
		entry("searchStationTimetables", 503, "TIMETABLE_UNAVAILABLE", "ROUTING_DATA_UNAVAILABLE",
			"시간표를 확인할 수 없어요.", "journey.action.newSearch"),
		entry("searchStationTimetables", 503, "TIMETABLE_STALE", "ROUTING_DATA_STALE",
			"최신 시간표를 확인할 수 없어요.", "journey.action.newSearch"),
		entry("searchStationTimetables", 503, "TIMETABLE_IDENTITY_MISMATCH", "ROUTING_IDENTITY_FAILURE",
			"시간표 데이터 확인 중 문제가 발생했어요.", "journey.action.newSearch"),
		entry("searchStationTimetables", 401, "ROUTE_SESSION_REQUIRED", "SESSION_AUTHENTICATION",
			"경로 검색 인증이 만료되었어요.", "journey.action.reauthenticate"),
		entry("searchStationTimetables", 429, "ROUTE_RATE_LIMITED", "RATE_LIMIT",
			"요청이 많아요. 잠시 후 다시 검색해 주세요.", null),
		entry("issueJourneySession", 400, "INVALID_JOURNEY_SESSION_REQUEST", "SESSION_AUTHENTICATION",
			"경로 검색 인증 요청을 확인할 수 없어요.", "journey.action.reauthenticate"),
		entry("issueJourneySession", 403, "ROUTE_SESSION_ATTESTATION_REJECTED", "SESSION_AUTHENTICATION",
			"기기 인증을 확인하지 못했어요.", "journey.action.reauthenticate"),
		entry("issueJourneySession", 503, "ROUTE_SESSION_ATTESTATION_UNAVAILABLE", "SERVICE_UNAVAILABLE",
			"기기 인증 서비스를 지금 사용할 수 없어요.", "journey.action.newSearch")
	);

	@Test
	@DisplayName("closed disposition binds every catalog pair to exact mobile-safe presentation")
	void dispositionIsClosedAndExact() throws IOException {
		JsonNode disposition = JSON.readTree(DISPOSITION.toFile());
		assertThat(fieldNames(disposition)).containsExactlyInAnyOrder(
			"schemaVersion", "artifactKind", "sourceCatalog", "entries");
		assertThat(disposition.path("schemaVersion").asText()).isEqualTo("JOURNEY_ERROR_DISPOSITION_V1");
		assertThat(disposition.path("artifactKind").asText()).isEqualTo("journey-v3-error-disposition");

		JsonNode sourceCatalog = disposition.path("sourceCatalog");
		assertThat(fieldNames(sourceCatalog)).containsExactlyInAnyOrder("path", "schemaVersion", "sha256");
		assertThat(sourceCatalog.path("path").asText()).isEqualTo("journey-v3-error-catalog.json");
		assertThat(sourceCatalog.path("schemaVersion").asText()).isEqualTo("JOURNEY_ERROR_CATALOG_V1");
		assertThat(sourceCatalog.path("sha256").asText()).isEqualTo(sha256(Files.readAllBytes(CATALOG)));
		assertThat(disposition.path("entries").isArray()).isTrue();

		List<ExpectedDisposition> actual = new ArrayList<>();
		Set<String> publicMessageKeys = new LinkedHashSet<>();
		Set<String> mobileResourceKeys = new LinkedHashSet<>();
		Set<String> safeDiagnosticKeys = new LinkedHashSet<>();
		for (JsonNode entry : disposition.path("entries")) {
			assertThat(fieldNames(entry)).containsExactlyInAnyOrder(
				"operation", "httpStatus", "machineCode", "semanticCategory", "exposure", "userVisible",
				"publicMessageKey", "canonicalKoreanCopy", "mobileResourceKey", "mobilePresentation",
				"retryDisposition", "primaryActionKey", "secondaryActionKey", "safeDiagnosticKey",
				"sensitiveDetailPolicy");
			assertThat(entry.path("httpStatus").isIntegralNumber()).isTrue();
			assertThat(entry.path("userVisible").isBoolean()).isTrue();
			actual.add(new ExpectedDisposition(
				entry.path("operation").asText(), entry.path("httpStatus").asInt(), entry.path("machineCode").asText(),
				entry.path("semanticCategory").asText(), entry.path("canonicalKoreanCopy").asText(),
				entry.path("primaryActionKey").isNull() ? null : entry.path("primaryActionKey").asText()));
			assertThat(entry.path("exposure").asText()).isEqualTo("MOBILE_USER_VISIBLE");
			assertThat(entry.path("userVisible").asBoolean()).isTrue();
			assertThat(entry.path("mobilePresentation").asText()).isEqualTo("FAILURE_SCREEN");
			assertThat(entry.path("retryDisposition").asText()).isEqualTo("FORBIDDEN");
			assertThat(entry.path("secondaryActionKey").isNull()).isTrue();
			assertThat(entry.path("sensitiveDetailPolicy").asText()).isEqualTo("NEVER_PUBLIC");
			String operation = entry.path("operation").asText();
			String machineCode = entry.path("machineCode").asText();
			String publicMessageKey = entry.path("publicMessageKey").asText();
			String mobileResourceKey = entry.path("mobileResourceKey").asText();
			String safeDiagnosticKey = entry.path("safeDiagnosticKey").asText();
			assertThat(publicMessageKey).isEqualTo("journey.error." + machineCode.toLowerCase(Locale.ROOT));
			assertThat(safeDiagnosticKey).isEqualTo("journey.diagnostic." + machineCode.toLowerCase(Locale.ROOT));
			assertThat(safeDiagnosticKey).isNotEqualTo(publicMessageKey);
			assertThat(mobileResourceKey).isEqualTo(mobileResourceKey(machineCode));
			// A Journey V3 machine code may be shared by distinct operations. Resource
			// keys are therefore unique within the operation that owns their presentation.
			assertThat(publicMessageKeys.add(operation + "\u0000" + publicMessageKey)).isTrue();
			assertThat(mobileResourceKeys.add(operation + "\u0000" + mobileResourceKey)).isTrue();
			assertThat(safeDiagnosticKeys.add(operation + "\u0000" + safeDiagnosticKey)).isTrue();
			assertPublicSurfaceIsSafe(entry);
		}
		assertThat(actual).containsExactlyElementsOf(EXPECTED);
		assertThat(new LinkedHashSet<>(actual)).hasSameSizeAs(EXPECTED);
		assertThat(catalogPairs("applicationErrors")).containsExactlyElementsOf(expectedCatalogPairs(true));
		assertThat(catalogPairs("ingressErrors")).containsExactlyElementsOf(expectedCatalogPairs(false));
	}

	@Test
	@DisplayName("digest artifact binds the disposition raw bytes in bytewise path order")
	void digestBindsDispositionRawBytes() throws IOException {
		JsonNode digest = JSON.readTree(DIGESTS.toFile());
		List<String> paths = new ArrayList<>();
		assertThat(digest.path("artifacts").isArray()).isTrue();
		for (JsonNode artifact : digest.path("artifacts")) {
			assertThat(fieldNames(artifact)).containsExactlyInAnyOrder("path", "sha256");
			String path = artifact.path("path").asText();
			paths.add(path);
			assertThat(artifact.path("sha256").asText()).isEqualTo(sha256(Files.readAllBytes(CONTRACTS.resolve(path))));
		}
		assertThat(paths).containsExactly("journey-v3-error-catalog.json", "journey-v3-error-disposition.json",
			"journey-v3-session-integrity.json", "journey-v3.openapi.yaml");
	}

	private static ExpectedDisposition entry(
		String operation, int httpStatus, String machineCode, String semanticCategory, String copy, String primaryActionKey
	) {
		return new ExpectedDisposition(operation, httpStatus, machineCode, semanticCategory, copy, primaryActionKey);
	}

	private static List<ErrorPair> catalogPairs(String field) throws IOException {
		JsonNode catalog = JSON.readTree(CATALOG.toFile());
		List<ErrorPair> pairs = new ArrayList<>();
		assertThat(catalog.path(field).isArray()).isTrue();
		for (JsonNode entry : catalog.path(field)) {
			assertThat(fieldNames(entry)).containsExactlyInAnyOrder("operation", "httpStatus", "code");
			assertThat(entry.path("httpStatus").isIntegralNumber()).isTrue();
			pairs.add(new ErrorPair(entry.path("operation").asText(), entry.path("httpStatus").asInt(), entry.path("code").asText()));
		}
		return pairs;
	}

	private static List<ErrorPair> expectedCatalogPairs(boolean application) {
		return EXPECTED.stream()
			.filter(entry -> application == isApplicationError(entry))
			.map(ExpectedDisposition::pair)
			.toList();
	}

	private static boolean isApplicationError(ExpectedDisposition entry) {
		return switch (entry.operation()) {
			case "searchJourneys", "searchStationTimetables" -> entry.httpStatus() != 401 && entry.httpStatus() != 429;
			case "issueJourneySession" -> false;
			default -> throw new IllegalArgumentException("unknown Journey V3 operation: " + entry.operation());
		};
	}

	private static String mobileResourceKey(String machineCode) {
		StringBuilder result = new StringBuilder("journeyError");
		for (String token : machineCode.split("_")) {
			result.append(token.charAt(0)).append(token.substring(1).toLowerCase(Locale.ROOT));
		}
		return result.toString();
	}

	private static void assertPublicSurfaceIsSafe(JsonNode entry) {
		String value = String.join(" ", entry.path("publicMessageKey").asText(), entry.path("canonicalKoreanCopy").asText(),
			entry.path("mobileResourceKey").asText(), entry.path("primaryActionKey").isNull() ? "" : entry.path("primaryActionKey").asText(),
			entry.path("safeDiagnosticKey").asText()).toLowerCase(Locale.ROOT);
		assertThat(value).doesNotContain(
			"http", "provider", "url", "path", "raw body", "exception", "stack", "token", "session=", "sha256", "digest", "/", "\\");
	}

	private static Set<String> fieldNames(JsonNode node) {
		Set<String> names = new LinkedHashSet<>();
		node.fieldNames().forEachRemaining(names::add);
		return names;
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 unavailable", exception);
		}
	}

	private record ErrorPair(String operation, int httpStatus, String machineCode) {
	}

	private record ExpectedDisposition(
		String operation, int httpStatus, String machineCode, String semanticCategory, String canonicalKoreanCopy,
		String primaryActionKey
	) {
		private ErrorPair pair() {
			return new ErrorPair(operation, httpStatus, machineCode);
		}
	}
}

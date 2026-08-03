package com.easysubway.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Legacy Android public-operation inventory contract")
class LegacyAndroidPublicOperationInventoryContractTest {

	private static final Path CONTRACTS = Path.of("..", "contracts", "api");
	private static final Path INVENTORY = CONTRACTS.resolve("legacy-android-public-operation-inventory.json");
	private static final Path INTERNAL_API_INDEX = CONTRACTS.resolve("internal-api-index.json");
	private static final ObjectMapper JSON = new ObjectMapper();
	private static final List<String> INVENTORY_PATH_PREFIXES = List.of(
		"/api/v1/routes/", "/api/v2/routes/", "/api/v1/realtime/", "/api/v1/report", "/api/v1/me/favorites/");

	private static final List<ExpectedEntry> EXPECTED = List.of(
		entry("POST", "/api/v1/routes/search", "route-v1-search", "ROUTE", "MOBILE_CONSUMED_BACKEND_EXPOSED", "REPLACE_WITH_JOURNEY_V3", "apps/mobile/lib/route_search.dart#_routeSearchErrorMessage"),
		entry("POST", "/api/v2/routes/session", "route-v2-session", "ROUTE", "MOBILE_CONSUMED_BACKEND_EXPOSED", "REPLACE_WITH_JOURNEY_V3", "apps/mobile/lib/route_v2_ingress.dart#RouteSearchOnlineException"),
		entry("POST", "/api/v2/routes/search", "route-v2-search", "ROUTE", "MOBILE_CONSUMED_BACKEND_EXPOSED", "REPLACE_WITH_JOURNEY_V3", "apps/mobile/lib/route_search.dart#_routeOnlineSearchErrorMessage"),
		entry("POST", "/api/v2/routes/{routeSearchId}/refresh", "route-v2-refresh", "ROUTE", "MOBILE_CONSUMED_BACKEND_EXPOSED", "REPLACE_WITH_JOURNEY_V3", "apps/mobile/lib/route_search.dart#_routeRefreshErrorMessage"),
		entry("POST", "/api/v1/routes/{routeSearchId}/feedback", "route-v1-feedback", "ROUTE", "MOBILE_CONSUMED_BACKEND_UNMAPPED", "RECONCILE_ORPHAN", "apps/mobile/lib/route_search.dart#_routeFeedbackErrorMessage"),
		entry("GET", "/api/v1/realtime/arrivals", "realtime-arrivals", "REALTIME", "MOBILE_CONSUMED_BACKEND_EXPOSED", "REVIEW_NON_ROUTE", "apps/mobile/lib/features/realtime/realtime_repository.dart#RealtimeException"),
		entry("GET", "/api/v1/realtime/train-positions", "realtime-train-positions", "REALTIME", "BACKEND_EXPOSED_MOBILE_UNCONSUMED", "REVIEW_NON_ROUTE", null),
		entry("POST", "/api/v1/report-uploads", "report-upload-intent", "REPORT", "MOBILE_CONSUMED_BACKEND_EXPOSED", "RETAIN_NON_ROUTE", "apps/mobile/lib/facility_report.dart#_facilityReportErrorMessage"),
		entry("PUT", "/api/v1/report-uploads/{uploadId}", "report-upload-by-intent", "REPORT", "MOBILE_CONDITIONAL_BACKEND_EXPOSED", "RETAIN_NON_ROUTE", "apps/mobile/lib/facility_report.dart#_facilityReportErrorMessage"),
		entry("POST", "/api/v1/reports", "report-create", "REPORT", "MOBILE_CONSUMED_BACKEND_EXPOSED", "RETAIN_NON_ROUTE", "apps/mobile/lib/facility_report.dart#_facilityReportErrorMessage"),
		entry("GET", "/api/v1/reports/{reportId}", "report-get", "REPORT", "MOBILE_CONSUMED_BACKEND_EXPOSED", "RETAIN_NON_ROUTE", "apps/mobile/lib/facility_report.dart#_facilityReportStatusErrorMessage"),
		entry("POST", "/api/v1/reports/{reportId}/confirm", "report-confirm", "REPORT", "BACKEND_EXPOSED_MOBILE_UNCONSUMED", "RETAIN_NON_ROUTE", null),
		entry("GET", "/api/v1/me/favorites/routes", "favorite-route-list", "FAVORITES", "MOBILE_CONSUMED_BACKEND_UNMAPPED", "RECONCILE_ORPHAN", "apps/mobile/lib/route_search.dart#_favoriteRouteLoadErrorMessage"),
		entry("POST", "/api/v1/me/favorites/routes", "favorite-route-save", "FAVORITES", "MOBILE_CONSUMED_BACKEND_UNMAPPED", "RECONCILE_ORPHAN", "apps/mobile/lib/route_search.dart#_favoriteRouteErrorMessage"),
		entry("DELETE", "/api/v1/me/favorites/routes/{favoriteRouteId}", "favorite-route-delete", "FAVORITES", "MOBILE_CONSUMED_BACKEND_UNMAPPED", "RECONCILE_ORPHAN", "apps/mobile/lib/route_search.dart#_favoriteRouteErrorMessage"),
		entry("GET", "/api/v1/me/favorites/facilities", "favorite-facility-list", "FAVORITES", "MOBILE_CONSUMED_BACKEND_UNMAPPED", "RECONCILE_ORPHAN", "apps/mobile/lib/favorite_facility.dart#_favoriteFacilityLoadErrorMessage"),
		entry("PUT", "/api/v1/me/favorites/facilities/{facilityId}", "favorite-facility-save", "FAVORITES", "MOBILE_CONSUMED_BACKEND_UNMAPPED", "RECONCILE_ORPHAN", "apps/mobile/lib/favorite_facility.dart#_favoriteFacilityChangeErrorMessage"),
		entry("DELETE", "/api/v1/me/favorites/facilities/{facilityId}", "favorite-facility-delete", "FAVORITES", "MOBILE_CONSUMED_BACKEND_UNMAPPED", "RECONCILE_ORPHAN", "apps/mobile/lib/favorite_facility.dart#_favoriteFacilityChangeErrorMessage"),
		entry("GET", "/api/v1/me/favorites/stations", "favorite-station-list", "FAVORITES", "MOBILE_CONSUMED_BACKEND_UNMAPPED", "RECONCILE_ORPHAN", "apps/mobile/lib/features/stations/data/station_api_repository.dart#_favoriteStationLoadErrorMessage"),
		entry("PUT", "/api/v1/me/favorites/stations/{stationId}", "favorite-station-save", "FAVORITES", "MOBILE_CONSUMED_BACKEND_UNMAPPED", "RECONCILE_ORPHAN", "apps/mobile/lib/features/stations/data/station_api_repository.dart#_favoriteStationChangeErrorMessage"),
		entry("DELETE", "/api/v1/me/favorites/stations/{stationId}", "favorite-station-delete", "FAVORITES", "MOBILE_CONSUMED_BACKEND_UNMAPPED", "RECONCILE_ORPHAN", "apps/mobile/lib/features/stations/data/station_api_repository.dart#_favoriteStationChangeErrorMessage")
	);

	@Test
	@DisplayName("snapshot is closed, ordered, and correctly reconciled with the Backend public index")
	void inventoryIsClosedAndExact() throws IOException {
		JsonNode inventory = JSON.readTree(INVENTORY.toFile());
		assertThat(fieldNames(inventory)).containsExactlyInAnyOrder("schemaVersion", "artifactKind", "evidence", "entries");
		assertThat(inventory.path("schemaVersion").isTextual()).isTrue();
		assertThat(inventory.path("artifactKind").isTextual()).isTrue();
		assertThat(inventory.path("evidence").isObject()).isTrue();
		assertThat(inventory.path("schemaVersion").asText()).isEqualTo("LEGACY_ANDROID_PUBLIC_OPERATION_INVENTORY_V1");
		assertThat(inventory.path("artifactKind").asText()).isEqualTo("legacy-android-public-operation-inventory");
		assertThat(fieldNames(inventory.path("evidence"))).containsExactlyInAnyOrder("backendBaseSha", "mobileBaseSha");
		assertThat(inventory.path("evidence").path("backendBaseSha").isTextual()).isTrue();
		assertThat(inventory.path("evidence").path("mobileBaseSha").isTextual()).isTrue();
		assertThat(inventory.path("evidence").path("backendBaseSha").asText()).isEqualTo("d512647eae19a52a13adc31dc5ba72af756edcfb");
		assertThat(inventory.path("evidence").path("mobileBaseSha").asText()).isEqualTo("fd348c38e333597e3f0ec56c509cb1ba41e59cae");
		assertThat(inventory.path("entries").isArray()).isTrue();

		List<ExpectedEntry> actual = new ArrayList<>();
		Set<String> operationNames = new LinkedHashSet<>();
		Set<Operation> methodPaths = new LinkedHashSet<>();
		for (JsonNode entry : inventory.path("entries")) {
			assertThat(fieldNames(entry)).containsExactlyInAnyOrder(
				"method", "path", "operation", "domain", "currentBinding", "migrationDisposition", "mobileMessageSource");
			assertThat(entry.path("method").isTextual()).isTrue();
			assertThat(entry.path("path").isTextual()).isTrue();
			assertThat(entry.path("operation").isTextual()).isTrue();
			assertThat(entry.path("domain").isTextual()).isTrue();
			assertThat(entry.path("currentBinding").isTextual()).isTrue();
			assertThat(entry.path("migrationDisposition").isTextual()).isTrue();
			assertThat(entry.path("mobileMessageSource").isTextual() || entry.path("mobileMessageSource").isNull()).isTrue();
			ExpectedEntry expected = entry(
				entry.path("method").asText(), entry.path("path").asText(), entry.path("operation").asText(),
				entry.path("domain").asText(), entry.path("currentBinding").asText(),
				entry.path("migrationDisposition").asText(),
				entry.path("mobileMessageSource").isNull() ? null : entry.path("mobileMessageSource").asText());
			actual.add(expected);
			assertThat(operationNames.add(expected.operation())).isTrue();
			assertThat(methodPaths.add(expected.methodPath())).isTrue();
			assertThat(expected.domain()).isIn("ROUTE", "REALTIME", "REPORT", "FAVORITES");
			assertThat(expected.currentBinding()).isIn(
				"MOBILE_CONSUMED_BACKEND_EXPOSED", "MOBILE_CONSUMED_BACKEND_UNMAPPED",
				"MOBILE_CONDITIONAL_BACKEND_EXPOSED", "BACKEND_EXPOSED_MOBILE_UNCONSUMED");
			assertThat(expected.migrationDisposition()).isIn(
				"REPLACE_WITH_JOURNEY_V3", "RETAIN_NON_ROUTE", "RECONCILE_ORPHAN", "REVIEW_NON_ROUTE");
			if ("BACKEND_EXPOSED_MOBILE_UNCONSUMED".equals(expected.currentBinding())) {
				assertThat(expected.mobileMessageSource()).isNull();
			} else {
				assertThat(expected.mobileMessageSource()).isNotNull();
			}
		}
		assertThat(actual).containsExactlyElementsOf(EXPECTED);

		Set<Operation> publicOperations = publicOperations();
		Set<Operation> expectedBackendOperations = new LinkedHashSet<>();
		for (ExpectedEntry entry : EXPECTED) {
			if ("MOBILE_CONSUMED_BACKEND_UNMAPPED".equals(entry.currentBinding())) {
				assertThat(publicOperations).doesNotContain(entry.methodPath());
			} else {
				expectedBackendOperations.add(entry.methodPath());
				assertThat(publicOperations).contains(entry.methodPath());
			}
		}
		assertThat(publicOperations).containsExactlyInAnyOrderElementsOf(expectedBackendOperations);
	}

	private static ExpectedEntry entry(
		String method, String path, String operation, String domain, String currentBinding,
		String migrationDisposition, String mobileMessageSource
	) {
		return new ExpectedEntry(method, path, operation, domain, currentBinding, migrationDisposition, mobileMessageSource);
	}

	private static Set<Operation> publicOperations() throws IOException {
		JsonNode index = JSON.readTree(INTERNAL_API_INDEX.toFile());
		Set<Operation> operations = new LinkedHashSet<>();
		for (JsonNode entry : index.path("operations")) {
			String path = entry.path("path").asText();
			if ("PUBLIC_API".equals(entry.path("surface").asText())
				&& INVENTORY_PATH_PREFIXES.stream().anyMatch(path::startsWith)) {
				operations.add(new Operation(entry.path("method").asText(), path));
			}
		}
		return operations;
	}

	private static Set<String> fieldNames(JsonNode node) {
		Set<String> names = new LinkedHashSet<>();
		node.fieldNames().forEachRemaining(names::add);
		return names;
	}

	private record Operation(String method, String path) {
	}

	private record ExpectedEntry(
		String method, String path, String operation, String domain, String currentBinding,
		String migrationDisposition, String mobileMessageSource
	) {
		private Operation methodPath() {
			return new Operation(method, path);
		}
	}
}

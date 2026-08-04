package com.easysubway.route.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Route V2 runtime input inventory contract")
class RouteV2RuntimeInputInventoryContractTest {

	private static final Path INVENTORY = Path.of("..", "contracts", "route", "route-v2-runtime-input-inventory.json");
	private static final ObjectMapper JSON = new ObjectMapper();
	private static final List<ExpectedEntry> EXPECTED = List.of(
		entry("INPUT", "backend/src/main/java/com/easysubway/route/adapter/in/web/RouteSearchController.java", "RouteSearchController#searchRouteV2", "/api/v2/routes/search", "/api/v2/routes/search", "searchRouteV2"),
		entry("INPUT", "backend/src/main/java/com/easysubway/route/adapter/in/web/RouteV2SessionController.java", "RouteV2SessionController#issue", "/api/v2/routes/session", "/api/v2/routes/session", "issue"),
		entry("OPTIONAL_OR_LEGACY", "backend/src/main/java/com/easysubway/route/application/service/RouteV2Planner.java", "RouteV2Planner#search", "optional route timetable/legacy graph branch", "getIfAvailable", "RouteTimetable::empty", "searchRouteAlternatives", "planRouteAlternatives"),
		entry("CACHE", "backend/src/main/java/com/easysubway/route/application/service/RouteV2Planner.java", "RouteV2Planner#timetableSnapshot", "timetable snapshot cache", "cachedTimetableSnapshot", "loadRouteTimetableSnapshot"),
		entry("PLANNER", "backend/src/main/java/com/easysubway/route/application/service/RouteTimetableRaptorPlanner.java", "RouteTimetableRaptorPlanner#searchWithDiagnostics", "RAPTOR timetable search", "searchWithDiagnostics"),
		entry("LOADER", "backend/src/main/java/com/easysubway/route/adapter/out/persistence/JdbcRouteTimetableRepository.java", "JdbcRouteTimetableRepository#loadRouteTimetableSnapshot", "active timetable snapshot load", "loadRouteTimetableSnapshot", "timetable_snapshot_active"),
		entry("REGISTRY", "backend/src/main/java/com/easysubway/route/adapter/out/persistence/JdbcRouteTimetableRepository.java", "JdbcRouteTimetableRepository#activeItxArtifact", "active ITX artifact registry", "activeItxArtifact", "timetable_snapshot_active"),
		entry("LOADER", "backend/src/main/java/com/easysubway/route/adapter/out/persistence/TimetableSeedLoader.java", "TimetableSeedLoader#run", "timetable seed activation", "run", "activateLocked"),
		entry("REGISTRY", "backend/src/main/java/com/easysubway/route/adapter/out/persistence/TimetableSeedLoader.java", "TimetableSeedLoader#activateLocked", "locked timetable snapshot activation", "activateLocked", "timetable_snapshot_lock", "timetable_snapshot_active"),
		entry("REGISTRY", "backend/src/main/java/com/easysubway/route/application/service/ProductionRouteV2Support.java", "ProductionRouteV2Support#requireTimetableArtifact/#requireUsablePlan", "production timetable and plan gate", "requireTimetableArtifact", "requireUsablePlan"),
		entry("OPTIONAL_OR_LEGACY", "backend/src/main/java/com/easysubway/route/application/service/RouteSearchService.java", "RouteSearchService#searchRouteAlternatives/#planRouteAlternatives", "legacy graph alternatives", "searchRouteAlternatives", "planRouteAlternatives"),
		entry("REGISTRY", "backend/src/main/java/com/easysubway/route/adapter/out/persistence/JdbcRouteV2AccessStore.java", "JdbcRouteV2AccessStore#saveState", "ephemeral route V2 state registry", "public void saveState", "INSERT INTO route_v2_states")
	);

	@Test
	@DisplayName("inventory is closed, ordered, production-only, and evidence-backed")
	void inventoryIsClosedAndExact() throws IOException {
		JsonNode inventory = JSON.readTree(INVENTORY.toFile());
		assertThat(fieldNames(inventory)).containsExactlyInAnyOrder("schemaVersion", "artifactKind", "backendBaseSha", "sourceRoots", "entries");
		assertThat(inventory.path("schemaVersion").asText()).isEqualTo("ROUTE_V2_RUNTIME_INPUT_INVENTORY_V1");
		assertThat(inventory.path("artifactKind").asText()).isEqualTo("route-v2-runtime-input-inventory");
		assertThat(inventory.path("backendBaseSha").asText()).isEqualTo("610777a77be82a7fddea43965b2a986d98079abe");
		assertThat(inventory.path("sourceRoots")).extracting(JsonNode::asText)
			.containsExactly("backend/src/main/java/com/easysubway/route/", "backend/src/main/resources/application-prod.yml");

		List<ExpectedEntry> actual = new ArrayList<>();
		Set<String> members = new LinkedHashSet<>();
		for (JsonNode node : inventory.path("entries")) {
			assertThat(fieldNames(node)).containsExactlyInAnyOrder("kind", "sourcePath", "member", "pathOrTrigger", "evidenceTokens", "journeyV3Disposition");
			List<String> evidenceTokens = new ArrayList<>();
			for (JsonNode token : node.path("evidenceTokens")) {
				assertThat(token.isTextual()).isTrue();
				evidenceTokens.add(token.asText());
			}
			ExpectedEntry entry = entry(
				node.path("kind").asText(), node.path("sourcePath").asText(), node.path("member").asText(),
				node.path("pathOrTrigger").asText(), evidenceTokens.toArray(String[]::new));
			actual.add(entry);
			assertThat(members.add(entry.member())).isTrue();
			assertThat(entry.kind()).isIn("INPUT", "PLANNER", "LOADER", "CACHE", "REGISTRY", "OPTIONAL_OR_LEGACY");
			assertThat(node.path("journeyV3Disposition").asText()).isEqualTo("LEGACY_NOT_JOURNEY_V3");
			assertThat(entry.sourcePath().startsWith("backend/src/main/java/com/easysubway/route/")
				|| entry.sourcePath().equals("backend/src/main/resources/application-prod.yml")).isTrue();
			Path source = Path.of("..").resolve(entry.sourcePath());
			assertThat(Files.isRegularFile(source)).isTrue();
			String sourceText = Files.readString(source);
			for (String token : entry.evidenceTokens()) {
				assertThat(sourceText).contains(token);
			}
		}
		assertThat(actual).containsExactlyElementsOf(EXPECTED);
	}

	private static ExpectedEntry entry(String kind, String sourcePath, String member, String pathOrTrigger, String... evidenceTokens) {
		return new ExpectedEntry(kind, sourcePath, member, pathOrTrigger, List.of(evidenceTokens));
	}

	private static Set<String> fieldNames(JsonNode node) {
		Set<String> names = new LinkedHashSet<>();
		node.fieldNames().forEachRemaining(names::add);
		return names;
	}

	private record ExpectedEntry(String kind, String sourcePath, String member, String pathOrTrigger, List<String> evidenceTokens) {
	}
}

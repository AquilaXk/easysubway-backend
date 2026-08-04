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
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Route V2 runtime input inventory contract")
class RouteV2RuntimeInputInventoryContractTest {

	private static final Path PROJECT = Path.of("..");
	private static final Path INVENTORY = PROJECT.resolve("contracts/route/route-v2-runtime-input-inventory.json");
	private static final Path INTERNAL_API_INDEX = PROJECT.resolve("contracts/api/internal-api-index.json");
	private static final List<String> JAVA_SOURCE_ROOTS = List.of(
		"backend/src/main/java/com/easysubway/route/",
		"backend/src/main/java/com/easysubway/realtime/application/"
	);
	// Capacity-evidence, nested fixture, and non-prod-profile sources are outside production Route V2 runtime.
	private static final Set<String> EXCLUDED_CANDIDATE_FILES = Set.of(
		"CapacityEvidencePlayIntegrityDecoder.java",
		"FixtureRealtimeProvider.java",
		"InMemoryRouteSearchRepository.java"
	);
	private static final ObjectMapper JSON = new ObjectMapper();
	private static final List<ExpectedEntry> EXPECTED = List.of(
		entry("INPUT", "backend/src/main/java/com/easysubway/route/adapter/in/web/RouteSearchController.java", "RouteSearchController#searchRouteV2", "POST /api/v2/routes/search", "/api/v2/routes/search", "searchRouteV2"),
		entry("INPUT", "backend/src/main/java/com/easysubway/route/adapter/in/web/RouteV2SessionController.java", "RouteV2SessionController#issue", "POST /api/v2/routes/session", "@PostMapping(\"/api/v2/routes/session\")", "ResponseEntity<RouteV2SessionResponse> issue("),
		entry("INPUT", "backend/src/main/java/com/easysubway/route/adapter/in/web/RouteSearchController.java", "RouteSearchController#refreshRoute", "POST /api/v2/routes/{routeSearchId}/refresh", "@PostMapping(\"/api/v2/routes/{routeSearchId}/refresh\")", "routeSearchUseCase.refreshRoute(routeSearchId)"),
		entry("REGISTRY", "backend/src/main/java/com/easysubway/route/application/service/RouteV2SessionService.java", "RouteV2SessionService#issue", "session integrity decode and persistence", "decoder.decode(integrityToken)", "store.claimNonceAndSaveSession"),
		entry("PROVIDER", "backend/src/main/java/com/easysubway/route/adapter/out/integrity/GooglePlayIntegrityDecoder.java", "GooglePlayIntegrityDecoder#decode", "Google Play Integrity decode", "implements PlayIntegrityDecoder", "DECODE_URL", "restClient.post()"),
		entry("REGISTRY", "backend/src/main/java/com/easysubway/route/adapter/out/persistence/JdbcRouteV2AccessStore.java", "JdbcRouteV2AccessStore#claimNonceAndSaveSession", "nonce claim and session registry", "@Transactional", "claimNonce(nonceSha256", "saveSession(session)"),
		entry("OPTIONAL_OR_LEGACY", "backend/src/main/java/com/easysubway/route/application/service/RouteV2Planner.java", "RouteV2Planner#search", "optional route timetable/legacy graph branch", "getIfAvailable", "RouteTimetable::empty", "searchRouteAlternatives", "planRouteAlternatives"),
		entry("CACHE", "backend/src/main/java/com/easysubway/route/application/service/RouteV2Planner.java", "RouteV2Planner#timetableSnapshot", "timetable snapshot cache", "cachedTimetableSnapshot", "loadRouteTimetableSnapshot"),
		entry("PLANNER", "backend/src/main/java/com/easysubway/route/application/service/RouteTimetableRaptorPlanner.java", "RouteTimetableRaptorPlanner#searchWithDiagnostics", "RAPTOR timetable search", "searchWithDiagnostics"),
		entry("LOADER", "backend/src/main/java/com/easysubway/route/adapter/out/persistence/JdbcRouteTimetableRepository.java", "JdbcRouteTimetableRepository#loadRouteTimetableSnapshot", "active timetable snapshot load", "loadRouteTimetableSnapshot", "timetable_snapshot_active"),
		entry("REGISTRY", "backend/src/main/java/com/easysubway/route/adapter/out/persistence/JdbcRouteTimetableRepository.java", "JdbcRouteTimetableRepository#activeItxArtifact", "active ITX artifact registry", "activeItxArtifact", "timetable_snapshot_active"),
		entry("LOADER", "backend/src/main/java/com/easysubway/route/adapter/out/persistence/TimetableSeedLoader.java", "TimetableSeedLoader#run", "timetable seed activation", "implements ApplicationRunner", "public void run(ApplicationArguments args)", "activateLocked"),
		entry("REGISTRY", "backend/src/main/java/com/easysubway/route/adapter/out/persistence/TimetableSeedLoader.java", "TimetableSeedLoader#activateLocked", "locked timetable snapshot activation", "activateLocked", "timetable_snapshot_lock", "timetable_snapshot_active"),
		entry("REGISTRY", "backend/src/main/java/com/easysubway/route/application/service/ProductionRouteV2Support.java", "ProductionRouteV2Support#requireTimetableArtifact/#requireUsablePlan", "production timetable and plan gate", "requireTimetableArtifact", "requireUsablePlan"),
		entry("OPTIONAL_OR_LEGACY", "backend/src/main/java/com/easysubway/route/application/service/RouteSearchService.java", "RouteSearchService#searchRouteAlternatives/#planRouteAlternatives", "legacy graph alternatives", "searchRouteAlternatives", "planRouteAlternatives"),
		entry("REGISTRY", "backend/src/main/java/com/easysubway/route/adapter/out/persistence/JdbcRouteV2AccessStore.java", "JdbcRouteV2AccessStore#saveState", "ephemeral route V2 state registry", "public void saveState", "INSERT INTO route_v2_states"),
		entry("OPTIONAL_OR_LEGACY", "backend/src/main/java/com/easysubway/route/application/service/RouteSearchService.java", "RouteSearchService#refreshRoute", "Route V2 refresh", "getRouteSearch(routeSearchId)", "refreshStatus(routeSearch)"),
		entry("REGISTRY", "backend/src/main/java/com/easysubway/route/adapter/out/persistence/JdbcRouteSearchRepository.java", "JdbcRouteSearchRepository#loadRouteSearch", "persisted Route V2 search load", "implements LoadRouteSearchPort", "FROM route_search_results", "WHERE route_search_id = ?"),
		entry("OPTIONAL_OR_LEGACY", "backend/src/main/java/com/easysubway/route/application/service/RouteV2Planner.java", "RouteV2Planner#resolveRealtimeUpdates", "timetable realtime resolution", "routeSearchUseCase.resolveTimetableRealtime(queries)", "REALTIME_OVERLAY_UNAVAILABLE"),
		entry("OPTIONAL_OR_LEGACY", "backend/src/main/java/com/easysubway/route/application/service/RouteSearchService.java", "RouteSearchService#resolveTimetableRealtime", "optional realtime resolver", "realtimeArrivalResolver == null", "realtimeArrivalResolver.resolve"),
		entry("RESOLVER", "backend/src/main/java/com/easysubway/route/adapter/out/realtime/RealtimeGatewayArrivalResolver.java", "RealtimeGatewayArrivalResolver#resolve", "gateway arrival resolver", "realtimeGatewayService.arrivals(new RealtimeQuery", "statusOf(result)"),
		entry("PROVIDER", "backend/src/main/java/com/easysubway/realtime/application/RealtimeGatewayService.java", "RealtimeGatewayService#arrivals", "realtime gateway arrival provider", "normalizeArrivalQuery(query)", "provider.arrivals(normalizedQuery.query())", "arrivalCache"),
		entry("OPTIONAL_OR_LEGACY", "backend/src/main/java/com/easysubway/realtime/application/TopisRealtimeProvider.java", "TopisRealtimeProvider#arrivals", "TOPIS provider fallback", "implements RealtimeProvider", "TOPIS_BASE_URI", "fallbackProvider.arrivals(query)")
	);

	@Test
	@DisplayName("inventory is closed, ordered, production-only, and evidence-backed")
	void inventoryIsClosedAndExact() throws IOException {
		JsonNode inventory = JSON.readTree(INVENTORY.toFile());
		assertThat(fieldNames(inventory)).containsExactlyInAnyOrder("schemaVersion", "artifactKind", "backendBaseSha", "sourceRoots", "entries");
		assertThat(inventory.path("schemaVersion").asText()).isEqualTo("ROUTE_V2_RUNTIME_INPUT_INVENTORY_V1");
		assertThat(inventory.path("artifactKind").asText()).isEqualTo("route-v2-runtime-input-inventory");
		assertThat(inventory.path("backendBaseSha").asText()).isEqualTo("610777a77be82a7fddea43965b2a986d98079abe");
		assertThat(inventory.path("sourceRoots")).extracting(JsonNode::asText).containsExactly(
			"backend/src/main/java/com/easysubway/route/",
			"backend/src/main/java/com/easysubway/realtime/application/"
		);

		List<ExpectedEntry> actual = new ArrayList<>();
		Set<String> members = new LinkedHashSet<>();
		for (JsonNode node : inventory.path("entries")) {
			assertThat(fieldNames(node)).containsExactlyInAnyOrder("kind", "sourcePath", "member", "pathOrTrigger", "evidenceTokens", "journeyV3Disposition");
			assertThat(node.path("evidenceTokens").isArray()).isTrue();
			List<String> evidenceTokens = new ArrayList<>();
			for (JsonNode token : node.path("evidenceTokens")) {
				assertThat(token.isTextual()).isTrue();
				evidenceTokens.add(token.asText());
			}
			ExpectedEntry entry = entry(node.path("kind").asText(), node.path("sourcePath").asText(),
				node.path("member").asText(), node.path("pathOrTrigger").asText(), evidenceTokens.toArray(String[]::new));
			actual.add(entry);
			assertThat(members.add(entry.member())).isTrue();
			assertThat(entry.kind()).isIn("INPUT", "PLANNER", "LOADER", "CACHE", "REGISTRY", "RESOLVER", "PROVIDER", "OPTIONAL_OR_LEGACY");
			assertThat(node.path("journeyV3Disposition").asText()).isEqualTo("LEGACY_NOT_JOURNEY_V3");
			assertThat(inventory.path("sourceRoots")).extracting(JsonNode::asText).anyMatch(entry.sourcePath()::startsWith);
			Path source = PROJECT.resolve(entry.sourcePath());
			assertThat(Files.isRegularFile(source)).isTrue();
			String sourceText = Files.readString(source);
			for (String token : entry.evidenceTokens()) {
				assertThat(sourceText).contains(token);
			}
		}
		assertThat(actual).containsExactlyElementsOf(EXPECTED);
	}

	@Test
	@DisplayName("Route V2 inputs and production runtime implementations reconcile exactly")
	void inventoryReconcilesInputsAndProductionImplementations() throws IOException {
		JsonNode inventory = JSON.readTree(INVENTORY.toFile());
		Set<Endpoint> inputs = new LinkedHashSet<>();
		Set<Member> members = new LinkedHashSet<>();
		for (JsonNode entry : inventory.path("entries")) {
			String member = entry.path("member").asText();
			members.add(new Member(entry.path("sourcePath").asText(), member));
			if ("INPUT".equals(entry.path("kind").asText())) {
				String[] methodAndPath = entry.path("pathOrTrigger").asText().split(" ", 2);
				String[] classAndMethod = member.split("#", 2);
				assertThat(methodAndPath).as("pathOrTrigger %s", entry.path("pathOrTrigger").asText()).hasSize(2);
				assertThat(classAndMethod).as("member %s", member).hasSize(2);
				inputs.add(new Endpoint(methodAndPath[0], methodAndPath[1], classAndMethod[0], classAndMethod[1]));
			}
		}

		Set<Endpoint> indexedInputs = new LinkedHashSet<>();
		for (JsonNode operation : JSON.readTree(INTERNAL_API_INDEX.toFile()).path("operations")) {
			String path = operation.path("path").asText();
			if ("POST".equals(operation.path("method").asText()) && path.startsWith("/api/v2/routes/")) {
				String handler = operation.path("handlerClass").asText();
				indexedInputs.add(new Endpoint("POST", path, handler.substring(handler.lastIndexOf('.') + 1), operation.path("javaMethod").asText()));
			}
		}
		assertThat(inputs).containsExactlyInAnyOrderElementsOf(indexedInputs);

		for (String sourceRoot : JAVA_SOURCE_ROOTS) {
			try (Stream<Path> paths = Files.walk(PROJECT.resolve(sourceRoot))) {
				for (Path path : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
					String sourcePath = PROJECT.relativize(path).toString().replace('\\', '/');
					if (EXCLUDED_CANDIDATE_FILES.contains(path.getFileName().toString())) {
						continue;
					}
					String source = Files.readString(path);
					String suffix = requiredMemberSuffix(source);
					if (suffix != null) {
						String fileName = path.getFileName().toString();
						String className = fileName.substring(0, fileName.length() - ".java".length());
						assertThat(members).contains(new Member(sourcePath, className + suffix));
					}
				}
			}
		}
	}

	private static String requiredMemberSuffix(String source) {
		if (source.contains("implements LoadRouteTimetablePort")) return "#loadRouteTimetableSnapshot";
		if (source.contains("implements LoadRouteSearchPort")) return "#loadRouteSearch";
		if (source.contains("implements RealtimeArrivalResolver")) return "#resolve";
		if (source.contains("implements RealtimeProvider {") || source.contains("implements RealtimeProvider,")) return "#arrivals";
		if (source.contains("implements PlayIntegrityDecoder")) return "#decode";
		if (source.contains("implements RouteV2AccessStore")) return "#claimNonceAndSaveSession";
		if (source.contains("implements ApplicationRunner")) return "#run";
		return null;
	}

	private static ExpectedEntry entry(String kind, String sourcePath, String member, String pathOrTrigger, String... evidenceTokens) {
		return new ExpectedEntry(kind, sourcePath, member, pathOrTrigger, List.of(evidenceTokens));
	}

	private static Set<String> fieldNames(JsonNode node) {
		Set<String> names = new LinkedHashSet<>();
		node.fieldNames().forEachRemaining(names::add);
		return names;
	}

	private record Endpoint(String method, String path, String handlerClass, String javaMethod) {
	}

	private record Member(String sourcePath, String member) {
	}

	private record ExpectedEntry(String kind, String sourcePath, String member, String pathOrTrigger, List<String> evidenceTokens) {
	}
}

package com.easysubway.route.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Route V2 runtime input inventory contract")
class RouteV2RuntimeInputInventoryContractTest {

	private static final Path PROJECT = Path.of("..");
	private static final Path INVENTORY = PROJECT.resolve("contracts/route/route-v2-runtime-input-inventory.json");
	private static final Path INTERNAL_API_INDEX = PROJECT.resolve("contracts/api/internal-api-index.json");
	private static final Path TIMETABLE_GZIP = PROJECT.resolve("backend/src/main/resources/timetable/line4-timetable-seed.sql.gz");
	private static final Path TIMETABLE_EVIDENCE = PROJECT.resolve("backend/src/main/resources/timetable/server-timetable-snapshot-evidence.json");
	private static final String GZIP_SHA256 = "7f63ca3717d224ac9191d40d258b736d85a0c19a5c3233793fc5ac4848adc375";
	private static final String EVIDENCE_SHA256 = "0906ef492ae32f5362ef679943a91fead28350f6c1c540423a63a61abb534b80";
	// Capacity-evidence, nested fixture, and non-prod-profile sources are outside production Route V2 runtime.
	private static final Set<String> EXCLUDED_CANDIDATE_FILES = Set.of(
		"CapacityEvidencePlayIntegrityDecoder.java",
		"FixtureRealtimeProvider.java",
		"InMemoryFacilityReportRepository.java",
		"InMemoryRouteSearchRepository.java",
		"InMemoryRealtimeMappingPort.java",
		"DevelopmentRealtimeSafetyPorts.java"
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
		entry("REGISTRY", "backend/src/main/java/com/easysubway/route/adapter/out/persistence/JdbcRouteTimetableRepository.java", "JdbcRouteTimetableRepository#activeItxArtifact", "CONFLICT: stale break-glass", "activeItxArtifact", "timetable_snapshot_active", "if (breakGlass)", "return admissible;"),
		entry("LOADER", "backend/src/main/java/com/easysubway/route/adapter/out/persistence/TimetableSeedLoader.java", "TimetableSeedLoader#run", "timetable seed activation", "implements ApplicationRunner", "public void run(ApplicationArguments args)", "activateLocked"),
		entry("REGISTRY", "backend/src/main/java/com/easysubway/route/adapter/out/persistence/TimetableSeedLoader.java", "TimetableSeedLoader#activateLocked", "locked timetable snapshot activation", "activateLocked", "timetable_snapshot_lock", "timetable_snapshot_active"),
		entry("REGISTRY", "backend/src/main/java/com/easysubway/route/application/service/ProductionRouteV2Support.java", "ProductionRouteV2Support#requireTimetableArtifact/#requireUsablePlan/#saveState", "production timetable, plan, and state gate", "requireTimetableArtifact", "requireUsablePlan", "stateStore.saveState"),
		entry("OPTIONAL_OR_LEGACY", "backend/src/main/java/com/easysubway/route/application/service/RouteSearchService.java", "RouteSearchService#searchRouteAlternatives/#planRouteAlternatives", "legacy graph alternatives", "searchRouteAlternatives", "planRouteAlternatives"),
		entry("REGISTRY", "backend/src/main/java/com/easysubway/route/adapter/out/persistence/JdbcRouteV2AccessStore.java", "JdbcRouteV2AccessStore#saveState", "ephemeral route V2 state registry", "public void saveState", "INSERT INTO route_v2_states"),
		entry("OPTIONAL_OR_LEGACY", "backend/src/main/java/com/easysubway/route/application/service/RouteSearchService.java", "RouteSearchService#refreshRoute", "Route V2 refresh", "getRouteSearch(routeSearchId)", "refreshStatus(routeSearch)"),
		entry("REGISTRY", "backend/src/main/java/com/easysubway/route/adapter/out/persistence/JdbcRouteSearchRepository.java", "JdbcRouteSearchRepository#loadRouteSearch", "persisted Route V2 search load", "implements LoadRouteSearchPort", "FROM route_search_results", "WHERE route_search_id = ?"),
		entry("OPTIONAL_OR_LEGACY", "backend/src/main/java/com/easysubway/route/application/service/RouteV2Planner.java", "RouteV2Planner#resolveRealtimeUpdates", "timetable realtime resolution", "routeSearchUseCase.resolveTimetableRealtime(queries)", "REALTIME_OVERLAY_UNAVAILABLE"),
		entry("OPTIONAL_OR_LEGACY", "backend/src/main/java/com/easysubway/route/application/service/RouteSearchService.java", "RouteSearchService#resolveTimetableRealtime", "optional realtime resolver", "realtimeArrivalResolver == null", "realtimeArrivalResolver.resolve"),
		entry("RESOLVER", "backend/src/main/java/com/easysubway/route/adapter/out/realtime/RealtimeGatewayArrivalResolver.java", "RealtimeGatewayArrivalResolver#resolve", "gateway arrival resolver", "realtimeGatewayService.arrivals(new RealtimeQuery", "statusOf(result)"),
		entry("PROVIDER", "backend/src/main/java/com/easysubway/realtime/application/RealtimeGatewayService.java", "RealtimeGatewayService#arrivals/#trainPositions", "realtime gateway arrival/train-position provider", "normalizeArrivalQuery(query)", "provider.arrivals(normalizedQuery.query())", "arrivalCache", "mappingPort.findArrivalMapping(PROVIDER_ID, query)", "normalizeTrainPositionQuery(query)", "provider.trainPositions(normalizedQuery)", "trainPositionCache"),
		entry("OPTIONAL_OR_LEGACY", "backend/src/main/java/com/easysubway/realtime/application/TopisRealtimeProvider.java", "TopisRealtimeProvider#arrivals/#trainPositions", "CONFLICT: local-fixture fallback branch", "implements RealtimeProvider", "TOPIS_BASE_URI", "fixtureAllowedRuntime", "if (!fixtureEnabled)", "fallbackProvider.arrivals(query)", "fallbackProvider.trainPositions(query)"),
		entry("REGISTRY", "backend/src/main/java/com/easysubway/common/security/SecurityConfig.java", "SecurityConfig#routeV2IngressSecurityFilterChain", "production Route V2 ingress security wiring", "@ConditionalOnBean(RouteV2AccessStore.class)", "@Value(\"${easysubway.route-v2.origin-secret:}\")", "RouteV2IngressSecurity.configure"),
		entry("REGISTRY", "backend/src/main/java/com/easysubway/route/adapter/in/web/RouteV2IngressSecurity.java", "RouteV2IngressSecurity#configure", "production Route V2 security matcher and filters", "securityMatcher(", "addFilterBefore(sessionFilter", "addFilterBefore(originFilter"),
		entry("INPUT", "backend/src/main/java/com/easysubway/route/adapter/in/web/RouteV2OriginGateFilter.java", "RouteV2OriginGateFilter#doFilterInternal", "Route V2 origin header gate", "ORIGIN_HEADER", "MessageDigest.isEqual", "ROUTE_ORIGIN_FORBIDDEN"),
		entry("INPUT", "backend/src/main/java/com/easysubway/route/adapter/in/web/RouteV2SessionFilter.java", "RouteV2SessionFilter#shouldNotFilter/#doFilterInternal", "Route V2 bearer-session gate", "\"/api/v2/routes/search\"", "HttpHeaders.AUTHORIZATION", "store.consumeSession("),
		entry("REGISTRY", "backend/src/main/java/com/easysubway/route/adapter/out/persistence/JdbcRouteV2AccessStore.java", "JdbcRouteV2AccessStore#consumeSession", "Route V2 session consume/rate-limit registry", "UPDATE route_v2_sessions", "request_count < ?", "findSession(tokenSha256)"),
		entry("LOADER", "backend/src/main/java/com/easysubway/route/application/service/RouteSearchService.java", "RouteSearchService#validateRouteSearch/#validatedRouteStations", "Route V2 station validation through transit master", "validatedRouteStations(command)", "loadActiveStation(command.originStationId())", "loadActiveStation(command.destinationStationId())"),
		entry("LOADER", "backend/src/main/java/com/easysubway/transit/adapter/out/persistence/JdbcTransitMasterOverrideRepository.java", "JdbcTransitMasterOverrideRepository#loadAccessibilityFacilities/#loadRouteNodes/#loadRouteEdges", "production transit/accessibility override merge", "@Profile(\"prod | staging | release | prod-like\")", "merge(super.loadAccessibilityFacilities()", "merge(super.loadRouteNodes()", "merge(super.loadRouteEdges()"),
		entry("OPTIONAL_OR_LEGACY", "backend/src/main/java/com/easysubway/transit/adapter/out/persistence/UnavailableTransitMasterRepository.java", "UnavailableTransitMasterRepository#loadStations/#loadLines/#loadStationLines/#loadStationExits/#loadAccessibilityFacilities/#loadRouteNodes/#loadRouteEdges", "CONFLICT: production static-seed transit master base", "new InMemoryTransitMasterRepository()", "return seedRepository.loadStations()", "return seedRepository.loadRouteEdges()"),
		entry("OPTIONAL_OR_LEGACY", "backend/src/main/java/com/easysubway/transit/adapter/out/persistence/InMemoryTransitMasterRepository.java", "InMemoryTransitMasterRepository#loadStations/#loadLines/#loadStationLines/#loadStationExits/#loadAccessibilityFacilities/#loadRouteNodes/#loadRouteEdges", "CONFLICT: direct static-seed transit master source", "LoadTransitMasterPort,", "public List<Station> loadStations()", "public List<RouteEdge> loadRouteEdges()"),
		entry("REGISTRY", "backend/src/main/java/com/easysubway/realtime/adapter/out/persistence/JdbcRealtimeMappingRepository.java", "JdbcRealtimeMappingRepository#findArrivalMapping/#findTripMapping/#findTrainPositionMapping", "production realtime station/trip mappings", "implements RealtimeMappingPort", "findArrivalMapping", "findTripMapping", "findTrainPositionMapping"),
		entry("REGISTRY", "backend/src/main/resources/db/migration/postgresql/V31__realtime_provider_mappings.sql", "V31__realtime_provider_mappings.sql#productionSeed", "production PostgreSQL realtime provider line/station mapping seed", "INSERT INTO realtime_provider_line_mappings", "'seoul-topis'", "'1004'", "'seoul-4'", "'station-sangnoksu'", "'상록수'"),
		entry("REGISTRY", "backend/src/main/resources/db/migration/postgresql/V34__realtime_provider_trip_mappings.sql", "V34__realtime_provider_trip_mappings.sql#productionSeed", "production PostgreSQL realtime provider trip mapping seed", "INSERT INTO realtime_provider_trip_mappings", "'seoul-topis'", "'1004'", "'seoul-4'", "'상행'", "'당고개 방면'", "'하행'", "'오이도 방면'"),
		entry("REGISTRY", "backend/src/main/resources/db/migration/postgresql/V49__realtime_arrival_observations.sql", "V49__realtime_arrival_observations.sql#productionQuotaSeed", "production PostgreSQL realtime provider quota seed", "CREATE TABLE realtime_provider_call_quota_state", "INSERT INTO realtime_provider_call_quota_state", "('seoul-topis', -1, 0, -1, 0, CURRENT_TIMESTAMP)"),
		entry("REGISTRY", "backend/src/main/java/com/easysubway/realtime/adapter/out/persistence/JdbcRealtimeProviderCallQuotaRepository.java", "JdbcRealtimeProviderCallQuotaRepository#tryAcquire", "production realtime provider quota", "implements RealtimeProviderCallQuotaPort", "tryAcquire(", "realtime_provider_call_quota_state"),
		entry("REGISTRY", "backend/src/main/java/com/easysubway/realtime/application/RealtimeProviderControl.java", "RealtimeProviderControl#providerEnabled/#disableProvider/#enableProvider", "operator realtime provider switch", "providerEnabled(String providerId)", "switchState(providerId).enabled()", "disableProvider(String providerId, String reason)", "switches.put(providerId, new ProviderSwitch(false, cleanReason(reason)));", "enableProvider(String providerId)", "switches.put(providerId, new ProviderSwitch(true, null));"),
		entry("REGISTRY", "backend/src/main/resources/application-prod.yml", "application-prod.yml#routeV2ProductionControls", "production Route V2 auth/session/seed/freshness property controls", "management:", "readiness:", "include: \"readinessState,db,productionReadiness\"", "timetable:", "enabled: ${EASYSUBWAY_TIMETABLE_SEED_ENABLED:false}", "includes-itx: ${EASYSUBWAY_TIMETABLE_SEED_INCLUDES_ITX:false}", "break-glass: ${EASYSUBWAY_TIMETABLE_FRESHNESS_BREAK_GLASS:false}", "route-v2:", "origin-secret: ${EASYSUBWAY_ROUTE_V2_ORIGIN_SECRET:}", "session-max-requests: ${EASYSUBWAY_ROUTE_V2_SESSION_MAX_REQUESTS:50}", "certificate-sha256: ${EASYSUBWAY_ROUTE_V2_PLAY_INTEGRITY_CERTIFICATE_SHA256:}", "credentials-base64: ${EASYSUBWAY_PLAY_INTEGRITY_CREDENTIALS_BASE64:}"),
		entry("LOADER", "backend/src/main/resources/timetable/line4-timetable-seed.sql.gz", "line4-timetable-seed.sql.gz#defaultSeed", "default production timetable seed gzip resource", "sha256:7f63ca3717d224ac9191d40d258b736d85a0c19a5c3233793fc5ac4848adc375"),
		entry("OPTIONAL_OR_LEGACY", "backend/src/main/resources/timetable/line4-subway-timetable-seed.sql.gz", "line4-subway-timetable-seed.sql.gz#packagedAlternateSeed", "CONFLICT: packaged configurable alternate timetable seed", "sha256:0fda95e45a7b4e8a4863ce93132bda458b3d149ece6706ff1e1dcb23d1adeec8"),
		entry("REGISTRY", "backend/src/main/resources/timetable/server-timetable-snapshot-evidence.json", "server-timetable-snapshot-evidence.json#defaultSeedEvidence", "default production timetable seed identity evidence", "\"artifactKind\": \"server-timetable-snapshot-evidence\"", "\"schemaIdentity\": \"backend-timetable-snapshot-v1\"", "\"snapshotGzipSha256\"", "\"materializedSqlSha256\""),
		entry("INPUT", "backend/src/main/java/com/easysubway/realtime/adapter/in/web/RealtimeController.java", "RealtimeController#arrivals/#trainPositions", "GET /api/v1/realtime/{arrivals,train-positions}", "@GetMapping(\"/api/v1/realtime/arrivals\")", "@GetMapping(\"/api/v1/realtime/train-positions\")", "rejectSecretBearingProviderParameters(request)", "realtimeGatewayService.arrivals(new RealtimeQuery(", "realtimeGatewayService.trainPositions(new RealtimeQuery("),
		entry("INPUT", "backend/src/main/java/com/easysubway/realtime/adapter/in/web/RealtimeProviderAdminController.java", "RealtimeProviderAdminController#disableProvider/#enableProvider", "POST /admin/realtime/providers/{providerId}/{disable,enable}", "@PostMapping(\"/admin/realtime/providers/{providerId}/disable\")", "@PostMapping(\"/admin/realtime/providers/{providerId}/enable\")", "providerControl.disableProvider(providerId, reason)", "providerControl.enableProvider(providerId)"),
		entry("REGISTRY", "backend/src/main/java/com/easysubway/route/adapter/in/scheduler/RouteV2StatePurgeScheduler.java", "RouteV2StatePurgeScheduler#purgeExpiredState", "scheduled Route V2 state/session/nonce purge", "@Scheduled(fixedDelayString = \"${easysubway.route-v2.state-purge-interval-ms:300000}\")", "store.purgeExpired(clock.instant())", "metrics.recordPurge(purged)"),
		entry("LOADER", "backend/src/main/java/com/easysubway/route/adapter/out/persistence/TimetableSeedLoader.java", "TimetableSeedLoader#TimetableSeedLoader", "configurable timetable seed/evidence resources", "@Value(\"${easysubway.timetable.seed.resource:classpath:timetable/line4-timetable-seed.sql.gz}\")", "@Value(\"${easysubway.timetable.seed.evidence-resource:classpath:timetable/server-timetable-snapshot-evidence.json}\")", "@Value(\"${easysubway.timetable.seed.includes-itx:false}\")"),
		entry("PROVIDER", "backend/src/main/java/com/easysubway/realtime/application/TopisRealtimeProvider.java", "TopisRealtimeProvider#TopisRealtimeProvider", "Seoul TOPIS credential/fixture runtime configuration", "@Value(\"${EASYSUBWAY_SEOUL_TOPIS_SERVICE_KEY:}\")", "@Value(\"${EASYSUBWAY_SEOUL_TOPIS_FIXTURE_ENABLED:false}\")", "@Value(\"${EASYSUBWAY_DEPLOY_ENV:}\")", "runtimeProfiles(environment)"),
		entry("REGISTRY", "backend/src/main/java/com/easysubway/realtime/application/RealtimeGatewayService.java", "RealtimeGatewayService#RealtimeGatewayService", "TOPIS provider-call quota configuration", "@Value(\"${EASYSUBWAY_SEOUL_TOPIS_CALL_LIMIT_PER_MINUTE:1}\")", "@Value(\"${EASYSUBWAY_SEOUL_TOPIS_CALL_LIMIT_PER_DAY:800}\")", "RealtimeProviderCallQuotaPort providerCallQuotaPort"),
		entry("INPUT", "backend/src/main/java/com/easysubway/transit/adapter/in/web/TransitMasterController.java", "TransitMasterController#createAccessibilityFacility/#updateAccessibilityFacility/#updateFacilityStatus/#updateRouteEdge/#updateRouteNodeDisplay", "API facility create/update/status and route-edge/node override ingress", "@PatchMapping(\"/admin/stations/{stationId}/route-edges/{edgeId}\")", "@PatchMapping(\"/admin/stations/{stationId}/route-nodes/{nodeId}\")", "@PostMapping(\"/admin/facilities\")", "@PutMapping(\"/admin/facilities/{facilityId}\")", "@PatchMapping(\"/admin/facilities/{facilityId}/status\")", "transitMasterAdminUseCase.updateRouteEdge(", "transitMasterAdminUseCase.updateRouteNodeDisplay(", "transitMasterAdminUseCase.createAccessibilityFacility(", "transitMasterAdminUseCase.updateAccessibilityFacility(", "transitMasterAdminUseCase.updateFacilityStatus("),
		entry("INPUT", "backend/src/main/java/com/easysubway/transit/adapter/in/web/TransitStationLayoutAdminPageController.java", "TransitStationLayoutAdminPageController#updateRouteEdgeFromPage/#updateRouteNodeDisplayFromPage", "POST route-edge/node override page ingress", "@PostMapping(\"/admin/stations/{stationId}/route-edges/{edgeId}/page\")", "@PostMapping(\"/admin/stations/{stationId}/route-nodes/{nodeId}/page\")", "String updateRouteEdgeFromPage(", "String updateRouteNodeDisplayFromPage(", "transitMasterAdminUseCase.updateRouteEdge(new UpdateRouteEdgeCommand(", "transitMasterAdminUseCase.updateRouteNodeDisplay(new UpdateRouteNodeDisplayCommand("),
		entry("INPUT", "backend/src/main/java/com/easysubway/transit/adapter/in/web/TransitStationAdminPageController.java", "TransitStationAdminPageController#saveFacilityFromPage", "POST facility create/update page ingress", "@PostMapping(\"/admin/facilities/editor/page\")", "String saveFacilityFromPage(", "transitMasterAdminUseCase.createAccessibilityFacility(new CreateAccessibilityFacilityCommand(", "transitMasterAdminUseCase.updateAccessibilityFacility(new UpdateAccessibilityFacilityCommand("),
		entry("INPUT", "backend/src/main/java/com/easysubway/transit/adapter/in/web/TransitFacilityAdminPageController.java", "TransitFacilityAdminPageController#updateFacilityStatusFromPage", "POST facility-status page ingress", "@PostMapping(\"/admin/facilities/{facilityId}/page/status\")", "String updateFacilityStatusFromPage(", "transitMasterAdminUseCase.updateFacilityStatus(new UpdateAccessibilityFacilityStatusCommand("),
		entry("INPUT", "backend/src/main/java/com/easysubway/report/adapter/in/web/FacilityReportController.java", "FacilityReportController#createReport/#reviewReport", "POST report create and accepted-report API review ingress", "@PostMapping(\"/api/v1/reports\")", "ApiResponse<FacilityReportCreatedResponse> createReport(", "facilityReportUseCase.createReportWithReceipt(request.toReceiptCommand())", "facilityReportUseCase.createReport(request.toCommand(principal.getName()))", "@PostMapping(\"/admin/reports/{reportId}/review\")", "ApiResponse<FacilityReportResponse> reviewReport(", "facilityReportUseCase.reviewReport(request.toCommand(reportId, principal.getName()))"),
		entry("INPUT", "backend/src/main/java/com/easysubway/report/adapter/in/web/FacilityReportAdminPageController.java", "FacilityReportAdminPageController#reviewReportFromPage/#bulkReviewReports", "POST accepted-report page/bulk review ingress", "@PostMapping(\"/admin/reports/{reportId}/page/review\")", "@PostMapping(\"/admin/reports/bulk-review\")", "String reviewReportFromPage(", "String bulkReviewReports(", "facilityReportUseCase.reviewReport("),
		entry("REGISTRY", "backend/src/main/java/com/easysubway/transit/application/service/TransitMasterService.java", "TransitMasterService#createAccessibilityFacility/#updateAccessibilityFacility/#updateFacilityStatus/#updateRouteEdge/#updateRouteNodeDisplay", "facility and route-edge/node override writer orchestration", "public AccessibilityFacility createAccessibilityFacility(CreateAccessibilityFacilityCommand command)", "public AccessibilityFacility updateAccessibilityFacility(UpdateAccessibilityFacilityCommand command)", "public AccessibilityFacility updateFacilityStatus(UpdateAccessibilityFacilityStatusCommand command)", "public RouteEdge updateRouteEdge(UpdateRouteEdgeCommand command)", "public RouteNode updateRouteNodeDisplay(UpdateRouteNodeDisplayCommand command)", "saveAccessibilityFacilityStatusPort.saveAccessibilityFacility(facility, command.updatedBy())", "saveAccessibilityFacilityStatusPort.saveFacilityStatus(", "saveRouteEdgePort.saveRouteEdge(updated, command.updatedBy())", "saveRouteNodePort.saveRouteNode(updated, command.updatedBy())"),
		entry("REGISTRY", "backend/src/main/java/com/easysubway/report/application/service/FacilityReportService.java", "FacilityReportService#createReport/#createReportWithReceipt/#reviewReport/#applyAcceptedReportToFacilityStatus", "report create/review and accepted facility-status propagation", "public FacilityReport createReport(CreateFacilityReportCommand command)", "public CreatedFacilityReport createReportWithReceipt(CreateFacilityReportCommand command)", "saveFacilityReportPort.saveReport(report)", "public FacilityReport reviewReport(ReviewFacilityReportCommand command)", "applyAcceptedReportToFacilityStatus(report, command.decision())", "decision != FacilityReportReviewDecision.ACCEPT", "saveAccessibilityFacilityStatusPort.saveFacilityStatus(", "requireWritableMasterData();"),
		entry("REGISTRY", "backend/src/main/java/com/easysubway/transit/adapter/out/persistence/JdbcTransitMasterOverrideRepository.java", "JdbcTransitMasterOverrideRepository#saveFacilityStatus/#saveAccessibilityFacility/#saveRouteEdge/#saveRouteNode/#saveOverride", "facility/route-edge/node override persistence", "public void saveFacilityStatus(", "public void saveAccessibilityFacility(", "public void saveRouteEdge(", "public void saveRouteNode(", "saveOverride(FACILITY, facilityId, new AccessibilityFacility(", "saveOverride(ROUTE_EDGE, routeEdge.id(), routeEdge, updatedBy)", "saveOverride(ROUTE_NODE, routeNode.id(), routeNode, updatedBy)", "INSERT INTO transit_master_overrides"),
		entry("REGISTRY", "backend/src/main/java/com/easysubway/common/persistence/ProductionPersistenceReadinessConfiguration.java", "ProductionPersistenceReadinessConfiguration#productionReadinessHealthIndicator", "production database/master-data readiness", "@Profile(\"prod | staging | release | prod-like\")", "HealthIndicator productionReadinessHealthIndicator(", "boolean databaseReady = databaseReady(dataSource);", "boolean masterDataReady = masterDataReady(loadTransitMasterPort);", "return health(ready).withDetails(details).build();"),
		entry("REGISTRY", "backend/src/main/java/com/easysubway/route/adapter/out/persistence/JdbcRouteV2AccessStore.java", "JdbcRouteV2AccessStore#purgeExpired", "scheduled state/session/nonce persistence purge", "public int purgeExpired(Instant now)", "DELETE FROM route_v2_states WHERE expires_at <= ?", "DELETE FROM route_v2_nonce_replays WHERE expires_at <= ?", "DELETE FROM route_v2_sessions WHERE expires_at <= ?"),
		entry("REGISTRY", "backend/src/main/java/com/easysubway/common/security/SecurityConfig.java", "SecurityConfig#publicSecurityFilterChain", "public realtime ingress security allowlist", "SecurityFilterChain publicSecurityFilterChain(", "\"/api/v1/realtime/**\","),
		entry("REGISTRY", "backend/src/main/java/com/easysubway/report/adapter/out/persistence/JdbcFacilityReportRepository.java", "JdbcFacilityReportRepository#loadReport/#saveReport", "production facility-report load/save persistence", "public class JdbcFacilityReportRepository implements", "public Optional<FacilityReport> loadReport(String reportId)", "public FacilityReport saveReport(FacilityReport report)", "upsertReport(report)", "return loadReport(report.id())", "INSERT INTO facility_reports (", "ON CONFLICT (report_id) DO UPDATE")
	);

	@Test
	@DisplayName("inventory is closed, ordered, production-only, and evidence-backed")
	void inventoryIsClosedAndExact() throws Exception {
		JsonNode inventory = JSON.readTree(INVENTORY.toFile());
		assertThat(fieldNames(inventory)).containsExactlyInAnyOrder("schemaVersion", "artifactKind", "backendBaseSha", "sourceRoots", "entries");
		assertThat(inventory.path("schemaVersion").asText()).isEqualTo("ROUTE_V2_RUNTIME_INPUT_INVENTORY_V1");
		assertThat(inventory.path("artifactKind").asText()).isEqualTo("route-v2-runtime-input-inventory");
		assertThat(inventory.path("backendBaseSha").asText()).isEqualTo("610777a77be82a7fddea43965b2a986d98079abe");
		assertThat(inventory.path("sourceRoots")).extracting(JsonNode::asText).containsExactly(
			"backend/src/main/java/com/easysubway/route/",
			"backend/src/main/java/com/easysubway/realtime/application/",
			"backend/src/main/java/com/easysubway/realtime/adapter/out/persistence/",
			"backend/src/main/java/com/easysubway/transit/adapter/out/persistence/",
			"backend/src/main/java/com/easysubway/common/security/",
			"backend/src/main/java/com/easysubway/realtime/adapter/in/web/",
			"backend/src/main/java/com/easysubway/transit/adapter/in/web/",
			"backend/src/main/java/com/easysubway/report/adapter/in/web/",
			"backend/src/main/java/com/easysubway/transit/application/service/",
			"backend/src/main/java/com/easysubway/report/application/service/",
			"backend/src/main/java/com/easysubway/report/adapter/out/persistence/",
			"backend/src/main/java/com/easysubway/common/persistence/",
			"backend/src/main/resources/"
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
			if (entry.sourcePath().endsWith(".gz")) {
				List<String> digestTokens = entry.evidenceTokens().stream().filter(token -> token.startsWith("sha256:")).toList();
				assertThat(digestTokens).containsExactlyElementsOf(List.of("sha256:" + sha256(Files.readAllBytes(source))));
			} else {
				String sourceText = Files.readString(source);
				for (String token : entry.evidenceTokens()) {
					assertThat(sourceText).contains(token);
				}
			}
		}
		Set<String> timetableInventoryPaths = new LinkedHashSet<>();
		for (JsonNode node : inventory.path("entries")) {
			String sourcePath = node.path("sourcePath").asText();
			if (sourcePath.startsWith("backend/src/main/resources/timetable/")) {
				timetableInventoryPaths.add(sourcePath);
			}
		}
		try (Stream<Path> paths = Files.walk(TIMETABLE_GZIP.getParent())) {
			Set<String> actualTimetablePaths = new LinkedHashSet<>();
			for (Path path : paths.filter(Files::isRegularFile).toList()) {
				actualTimetablePaths.add(PROJECT.relativize(path).toString().replace('\\', '/'));
			}
			assertThat(actualTimetablePaths).containsExactlyInAnyOrderElementsOf(timetableInventoryPaths);
		}
		assertThat(actual).hasSize(61);
		assertThat(actual).containsExactlyElementsOf(EXPECTED);
	}

	@Test
	@DisplayName("default timetable gzip and evidence identity are immutable")
	void defaultTimetableBinaryIdentityIsExact() throws Exception {
		byte[] gzip = Files.readAllBytes(TIMETABLE_GZIP);
		byte[] evidenceBytes = Files.readAllBytes(TIMETABLE_EVIDENCE);
		assertThat(sha256(gzip)).isEqualTo(GZIP_SHA256);
		assertThat(sha256(normalizedTextBytes(evidenceBytes))).isEqualTo(EVIDENCE_SHA256);

		JsonNode evidence = JSON.readTree(evidenceBytes);
		assertThat(evidence.path("snapshotGzipSha256").asText()).isEqualTo(sha256(gzip));
		assertThat(evidence.path("snapshotGzipByteSize").asLong()).isEqualTo(gzip.length);
		byte[] sql;
		try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(gzip))) {
			sql = input.readAllBytes();
		}
		assertThat(evidence.path("snapshotSha256").asText()).isEqualTo(sha256(sql));
		assertThat(evidence.path("snapshotSqlByteSize").asLong()).isEqualTo(sql.length);
		String materializedSql = new String(sql, StandardCharsets.UTF_8);
		int update = materializedSql.indexOf("UPDATE data_source_snapshots SET ");
		int insert = materializedSql.indexOf("INSERT INTO data_source_snapshots ");
		int suffixStart = update < 0 ? insert : insert < 0 ? update : Math.min(update, insert);
		assertThat(suffixStart).isGreaterThanOrEqualTo(0);
		assertThat(evidence.path("accessibilitySource").path("materializedSqlSha256").asText())
			.isEqualTo(sha256(materializedSql.substring(suffixStart).getBytes(StandardCharsets.UTF_8)));
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
				if (methodAndPath[1].startsWith("/api/v2/routes/")) {
					inputs.add(new Endpoint(methodAndPath[0], methodAndPath[1], classAndMethod[0], classAndMethod[1]));
				}
			}
		}

		Set<Endpoint> indexedInputs = new LinkedHashSet<>();
		for (JsonNode operation : JSON.readTree(INTERNAL_API_INDEX.toFile()).path("operations")) {
			String path = operation.path("path").asText();
			if (path.startsWith("/api/v2/routes/")) {
				String handler = operation.path("handlerClass").asText();
				indexedInputs.add(new Endpoint(operation.path("method").asText(), path, handler.substring(handler.lastIndexOf('.') + 1), operation.path("javaMethod").asText()));
			}
		}
		assertThat(inputs).containsExactlyInAnyOrderElementsOf(indexedInputs);

		for (JsonNode sourceRootNode : inventory.path("sourceRoots")) {
			String sourceRoot = sourceRootNode.asText();
			if (!sourceRoot.contains("/java/")) continue;
			try (Stream<Path> paths = Files.walk(PROJECT.resolve(sourceRoot))) {
				for (Path path : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
					String sourcePath = PROJECT.relativize(path).toString().replace('\\', '/');
					String source = Files.readString(path);
					if (EXCLUDED_CANDIDATE_FILES.contains(path.getFileName().toString())) {
						assertExcludedCandidateIsOutsideProductionRuntime(path.getFileName().toString(), source);
						continue;
					}
					List<String> suffixes = requiredMemberSuffixes(path.getFileName().toString(), source);
					if (!suffixes.isEmpty()) {
						String fileName = path.getFileName().toString();
						String className = fileName.substring(0, fileName.length() - ".java".length());
						for (String suffix : suffixes) {
							assertThat(members).contains(new Member(sourcePath, className + suffix));
						}
					}
					assertSharedStateMarkers(sourcePath, source, members);
					if (source.contains("extends UnavailableTransitMasterRepository")) {
						assertThat(members).contains(new Member(sourcePath,
							"JdbcTransitMasterOverrideRepository#loadAccessibilityFacilities/#loadRouteNodes/#loadRouteEdges"));
					}
					if (source.contains("RouteV2IngressSecurity.configure")) {
						assertThat(members).contains(new Member(sourcePath, "SecurityConfig#routeV2IngressSecurityFilterChain"));
					}
					if (source.contains("new RouteV2SessionFilter") || source.contains("new RouteV2OriginGateFilter")) {
						assertThat(members).contains(
							new Member("backend/src/main/java/com/easysubway/route/adapter/in/web/RouteV2SessionFilter.java", "RouteV2SessionFilter#shouldNotFilter/#doFilterInternal"),
							new Member("backend/src/main/java/com/easysubway/route/adapter/in/web/RouteV2OriginGateFilter.java", "RouteV2OriginGateFilter#doFilterInternal")
						);
					}
				}
			}
		}
		Path postgresqlMigrations = PROJECT.resolve("backend/src/main/resources/db/migration/postgresql");
		try (Stream<Path> paths = Files.walk(postgresqlMigrations)) {
			for (Path path : paths.filter(path -> path.toString().endsWith(".sql")).toList()) {
				String source = Files.readString(path);
				String sourcePath = PROJECT.relativize(path).toString().replace('\\', '/');
				if (source.contains("realtime_provider_line_mappings")
					|| source.contains("realtime_provider_station_mappings")
					|| source.contains("realtime_provider_trip_mappings")) {
					assertThat(members).contains(new Member(sourcePath, path.getFileName() + "#productionSeed"));
				}
				if (source.contains("realtime_provider_call_quota_state")) {
					assertThat(members).contains(new Member(sourcePath, path.getFileName() + "#productionQuotaSeed"));
				}
			}
		}
	}

	private static void assertExcludedCandidateIsOutsideProductionRuntime(String fileName, String source) {
		switch (fileName) {
			case "CapacityEvidencePlayIntegrityDecoder.java" -> assertThat(source).contains("@Profile(\"capacity-evidence\")");
			case "InMemoryFacilityReportRepository.java", "InMemoryRouteSearchRepository.java", "InMemoryRealtimeMappingPort.java" -> assertThat(source)
				.contains("@Profile(\"!prod & !staging & !release & !prod-like\")");
			case "DevelopmentRealtimeSafetyPorts.java" -> assertThat(source).contains("@Profile({\"default\", \"dev\", \"test\"})");
			case "FixtureRealtimeProvider.java" -> assertThat(source)
				.contains("final class FixtureRealtimeProvider implements RealtimeProvider")
				.doesNotContain("@Component", "@Service", "@Repository", "@Controller", "@RestController");
			default -> throw new IllegalArgumentException("Unexpected excluded runtime candidate: " + fileName);
		}
	}

	private static List<String> requiredMemberSuffixes(String fileName, String source) {
		if (source.contains("implements LoadRouteTimetablePort")) return List.of("#loadRouteTimetableSnapshot");
		if (source.contains("implements LoadRouteSearchPort")) return List.of("#loadRouteSearch");
		if (source.contains("implements RealtimeArrivalResolver")) return List.of("#resolve");
		if ((source.contains("implements RealtimeProvider {") || source.contains("implements RealtimeProvider,"))
			&& !source.contains("fallbackProvider.arrivals(query)")) return List.of("#arrivals");
		if (source.contains("implements PlayIntegrityDecoder")) return List.of("#decode");
		if (source.contains("implements RouteV2AccessStore")) return List.of("#claimNonceAndSaveSession", "#consumeSession");
		if (source.contains("implements ApplicationRunner")) return List.of("#run");
		if (source.contains("LoadTransitMasterPort,")) return List.of("#loadStations/#loadLines/#loadStationLines/#loadStationExits/#loadAccessibilityFacilities/#loadRouteNodes/#loadRouteEdges");
		if (source.contains("implements RealtimeMappingPort")) return List.of("#findArrivalMapping/#findTripMapping/#findTrainPositionMapping");
		String className = fileName.substring(0, fileName.length() - ".java".length());
		String normalizedSource = source.replaceAll("\\s+", " ");
		if (normalizedSource.matches("(?s).*\\bclass " + className + "\\b[^\\{]*\\bimplements\\b[^\\{]*\\bRealtimeProviderCallQuotaPort\\b.*")) {
			return List.of("#tryAcquire");
		}
		return List.of();
	}

	private static void assertSharedStateMarkers(String sourcePath, String source, Set<Member> members) {
		assertMarker(sourcePath, source, "provider.arrivals(normalizedQuery.query())", "RealtimeGatewayService#arrivals/#trainPositions", members);
		assertMarker(sourcePath, source, "provider.trainPositions(normalizedQuery)", "RealtimeGatewayService#arrivals/#trainPositions", members);
		assertMarker(sourcePath, source, "fallbackProvider.arrivals(query)", "TopisRealtimeProvider#arrivals/#trainPositions", members);
		assertMarker(sourcePath, source, "fallbackProvider.trainPositions(query)", "TopisRealtimeProvider#arrivals/#trainPositions", members);
		assertMarker(sourcePath, source, "disableProvider(String providerId, String reason)", "RealtimeProviderControl#providerEnabled/#disableProvider/#enableProvider", members);
		assertMarker(sourcePath, source, "enableProvider(String providerId)", "RealtimeProviderControl#providerEnabled/#disableProvider/#enableProvider", members);
		assertMarker(sourcePath, source, "providerControl.disableProvider(providerId, reason)", "RealtimeProviderAdminController#disableProvider/#enableProvider", members);
		assertMarker(sourcePath, source, "providerControl.enableProvider(providerId)", "RealtimeProviderAdminController#disableProvider/#enableProvider", members);
		assertMarker(sourcePath, source, "store.purgeExpired(clock.instant())", "RouteV2StatePurgeScheduler#purgeExpiredState", members);
		assertMarker(sourcePath, source, "public int purgeExpired(Instant now)", "JdbcRouteV2AccessStore#purgeExpired", members);
		assertMarker(sourcePath, source, "${easysubway.timetable.seed.resource:classpath:timetable/line4-timetable-seed.sql.gz}", "TimetableSeedLoader#TimetableSeedLoader", members);
		assertTransitIngress(sourcePath, source, members);
		assertMarker(sourcePath, source, "public AccessibilityFacility createAccessibilityFacility(CreateAccessibilityFacilityCommand command)", "TransitMasterService#createAccessibilityFacility/#updateAccessibilityFacility/#updateFacilityStatus/#updateRouteEdge/#updateRouteNodeDisplay", members);
		assertMarker(sourcePath, source, "public RouteEdge updateRouteEdge(UpdateRouteEdgeCommand command)", "TransitMasterService#createAccessibilityFacility/#updateAccessibilityFacility/#updateFacilityStatus/#updateRouteEdge/#updateRouteNodeDisplay", members);
		assertMarker(sourcePath, source, "saveRouteEdgePort.saveRouteEdge(updated, command.updatedBy())", "TransitMasterService#createAccessibilityFacility/#updateAccessibilityFacility/#updateFacilityStatus/#updateRouteEdge/#updateRouteNodeDisplay", members);
		assertMarker(sourcePath, source, "merge(super.loadRouteNodes()", "JdbcTransitMasterOverrideRepository#loadAccessibilityFacilities/#loadRouteNodes/#loadRouteEdges", members);
		assertMarker(sourcePath, source, "saveRouteNodePort.saveRouteNode(updated, command.updatedBy())", "TransitMasterService#createAccessibilityFacility/#updateAccessibilityFacility/#updateFacilityStatus/#updateRouteEdge/#updateRouteNodeDisplay", members);
		assertMarker(sourcePath, source, "saveOverride(ROUTE_NODE, routeNode.id(), routeNode, updatedBy)", "JdbcTransitMasterOverrideRepository#saveFacilityStatus/#saveAccessibilityFacility/#saveRouteEdge/#saveRouteNode/#saveOverride", members);
		assertMarker(sourcePath, source, "facilityReportUseCase.createReportWithReceipt(request.toReceiptCommand())", "FacilityReportController#createReport/#reviewReport", members);
		assertMarker(sourcePath, source, "facilityReportUseCase.createReport(request.toCommand(principal.getName()))", "FacilityReportController#createReport/#reviewReport", members);
		assertMarker(sourcePath, source, "facilityReportUseCase.reviewReport(request.toCommand(reportId, principal.getName()))", "FacilityReportController#createReport/#reviewReport", members);
		assertMarker(sourcePath, source, "public FacilityReport createReport(CreateFacilityReportCommand command)", "FacilityReportService#createReport/#createReportWithReceipt/#reviewReport/#applyAcceptedReportToFacilityStatus", members);
		assertMarker(sourcePath, source, "public CreatedFacilityReport createReportWithReceipt(CreateFacilityReportCommand command)", "FacilityReportService#createReport/#createReportWithReceipt/#reviewReport/#applyAcceptedReportToFacilityStatus", members);
		assertMarker(sourcePath, source, "applyAcceptedReportToFacilityStatus(report, command.decision())", "FacilityReportService#createReport/#createReportWithReceipt/#reviewReport/#applyAcceptedReportToFacilityStatus", members);
		assertMarker(sourcePath, source, "public Optional<FacilityReport> loadReport(String reportId)", "JdbcFacilityReportRepository#loadReport/#saveReport", members);
		assertMarker(sourcePath, source, "public FacilityReport saveReport(FacilityReport report)", "JdbcFacilityReportRepository#loadReport/#saveReport", members);
		assertMarker(sourcePath, source, "HealthIndicator productionReadinessHealthIndicator(", "ProductionPersistenceReadinessConfiguration#productionReadinessHealthIndicator", members);
		assertMarker(sourcePath, source, "RouteV2IngressSecurity.configure", "SecurityConfig#routeV2IngressSecurityFilterChain", members);
		if (source.contains("\"/api/v1/realtime/**\",")) {
			assertThat(members).contains(new Member(sourcePath, "SecurityConfig#publicSecurityFilterChain"));
			int endpoint = source.indexOf("\"/api/v1/realtime/**\",");
			int matcher = source.lastIndexOf(".requestMatchers(", endpoint);
			int nextMatcher = source.indexOf(".requestMatchers(", endpoint + 1);
			assertThat(matcher).isGreaterThanOrEqualTo(0);
			assertThat(source.substring(matcher, nextMatcher < 0 ? source.length() : nextMatcher)).contains(".permitAll()");
		}
	}

	private static void assertMarker(String sourcePath, String source, String marker, String member, Set<Member> members) {
		if (source.contains(marker)) {
			assertThat(members).contains(new Member(sourcePath, member));
		}
	}

	private static void assertTransitIngress(String sourcePath, String source, Set<Member> members) {
		if (!source.contains("transitMasterAdminUseCase.createAccessibilityFacility(")
			&& !source.contains("transitMasterAdminUseCase.updateAccessibilityFacility(")
			&& !source.contains("transitMasterAdminUseCase.updateFacilityStatus(")
			&& !source.contains("transitMasterAdminUseCase.updateRouteEdge(")
			&& !source.contains("transitMasterAdminUseCase.updateRouteNodeDisplay(")) {
			return;
		}
		String member = (source.contains("@PatchMapping(\"/admin/stations/{stationId}/route-edges/{edgeId}\")")
			|| source.contains("@PatchMapping(\"/admin/stations/{stationId}/route-nodes/{nodeId}\")"))
			? "TransitMasterController#createAccessibilityFacility/#updateAccessibilityFacility/#updateFacilityStatus/#updateRouteEdge/#updateRouteNodeDisplay"
			: (source.contains("@PostMapping(\"/admin/stations/{stationId}/route-edges/{edgeId}/page\")")
				|| source.contains("@PostMapping(\"/admin/stations/{stationId}/route-nodes/{nodeId}/page\")"))
				? "TransitStationLayoutAdminPageController#updateRouteEdgeFromPage/#updateRouteNodeDisplayFromPage"
				: source.contains("@PostMapping(\"/admin/facilities/editor/page\")")
					? "TransitStationAdminPageController#saveFacilityFromPage"
					: source.contains("@PostMapping(\"/admin/facilities/{facilityId}/page/status\")")
						? "TransitFacilityAdminPageController#updateFacilityStatusFromPage"
						: "UNINVENTORIED_TRANSIT_WRITER";
		assertThat(members).contains(new Member(sourcePath, member));
	}

	private static ExpectedEntry entry(String kind, String sourcePath, String member, String pathOrTrigger, String... evidenceTokens) {
		return new ExpectedEntry(kind, sourcePath, member, pathOrTrigger, List.of(evidenceTokens));
	}

	private static String sha256(byte[] bytes) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}

	private static byte[] normalizedTextBytes(byte[] bytes) {
		return new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n').getBytes(StandardCharsets.UTF_8);
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

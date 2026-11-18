package com.easysubway.transit.adapter.out.persistence;

import com.easysubway.common.error.TransitDataAccessException;
import com.easysubway.journey.bundle.ActiveRouteBundleSnapshot;
import com.easysubway.journey.bundle.RouteBundleActivationException;
import com.easysubway.journey.bundle.RouteBundleActivationRegistry;
import com.easysubway.journey.bundle.RouteBundleFacilityCatalog;
import com.easysubway.transit.application.port.out.LoadTransitMasterPort;
import com.easysubway.transit.application.port.out.MasterDataCapability;
import com.easysubway.transit.application.port.out.MasterDataCapabilityPort;
import com.easysubway.transit.application.port.out.MasterDataCapabilityStatus;
import com.easysubway.transit.application.port.out.RollbackTransitMasterOverridePort;
import com.easysubway.transit.application.port.out.SaveAccessibilityFacilityStatusPort;
import com.easysubway.transit.application.port.out.SaveRouteEdgePort;
import com.easysubway.transit.application.port.out.SaveRouteNodePort;
import com.easysubway.transit.application.port.out.SaveSimplifiedStationLayoutStatusPort;
import com.easysubway.transit.application.port.out.SaveStationLayoutSourcePort;
import com.easysubway.transit.application.port.out.TransitMasterOverrideAudit;
import com.easysubway.transit.domain.AccessibilityFacility;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import com.easysubway.transit.domain.AccessibilityFacilityType;
import com.easysubway.transit.domain.DataConfidenceLevel;
import com.easysubway.transit.domain.DataQualityLevel;
import com.easysubway.transit.domain.DataSourceType;
import com.easysubway.transit.domain.RouteEdge;
import com.easysubway.transit.domain.RouteNode;
import com.easysubway.transit.domain.SimplifiedStationLayout;
import com.easysubway.transit.domain.SimplifiedStationLayoutStatus;
import com.easysubway.transit.domain.Station;
import com.easysubway.transit.domain.StationExit;
import com.easysubway.transit.domain.StationLayoutSource;
import com.easysubway.transit.domain.StationLine;
import com.easysubway.transit.domain.SubwayLine;
import com.easysubway.transit.domain.TransitOperator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile("prod | staging | release | prod-like")
public class JdbcTransitMasterOverrideRepository extends UnavailableTransitMasterRepository
	implements RollbackTransitMasterOverridePort, InitializingBean {

	public static final String FACILITY = "ACCESSIBILITY_FACILITY";
	public static final String LAYOUT_SOURCE = "STATION_LAYOUT_SOURCE";
	public static final String LAYOUT = "SIMPLIFIED_STATION_LAYOUT";
	public static final String ROUTE_NODE = "ROUTE_NODE";
	public static final String ROUTE_EDGE = "ROUTE_EDGE";

	private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");

	private final JdbcTemplate jdbcTemplate;
	private final ObjectReader jsonReader;
	private final ObjectWriter jsonWriter;
	private final Supplier<MeterRegistry> meterRegistrySupplier;
	private final Supplier<RouteBundleActivationRegistry> routeBundleActivationRegistrySupplier;
	private volatile Counter dbFailureCounter;
	private volatile DatabaseDialect databaseDialect;

	public JdbcTransitMasterOverrideRepository(DataSource dataSource, ObjectMapper objectMapper) {
		this(dataSource, objectMapper, new SimpleMeterRegistry());
	}

	public JdbcTransitMasterOverrideRepository(
		DataSource dataSource,
		ObjectMapper objectMapper,
		MeterRegistry meterRegistry
	) {
		this(dataSource, objectMapper, () -> meterRegistry, () -> null);
	}

	@Autowired
	public JdbcTransitMasterOverrideRepository(
		DataSource dataSource,
		ObjectMapper objectMapper,
		ObjectProvider<MeterRegistry> meterRegistryProvider,
		ObjectProvider<RouteBundleActivationRegistry> routeBundleActivationRegistryProvider
	) {
		this(
			dataSource,
			objectMapper,
			() -> meterRegistryProvider != null
				? meterRegistryProvider.getIfAvailable(SimpleMeterRegistry::new)
				: new SimpleMeterRegistry(),
			() -> routeBundleActivationRegistryProvider != null
				? routeBundleActivationRegistryProvider.getIfAvailable()
				: null
		);
	}

	JdbcTransitMasterOverrideRepository(
		DataSource dataSource,
		ObjectMapper objectMapper,
		MeterRegistry meterRegistry,
		Supplier<RouteBundleActivationRegistry> routeBundleActivationRegistrySupplier
	) {
		this(dataSource, objectMapper, () -> meterRegistry, routeBundleActivationRegistrySupplier);
	}

	private JdbcTransitMasterOverrideRepository(
		DataSource dataSource,
		ObjectMapper objectMapper,
		Supplier<MeterRegistry> meterRegistrySupplier,
		Supplier<RouteBundleActivationRegistry> routeBundleActivationRegistrySupplier
	) {
		this.jdbcTemplate = new JdbcTemplate(dataSource);
		this.jsonReader = objectMapper.reader();
		this.jsonWriter = objectMapper.writer();
		this.meterRegistrySupplier = meterRegistrySupplier != null
			? meterRegistrySupplier
			: SimpleMeterRegistry::new;
		this.routeBundleActivationRegistrySupplier = routeBundleActivationRegistrySupplier != null
			? routeBundleActivationRegistrySupplier
			: () -> null;
	}

	private void incrementDbFailure() {
		Counter counter = this.dbFailureCounter;
		if (counter == null) {
			MeterRegistry registry = meterRegistrySupplier.get();
			counter = (registry != null ? registry : new SimpleMeterRegistry())
				.counter("transit_master_db_failure_total");
			this.dbFailureCounter = counter;
		}
		counter.increment();
	}

	@Override
	public void afterPropertiesSet() {
		this.databaseDialect = detectDatabaseDialect();
	}

	@Override
	public MasterDataCapability masterDataCapability() {
		Optional<ActiveRouteBundleSnapshot> activeBundle = activeBundleSnapshot();
		String artifactVersion = activeBundle
			.map(snapshot -> snapshot.identity().bundleId())
			.orElse("static-seed+overrides");
		try {
			jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transit_master_overrides", Integer.class);
			jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transit_master_override_audits", Integer.class);
			jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transit_master_override_locks", Integer.class);
			return new MasterDataCapability(
				MasterDataCapabilityStatus.UP,
				true,
				true,
				artifactVersion,
				activeBundle.map(snapshot -> snapshot.admissionEvidence().manifestSha256()).orElse("jdbc"),
				activeBundle.map(ActiveRouteBundleSnapshot::activatedAt).orElseGet(Instant::now)
			);
		} catch (DataAccessException exception) {
			incrementDbFailure();
			return new MasterDataCapability(
				MasterDataCapabilityStatus.READ_ONLY,
				true,
				false,
				artifactVersion,
				"override-store-unready",
				null
			);
		}
	}

	@Override
	public List<Station> loadStations() {
		List<Station> seedStations = super.loadStations();
		Optional<ActiveRouteBundleSnapshot> activeBundle = activeBundleSnapshot();
		if (activeBundle.isEmpty() || !(activeBundle.get().runtimeView() instanceof RouteBundleFacilityCatalog catalog)) {
			return seedStations;
		}
		Map<String, Station> byId = new LinkedHashMap<>();
		seedStations.forEach(station -> byId.put(station.id(), station));
		LocalDate verifiedAt = LocalDate.ofInstant(activeBundle.get().activatedAt(), SEOUL_ZONE);
		for (String stationId : catalog.canonicalStationIds()) {
			byId.computeIfAbsent(stationId, id -> new Station(
				id,
				id,
				"",
				"전국",
				null,
				null,
				DataQualityLevel.LEVEL_2,
				DataSourceType.OFFICIAL_FILE,
				verifiedAt,
				true
			));
		}
		return List.copyOf(byId.values());
	}

	@Override
	public List<StationExit> loadStationExits() {
		List<StationExit> seedExits = super.loadStationExits();
		Optional<ActiveRouteBundleSnapshot> activeBundle = activeBundleSnapshot();
		if (activeBundle.isEmpty() || !(activeBundle.get().runtimeView() instanceof RouteBundleFacilityCatalog catalog)) {
			return seedExits;
		}
		List<RouteBundleFacilityCatalog.BundleStationExit> bundleExits = catalog.bundleStationExits();
		if (bundleExits.isEmpty()) {
			return seedExits;
		}
		Map<String, StationExit> byId = new LinkedHashMap<>();
		seedExits.forEach(exit -> byId.put(exit.id(), exit));
		for (RouteBundleFacilityCatalog.BundleStationExit bundleExit : bundleExits) {
			byId.put(bundleExit.id(), toStationExit(bundleExit));
		}
		return List.copyOf(byId.values());
	}

	@Override
	public List<AccessibilityFacility> loadAccessibilityFacilities() {
		List<AccessibilityFacility> seedFacilities = super.loadAccessibilityFacilities();
		Optional<ActiveRouteBundleSnapshot> activeBundle = activeBundleSnapshot();
		if (activeBundle.isEmpty() || !(activeBundle.get().runtimeView() instanceof RouteBundleFacilityCatalog catalog)) {
			return merge(seedFacilities, AccessibilityFacility::id, FACILITY, AccessibilityFacility.class);
		}
		List<RouteBundleFacilityCatalog.BundleFacility> bundleFacilities = catalog.bundleFacilities();
		if (bundleFacilities.isEmpty()) {
			return merge(seedFacilities, AccessibilityFacility::id, FACILITY, AccessibilityFacility.class);
		}
		Map<String, AccessibilityFacility> byId = new LinkedHashMap<>();
		seedFacilities.forEach(facility -> byId.put(facility.id(), facility));
		LocalDate fallbackDate = LocalDate.ofInstant(activeBundle.get().activatedAt(), SEOUL_ZONE);
		for (RouteBundleFacilityCatalog.BundleFacility bundleFacility : bundleFacilities) {
			byId.put(bundleFacility.id(), toAccessibilityFacility(bundleFacility, fallbackDate));
		}
		return merge(List.copyOf(byId.values()), AccessibilityFacility::id, FACILITY, AccessibilityFacility.class);
	}

	@Override
	public List<StationLayoutSource> loadStationLayoutSources() {
		return merge(super.loadStationLayoutSources(), StationLayoutSource::id, LAYOUT_SOURCE, StationLayoutSource.class);
	}

	@Override
	public List<SimplifiedStationLayout> loadSimplifiedStationLayouts() {
		return merge(super.loadSimplifiedStationLayouts(), SimplifiedStationLayout::id, LAYOUT, SimplifiedStationLayout.class);
	}

	@Override
	public List<RouteNode> loadRouteNodes() {
		return merge(super.loadRouteNodes(), RouteNode::id, ROUTE_NODE, RouteNode.class);
	}

	@Override
	public List<RouteEdge> loadRouteEdges() {
		return merge(super.loadRouteEdges(), RouteEdge::id, ROUTE_EDGE, RouteEdge.class);
	}

	@Override
	@Transactional
	public void saveFacilityStatus(String facilityId, AccessibilityFacilityStatus status, LocalDate updatedAt) {
		saveFacilityStatus(facilityId, status, updatedAt, "admin");
	}

	@Override
	@Transactional
	public void saveFacilityStatus(
		String facilityId,
		AccessibilityFacilityStatus status,
		LocalDate updatedAt,
		String updatedBy
	) {
		lockTarget(FACILITY, facilityId);
		loadAccessibilityFacility(facilityId).ifPresent(facility -> saveLockedOverride(FACILITY, facilityId, new AccessibilityFacility(
			facility.id(),
			facility.stationId(),
			facility.exitId(),
			facility.type(),
			facility.name(),
			facility.floorFrom(),
			facility.floorTo(),
			facility.latitude(),
			facility.longitude(),
			facility.description(),
			status,
			facility.dataConfidence(),
			DataSourceType.ADMIN_VERIFIED,
			updatedAt
		), updatedBy));
	}

	@Override
	@Transactional
	public void saveAccessibilityFacility(AccessibilityFacility facility) {
		saveAccessibilityFacility(facility, "admin");
	}

	@Override
	@Transactional
	public void saveAccessibilityFacility(AccessibilityFacility facility, String updatedBy) {
		saveOverride(FACILITY, facility.id(), facility, updatedBy);
	}

	@Override
	@Transactional
	public void saveStationLayoutSource(StationLayoutSource source) {
		saveStationLayoutSource(source, "admin");
	}

	@Override
	@Transactional
	public void saveStationLayoutSource(StationLayoutSource source, String updatedBy) {
		saveOverride(LAYOUT_SOURCE, source.id(), source, updatedBy);
	}

	@Override
	@Transactional
	public void saveSimplifiedStationLayoutStatus(
		String layoutId,
		SimplifiedStationLayoutStatus status,
		String reviewedBy,
		LocalDate updatedAt
	) {
		lockTarget(LAYOUT, layoutId);
		loadSimplifiedStationLayout(layoutId).ifPresent(layout -> saveLockedOverride(LAYOUT, layoutId, new SimplifiedStationLayout(
			layout.id(),
			layout.stationId(),
			layout.version() + 1,
			status,
			layout.sourceIds(),
			layout.confidenceLevel(),
			layout.baseFloor(),
			layout.layoutJson(),
			layout.renderedPreviewUrl(),
			layout.createdBy(),
			reviewedBy,
			status == SimplifiedStationLayoutStatus.PUBLISHED ? updatedAt : layout.publishedAt(),
			updatedAt
		), reviewedBy));
	}

	@Override
	@Transactional
	public void saveRouteNode(RouteNode routeNode) {
		saveRouteNode(routeNode, "admin");
	}

	@Override
	@Transactional
	public void saveRouteNode(RouteNode routeNode, String updatedBy) {
		saveOverride(ROUTE_NODE, routeNode.id(), routeNode, updatedBy);
	}

	@Override
	@Transactional
	public void saveRouteEdge(RouteEdge routeEdge) {
		saveRouteEdge(routeEdge, "admin");
	}

	@Override
	@Transactional
	public void saveRouteEdge(RouteEdge routeEdge, String updatedBy) {
		saveOverride(ROUTE_EDGE, routeEdge.id(), routeEdge, updatedBy);
	}

	@Override
	@Transactional
	public void rollbackMasterDataOverride(String entityType, String entityId, String updatedBy) {
		lockTarget(entityType, entityId);
		String currentPayload = activePayload(entityType, entityId).orElse(null);
		if (currentPayload == null) {
			return;
		}
		String previousPayload = lastPreviousPayload(entityType, entityId, currentPayload).orElse(null);
		if (previousPayload == null) {
			jdbcTemplate.update("""
				DELETE FROM transit_master_overrides
				WHERE entity_type = ? AND entity_id = ?
				""", entityType, entityId);
		} else {
			jdbcTemplate.update("""
				UPDATE transit_master_overrides
				SET payload_json = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP
				WHERE entity_type = ? AND entity_id = ?
				""", previousPayload, updatedBy, entityType, entityId);
		}
		insertAudit(entityType, entityId, "ROLLBACK", updatedBy, currentPayload, previousPayload);
	}

	@Override
	public List<TransitMasterOverrideAudit> listMasterDataOverrideAudits(String entityType, String entityId) {
		return jdbcTemplate.query("""
			SELECT audit_id, entity_type, entity_id, action, updated_by, updated_at
			FROM transit_master_override_audits
			WHERE entity_type = ? AND entity_id = ?
			ORDER BY audit_id DESC
			""", (resultSet, rowNumber) -> new TransitMasterOverrideAudit(
			resultSet.getLong("audit_id"),
			resultSet.getString("entity_type"),
			resultSet.getString("entity_id"),
			resultSet.getString("action"),
			resultSet.getString("updated_by"),
			resultSet.getObject("updated_at", LocalDateTime.class)
		), entityType, entityId);
	}

	private Optional<ActiveRouteBundleSnapshot> activeBundleSnapshot() {
		RouteBundleActivationRegistry registry = routeBundleActivationRegistrySupplier.get();
		if (registry == null) {
			return Optional.empty();
		}
		try {
			return Optional.of(registry.activeSnapshot());
		} catch (RouteBundleActivationException exception) {
			if (exception.reason() == RouteBundleActivationException.Reason.BUNDLE_UNAVAILABLE) {
				return Optional.empty();
			}
			throw new TransitDataAccessException("활성 경로 번들이 만료되어 마스터 데이터를 읽을 수 없습니다.", exception);
		}
	}

	private static StationExit toStationExit(RouteBundleFacilityCatalog.BundleStationExit bundleExit) {
		String name = bundleExit.description().isBlank()
			? bundleExit.exitNumber() + "번 출구"
			: bundleExit.description();
		return new StationExit(
			bundleExit.id(),
			bundleExit.stationId(),
			bundleExit.exitNumber(),
			name,
			bundleExit.latitude() != null ? BigDecimal.valueOf(bundleExit.latitude()) : null,
			bundleExit.longitude() != null ? BigDecimal.valueOf(bundleExit.longitude()) : null,
			bundleExit.hasElevatorConnection(),
			false,
			DataConfidenceLevel.MEDIUM,
			mapDataSourceType(bundleExit.dataSourceType())
		);
	}

	private static AccessibilityFacility toAccessibilityFacility(
		RouteBundleFacilityCatalog.BundleFacility bundleFacility,
		LocalDate fallbackDate
	) {
		LocalDate lastUpdatedAt = bundleFacility.verifiedAtEpochSeconds() > 0
			? LocalDate.ofInstant(Instant.ofEpochSecond(bundleFacility.verifiedAtEpochSeconds()), SEOUL_ZONE)
			: fallbackDate;
		return new AccessibilityFacility(
			bundleFacility.id(),
			bundleFacility.stationId(),
			bundleFacility.exitId(),
			mapFacilityType(bundleFacility.type()),
			bundleFacility.name(),
			bundleFacility.floorFrom().isBlank() ? null : bundleFacility.floorFrom(),
			bundleFacility.floorTo().isBlank() ? null : bundleFacility.floorTo(),
			null,
			null,
			bundleFacility.description(),
			mapFacilityStatus(bundleFacility.operationalStatus(), bundleFacility.status()),
			mapDataConfidence(bundleFacility.confidence()),
			mapDataSourceType(bundleFacility.provenanceKind()),
			lastUpdatedAt
		);
	}

	private static AccessibilityFacilityType mapFacilityType(String rawType) {
		return switch (rawType.toUpperCase(Locale.ROOT)) {
			case "ESCALATOR" -> AccessibilityFacilityType.ESCALATOR;
			case "WHEELCHAIR_LIFT", "LIFT" -> AccessibilityFacilityType.WHEELCHAIR_LIFT;
			case "RAMP" -> AccessibilityFacilityType.RAMP;
			case "ACCESSIBLE_TOILET" -> AccessibilityFacilityType.ACCESSIBLE_TOILET;
			case "TOILET" -> AccessibilityFacilityType.TOILET;
			case "NURSING_ROOM" -> AccessibilityFacilityType.NURSING_ROOM;
			case "CUSTOMER_CENTER" -> AccessibilityFacilityType.CUSTOMER_CENTER;
			default -> AccessibilityFacilityType.ELEVATOR;
		};
	}

	private static AccessibilityFacilityStatus mapFacilityStatus(String operationalStatus, String status) {
		String preferred = !operationalStatus.isBlank() && !"UNKNOWN".equalsIgnoreCase(operationalStatus)
			? operationalStatus
			: status;
		return switch (preferred.toUpperCase(Locale.ROOT)) {
			case "NORMAL", "AVAILABLE", "OPERATIONAL", "IN_SERVICE" -> AccessibilityFacilityStatus.NORMAL;
			case "BROKEN", "OUT_OF_SERVICE", "UNAVAILABLE", "FAULT" -> AccessibilityFacilityStatus.BROKEN;
			case "UNDER_CONSTRUCTION", "MAINTENANCE", "CONSTRUCTION" -> AccessibilityFacilityStatus.UNDER_CONSTRUCTION;
			case "CLOSED" -> AccessibilityFacilityStatus.CLOSED;
			case "USER_REPORTED" -> AccessibilityFacilityStatus.USER_REPORTED;
			case "ADMIN_VERIFIED" -> AccessibilityFacilityStatus.ADMIN_VERIFIED;
			default -> AccessibilityFacilityStatus.UNKNOWN;
		};
	}

	private static DataConfidenceLevel mapDataConfidence(int confidence) {
		if (confidence >= 90) {
			return DataConfidenceLevel.HIGH;
		}
		if (confidence >= 70) {
			return DataConfidenceLevel.MEDIUM;
		}
		if (confidence > 0) {
			return DataConfidenceLevel.LOW;
		}
		return DataConfidenceLevel.NEEDS_VERIFICATION;
	}

	private static DataSourceType mapDataSourceType(String rawSourceType) {
		if (rawSourceType == null || rawSourceType.isBlank()) {
			return DataSourceType.OFFICIAL_FILE;
		}
		return switch (rawSourceType.toUpperCase(Locale.ROOT)) {
			case "OFFICIAL_API" -> DataSourceType.OFFICIAL_API;
			case "OPERATOR_PAGE", "OPERATOR_CONFIRMED" -> DataSourceType.OPERATOR_PAGE;
			case "USER_REPORT" -> DataSourceType.USER_REPORT;
			case "ADMIN_VERIFIED", "FIELD_VERIFIED" -> DataSourceType.ADMIN_VERIFIED;
			case "PARTNER_FEED" -> DataSourceType.PARTNER_FEED;
			default -> DataSourceType.OFFICIAL_FILE;
		};
	}

	private <T> List<T> merge(List<T> catalog, Function<T, String> id, String entityType, Class<T> type) {
		Map<String, T> effective = new LinkedHashMap<>();
		catalog.forEach(item -> effective.put(id.apply(item), item));
		for (T override : activeOverrides(entityType, type)) {
			effective.put(id.apply(override), override);
		}
		return List.copyOf(effective.values());
	}

	private <T> List<T> activeOverrides(String entityType, Class<T> type) {
		try {
			return jdbcTemplate.query("""
				SELECT payload_json
				FROM transit_master_overrides
				WHERE entity_type = ?
				ORDER BY entity_id ASC
				""", (resultSet, rowNumber) -> readJson(resultSet.getString("payload_json"), type), entityType);
		} catch (DataAccessException exception) {
			incrementDbFailure();
			throw new TransitDataAccessException("PostgreSQL 마스터 override 조회 실패: " + entityType, exception);
		}
	}

	private Optional<SimplifiedStationLayout> loadSimplifiedStationLayout(String layoutId) {
		return loadSimplifiedStationLayouts()
			.stream()
			.filter(layout -> layout.id().equals(layoutId))
			.findFirst();
	}

	private void saveOverride(String entityType, String entityId, Object value, String updatedBy) {
		lockTarget(entityType, entityId);
		saveLockedOverride(entityType, entityId, value, updatedBy);
	}

	private void saveLockedOverride(String entityType, String entityId, Object value, String updatedBy) {
		String payload = writeJson(value);
		String previousPayload = activePayload(entityType, entityId).orElse(null);
		upsertOverride(entityType, entityId, payload, updatedBy);
		insertAudit(entityType, entityId, "UPSERT", updatedBy, previousPayload, payload);
	}

	private void lockTarget(String entityType, String entityId) {
		switch (requireDatabaseDialect()) {
			case POSTGRESQL -> jdbcTemplate.update("""
				INSERT INTO transit_master_override_locks (entity_type, entity_id)
				VALUES (?, ?)
				ON CONFLICT (entity_type, entity_id) DO NOTHING
				""", entityType, entityId);
			case H2 -> jdbcTemplate.update("""
				MERGE INTO transit_master_override_locks (entity_type, entity_id)
				KEY (entity_type, entity_id)
				VALUES (?, ?)
				""", entityType, entityId);
		}
		jdbcTemplate.queryForObject("""
			SELECT entity_type
			FROM transit_master_override_locks
			WHERE entity_type = ? AND entity_id = ?
			FOR UPDATE
			""", String.class, entityType, entityId);
	}

	private void upsertOverride(String entityType, String entityId, String payload, String updatedBy) {
		switch (requireDatabaseDialect()) {
			case POSTGRESQL -> jdbcTemplate.update("""
				INSERT INTO transit_master_overrides (entity_type, entity_id, payload_json, updated_by, updated_at)
				VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
				ON CONFLICT (entity_type, entity_id) DO UPDATE
				SET payload_json = EXCLUDED.payload_json,
					updated_by = EXCLUDED.updated_by,
					updated_at = CURRENT_TIMESTAMP
				""", entityType, entityId, payload, updatedBy);
			case H2 -> jdbcTemplate.update("""
				MERGE INTO transit_master_overrides (entity_type, entity_id, payload_json, updated_by, updated_at)
				KEY (entity_type, entity_id)
				VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
				""", entityType, entityId, payload, updatedBy);
		}
	}

	private DatabaseDialect requireDatabaseDialect() {
		DatabaseDialect current = databaseDialect;
		if (current != null) {
			return current;
		}
		DatabaseDialect detected = detectDatabaseDialect();
		databaseDialect = detected;
		return detected;
	}

	private DatabaseDialect detectDatabaseDialect() {
		String productName = jdbcTemplate.execute(
			(ConnectionCallback<String>) connection -> connection.getMetaData().getDatabaseProductName()
		);
		if (productName == null) {
			throw new IllegalStateException("database product name is unavailable");
		}
		return switch (productName) {
			case "PostgreSQL" -> DatabaseDialect.POSTGRESQL;
			case "H2" -> DatabaseDialect.H2;
			default -> throw new IllegalStateException("지원하지 않는 transit master override database: " + productName);
		};
	}

	private enum DatabaseDialect {
		POSTGRESQL,
		H2
	}

	private Optional<String> activePayload(String entityType, String entityId) {
		List<String> payloads = jdbcTemplate.queryForList("""
			SELECT payload_json
			FROM transit_master_overrides
			WHERE entity_type = ? AND entity_id = ?
			""", String.class, entityType, entityId);
		return payloads.stream().findFirst();
	}

	private Optional<String> lastPreviousPayload(String entityType, String entityId, String currentPayload) {
		List<String> payloads = jdbcTemplate.queryForList("""
			SELECT previous_payload_json
			FROM transit_master_override_audits
			WHERE entity_type = ? AND entity_id = ? AND action = 'UPSERT' AND payload_json = ?
			ORDER BY audit_id DESC
			LIMIT 1
			""", String.class, entityType, entityId, currentPayload);
		if (payloads.isEmpty() || payloads.getFirst() == null) {
			return Optional.empty();
		}
		return Optional.of(payloads.getFirst());
	}

	private void insertAudit(
		String entityType,
		String entityId,
		String action,
		String updatedBy,
		String previousPayload,
		String payload
	) {
		jdbcTemplate.update("""
			INSERT INTO transit_master_override_audits (
				entity_type, entity_id, action, updated_by, previous_payload_json, payload_json, updated_at
			)
			VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
			""", entityType, entityId, action, updatedBy, previousPayload, payload);
	}

	private String writeJson(Object value) {
		try {
			return jsonWriter.writeValueAsString(value);
		} catch (JsonProcessingException exception) {
			throw new IllegalArgumentException("마스터 데이터 override payload를 JSON으로 저장할 수 없습니다.", exception);
		}
	}

	private <T> T readJson(String payload, Class<T> type) {
		try {
			return jsonReader.forType(type).readValue(payload);
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException("마스터 데이터 override payload를 읽을 수 없습니다.", exception);
		}
	}
}

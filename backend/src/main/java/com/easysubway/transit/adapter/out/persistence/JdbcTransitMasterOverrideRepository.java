package com.easysubway.transit.adapter.out.persistence;

import com.easysubway.transit.application.port.out.MasterDataCapability;
import com.easysubway.transit.application.port.out.MasterDataCapabilityStatus;
import com.easysubway.transit.application.port.out.RollbackTransitMasterOverridePort;
import com.easysubway.transit.application.port.out.TransitMasterOverrideAudit;
import com.easysubway.transit.domain.AccessibilityFacility;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import com.easysubway.transit.domain.DataSourceType;
import com.easysubway.transit.domain.RouteEdge;
import com.easysubway.transit.domain.RouteNode;
import com.easysubway.transit.domain.SimplifiedStationLayout;
import com.easysubway.transit.domain.SimplifiedStationLayoutStatus;
import com.easysubway.transit.domain.StationLayoutSource;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile("prod")
public class JdbcTransitMasterOverrideRepository extends UnavailableTransitMasterRepository
	implements RollbackTransitMasterOverridePort {

	public static final String FACILITY = "ACCESSIBILITY_FACILITY";
	public static final String LAYOUT_SOURCE = "STATION_LAYOUT_SOURCE";
	public static final String LAYOUT = "SIMPLIFIED_STATION_LAYOUT";
	public static final String ROUTE_NODE = "ROUTE_NODE";
	public static final String ROUTE_EDGE = "ROUTE_EDGE";

	private final JdbcTemplate jdbcTemplate;
	private final ObjectMapper objectMapper;

	@Autowired
	public JdbcTransitMasterOverrideRepository(DataSource dataSource, ObjectMapper objectMapper) {
		this.jdbcTemplate = new JdbcTemplate(dataSource);
		this.objectMapper = objectMapper;
	}

	@Override
	public MasterDataCapability masterDataCapability() {
		try {
			jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transit_master_overrides", Integer.class);
			jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transit_master_override_audits", Integer.class);
			return new MasterDataCapability(
				MasterDataCapabilityStatus.UP,
				true,
				true,
				"static-seed+overrides",
				"jdbc",
				Instant.now()
			);
		} catch (DataAccessException exception) {
			return new MasterDataCapability(
				MasterDataCapabilityStatus.READ_ONLY,
				true,
				false,
				"static-seed",
				"override-store-unready",
				null
			);
		}
	}

	@Override
	public List<AccessibilityFacility> loadAccessibilityFacilities() {
		return merge(super.loadAccessibilityFacilities(), AccessibilityFacility::id, FACILITY, AccessibilityFacility.class);
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
		loadAccessibilityFacility(facilityId).ifPresent(facility -> saveOverride(FACILITY, facilityId, new AccessibilityFacility(
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
		loadSimplifiedStationLayout(layoutId).ifPresent(layout -> saveOverride(LAYOUT, layoutId, new SimplifiedStationLayout(
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
			resultSet.getTimestamp("updated_at").toLocalDateTime()
		), entityType, entityId);
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
			return List.of();
		}
	}

	private Optional<SimplifiedStationLayout> loadSimplifiedStationLayout(String layoutId) {
		return loadSimplifiedStationLayouts()
			.stream()
			.filter(layout -> layout.id().equals(layoutId))
			.findFirst();
	}

	private void saveOverride(String entityType, String entityId, Object value, String updatedBy) {
		String payload = writeJson(value);
		String previousPayload = activePayload(entityType, entityId).orElse(null);
		int updated = jdbcTemplate.update("""
			UPDATE transit_master_overrides
			SET payload_json = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP
			WHERE entity_type = ? AND entity_id = ?
			""", payload, updatedBy, entityType, entityId);
		if (updated > 0) {
			insertAudit(entityType, entityId, "UPSERT", updatedBy, previousPayload, payload);
			return;
		}

		try {
			jdbcTemplate.update("""
				INSERT INTO transit_master_overrides (entity_type, entity_id, payload_json, updated_by, updated_at)
				VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
				""", entityType, entityId, payload, updatedBy);
		} catch (DuplicateKeyException exception) {
			previousPayload = activePayload(entityType, entityId).orElse(null);
			jdbcTemplate.update("""
				UPDATE transit_master_overrides
				SET payload_json = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP
				WHERE entity_type = ? AND entity_id = ?
				""", payload, updatedBy, entityType, entityId);
		}
		insertAudit(entityType, entityId, "UPSERT", updatedBy, previousPayload, payload);
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
			return objectMapper.writeValueAsString(value);
		} catch (JsonProcessingException exception) {
			throw new IllegalArgumentException("마스터 데이터 override payload를 JSON으로 저장할 수 없습니다.", exception);
		}
	}

	private <T> T readJson(String payload, Class<T> type) {
		try {
			return objectMapper.readValue(payload, type);
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException("마스터 데이터 override payload를 읽을 수 없습니다.", exception);
		}
	}
}

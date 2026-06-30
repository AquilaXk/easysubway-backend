CREATE TABLE IF NOT EXISTS station_pathway_nodes (
  id VARCHAR(160) NOT NULL PRIMARY KEY,
  station_id VARCHAR(120) NOT NULL,
  line_id VARCHAR(120),
  node_type VARCHAR(40) NOT NULL,
  label VARCHAR(200) NOT NULL,
  level VARCHAR(40) NOT NULL DEFAULT '',
  legacy_internal_route_node_id VARCHAR(160) NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS station_pathway_edges (
  id VARCHAR(180) NOT NULL PRIMARY KEY,
  from_node_id VARCHAR(160) NOT NULL,
  to_node_id VARCHAR(160) NOT NULL,
  edge_type VARCHAR(40) NOT NULL DEFAULT 'WALK',
  duration_seconds INTEGER NOT NULL DEFAULT 0,
  distance_meters INTEGER NOT NULL DEFAULT 0,
  bidirectional BOOLEAN NOT NULL DEFAULT FALSE,
  includes_stairs BOOLEAN NOT NULL DEFAULT FALSE,
  requires_elevator BOOLEAN NOT NULL DEFAULT FALSE,
  requires_escalator BOOLEAN NOT NULL DEFAULT FALSE,
  level_from VARCHAR(40) NOT NULL DEFAULT '',
  level_to VARCHAR(40) NOT NULL DEFAULT '',
  requires_facility_id VARCHAR(160),
  min_width_cm INTEGER,
  slope_percent NUMERIC(5, 2),
  vertical_meters NUMERIC(6, 2),
  reliability_score INTEGER NOT NULL DEFAULT 100,
  accessibility_status VARCHAR(40) NOT NULL DEFAULT 'UNKNOWN',
  source_id VARCHAR(160) NOT NULL DEFAULT '',
  source_snapshot_id VARCHAR(180) NOT NULL DEFAULT '',
  provider_record_hash VARCHAR(128) NOT NULL DEFAULT '',
  provenance_kind VARCHAR(60) NOT NULL DEFAULT 'UNKNOWN',
  verification_status VARCHAR(40) NOT NULL DEFAULT 'UNKNOWN',
  last_verified_at TIMESTAMPTZ,
  evidence_hash VARCHAR(128) NOT NULL DEFAULT '',
  instruction TEXT NOT NULL DEFAULT '',
  legacy_internal_route_edge_id VARCHAR(180) NOT NULL DEFAULT '',
  CONSTRAINT fk_station_pathway_edges_from FOREIGN KEY (from_node_id) REFERENCES station_pathway_nodes(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_station_pathway_edges_to FOREIGN KEY (to_node_id) REFERENCES station_pathway_nodes(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT chk_station_pathway_duration CHECK (duration_seconds >= 0),
  CONSTRAINT chk_station_pathway_distance CHECK (distance_meters >= 0),
  CONSTRAINT chk_station_pathway_reliability CHECK (reliability_score >= 0 AND reliability_score <= 100)
);

CREATE TABLE IF NOT EXISTS transfer_rules (
  id VARCHAR(180) NOT NULL PRIMARY KEY,
  from_station_id VARCHAR(120) NOT NULL,
  from_line_id VARCHAR(120) NOT NULL,
  to_station_id VARCHAR(120) NOT NULL,
  to_line_id VARCHAR(120) NOT NULL,
  transfer_type VARCHAR(40) NOT NULL DEFAULT 'IN_STATION',
  min_transfer_seconds INTEGER NOT NULL DEFAULT 0,
  pathway_edge_id VARCHAR(180),
  strict_step_free_pathway_edge_id VARCHAR(180),
  source_id VARCHAR(160) NOT NULL DEFAULT '',
  verification_status VARCHAR(40) NOT NULL DEFAULT 'UNKNOWN',
  CONSTRAINT fk_transfer_rules_pathway FOREIGN KEY (pathway_edge_id) REFERENCES station_pathway_edges(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_transfer_rules_strict_pathway FOREIGN KEY (strict_step_free_pathway_edge_id) REFERENCES station_pathway_edges(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT chk_transfer_rules_min_seconds CHECK (min_transfer_seconds >= 0)
);

CREATE INDEX IF NOT EXISTS idx_station_pathway_nodes_station ON station_pathway_nodes(station_id, line_id, node_type);
CREATE INDEX IF NOT EXISTS idx_station_pathway_edges_from ON station_pathway_edges(from_node_id);
CREATE INDEX IF NOT EXISTS idx_transfer_rules_from_line ON transfer_rules(from_station_id, from_line_id);

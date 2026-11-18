CREATE TABLE IF NOT EXISTS station_pathway_nodes (
  id CHARACTER VARYING(160) PRIMARY KEY NOT NULL,
  label CHARACTER VARYING(200) NOT NULL,
  station_id CHARACTER VARYING(120) NOT NULL,
  line_id CHARACTER VARYING(120),
  node_type CHARACTER VARYING(40) NOT NULL,
  level CHARACTER VARYING(40) DEFAULT '' NOT NULL,
  legacy_internal_route_node_id CHARACTER VARYING(160) DEFAULT '' NOT NULL
);

CREATE TABLE IF NOT EXISTS station_pathway_edges (
  id CHARACTER VARYING(180) PRIMARY KEY NOT NULL,
  from_node_id CHARACTER VARYING(160) NOT NULL,
  to_node_id CHARACTER VARYING(160) NOT NULL,
  edge_type CHARACTER VARYING(40) DEFAULT 'WALK' NOT NULL,
  duration_seconds INT DEFAULT 0 NOT NULL,
  distance_meters INT DEFAULT 0 NOT NULL,
  bidirectional BOOL DEFAULT FALSE NOT NULL,
  includes_stairs BOOL DEFAULT FALSE NOT NULL,
  requires_elevator BOOL DEFAULT FALSE NOT NULL,
  requires_escalator BOOL DEFAULT FALSE NOT NULL,
  accessibility_status CHARACTER VARYING(40) DEFAULT 'UNKNOWN' NOT NULL,
  reliability_score INT DEFAULT 100 NOT NULL,
  level_from CHARACTER VARYING(40) DEFAULT '' NOT NULL,
  level_to CHARACTER VARYING(40) DEFAULT '' NOT NULL,
  requires_facility_id CHARACTER VARYING(160),
  min_width_cm INT,
  slope_percent DOUBLE PRECISION,
  vertical_meters DOUBLE PRECISION,
  source_id CHARACTER VARYING(160) DEFAULT '' NOT NULL,
  source_snapshot_id CHARACTER VARYING(180) DEFAULT '' NOT NULL,
  provider_record_hash CHARACTER VARYING(128) DEFAULT '' NOT NULL,
  provenance_kind CHARACTER VARYING(60) DEFAULT 'UNKNOWN' NOT NULL,
  verification_status CHARACTER VARYING(40) DEFAULT 'UNKNOWN' NOT NULL,
  last_verified_at TIMESTAMP WITH TIME ZONE,
  evidence_hash CHARACTER VARYING(128) DEFAULT '' NOT NULL,
  instruction CLOB DEFAULT '' NOT NULL,
  legacy_internal_route_edge_id CHARACTER VARYING(180) DEFAULT '' NOT NULL,
  FOREIGN KEY (from_node_id) REFERENCES station_pathway_nodes(id),
  FOREIGN KEY (to_node_id) REFERENCES station_pathway_nodes(id),
  CONSTRAINT h2_station_pathway_duration CHECK (duration_seconds >= 0),
  CONSTRAINT h2_station_pathway_distance CHECK (distance_meters >= 0),
  CONSTRAINT h2_station_pathway_reliability CHECK (reliability_score BETWEEN 0 AND 100)
);

CREATE TABLE IF NOT EXISTS transfer_rules (
  id CHARACTER VARYING(180) PRIMARY KEY NOT NULL,
  source_id CHARACTER VARYING(160) DEFAULT '' NOT NULL,
  verification_status CHARACTER VARYING(40) DEFAULT 'UNKNOWN' NOT NULL,
  from_station_id CHARACTER VARYING(120) NOT NULL,
  from_line_id CHARACTER VARYING(120) NOT NULL,
  to_station_id CHARACTER VARYING(120) NOT NULL,
  to_line_id CHARACTER VARYING(120) NOT NULL,
  transfer_type CHARACTER VARYING(40) DEFAULT 'IN_STATION' NOT NULL,
  min_transfer_seconds INT DEFAULT 0 NOT NULL,
  pathway_edge_id CHARACTER VARYING(180),
  strict_step_free_pathway_edge_id CHARACTER VARYING(180),
  FOREIGN KEY (pathway_edge_id) REFERENCES station_pathway_edges(id),
  FOREIGN KEY (strict_step_free_pathway_edge_id) REFERENCES station_pathway_edges(id),
  CONSTRAINT h2_transfer_rules_min_seconds CHECK (min_transfer_seconds >= 0)
);

CREATE INDEX IF NOT EXISTS idx_station_pathway_nodes_station ON station_pathway_nodes(station_id, line_id, node_type);
CREATE INDEX IF NOT EXISTS idx_station_pathway_edges_from ON station_pathway_edges(from_node_id);
CREATE INDEX IF NOT EXISTS idx_transfer_rules_from_line ON transfer_rules(from_station_id, from_line_id);

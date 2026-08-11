CREATE TABLE IF NOT EXISTS transit_master_override_locks (
	entity_type VARCHAR(80) NOT NULL,
	entity_id VARCHAR(160) NOT NULL,
	PRIMARY KEY (entity_type, entity_id)
);

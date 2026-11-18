CREATE TABLE IF NOT EXISTS datapack_candidates (
	id VARCHAR(120) NOT NULL PRIMARY KEY,
	scope_id VARCHAR(120) NOT NULL,
	artifact_kind VARCHAR(40) NOT NULL,
	version VARCHAR(80) NOT NULL,
	source_snapshot_set_hash datapack_sha256 NOT NULL,
	override_set_hash datapack_sha256 NOT NULL,
	build_spec_sha256 datapack_sha256 NOT NULL,
	source_inventory_sha256 datapack_sha256 NOT NULL,
	sqlite_sha256 datapack_sha256,
	gzip_sha256 datapack_sha256,
	manifest_sha256 datapack_sha256,
	coverage_status VARCHAR(30) NOT NULL,
	validator_status VARCHAR(30) NOT NULL,
	route_regression_status VARCHAR(30) NOT NULL,
	android_evidence_status VARCHAR(30) NOT NULL,
	approval_status VARCHAR(30) NOT NULL,
	created_at TIMESTAMP NOT NULL,
	CONSTRAINT chk_datapack_candidates_gate_status
		CHECK (
			coverage_status IN ('PASS', 'FAIL', 'PENDING')
			AND validator_status IN ('PASS', 'FAIL', 'PENDING')
			AND route_regression_status IN ('PASS', 'FAIL', 'PENDING')
			AND android_evidence_status IN ('PASS', 'FAIL', 'PENDING')
		),
	CONSTRAINT chk_datapack_candidates_approval_status
		CHECK (approval_status IN ('DRAFT', 'FAILED', 'READY_FOR_APPROVAL', 'APPROVED', 'PROMOTED', 'REJECTED'))
);

CREATE INDEX IF NOT EXISTS idx_datapack_candidates_scope_created
	ON datapack_candidates (scope_id ASC, created_at DESC, id ASC);

CREATE TABLE IF NOT EXISTS datapack_candidate_inputs (
	id VARCHAR(120) NOT NULL PRIMARY KEY,
	candidate_id VARCHAR(120) NOT NULL,
	source_snapshot_ids TEXT NOT NULL,
	approved_alias_ledger_hash datapack_sha256 NOT NULL,
	facility_evidence_ledger_hash datapack_sha256 NOT NULL,
	route_evidence_ledger_hash datapack_sha256 NOT NULL,
	approved_override_set_hash datapack_sha256 NOT NULL,
	created_at TIMESTAMP NOT NULL,
	CONSTRAINT fk_datapack_candidate_inputs_candidate
		FOREIGN KEY (candidate_id) REFERENCES datapack_candidates(id)
		ON DELETE RESTRICT ON UPDATE RESTRICT
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_datapack_candidate_inputs_candidate
	ON datapack_candidate_inputs (candidate_id ASC);

CREATE TABLE IF NOT EXISTS datapack_release_evidence_bundles (
	id VARCHAR(120) NOT NULL PRIMARY KEY,
	candidate_id VARCHAR(120) NOT NULL,
	evidence_bundle_sha256 datapack_sha256 NOT NULL,
	workflow_run_url VARCHAR(1000) NOT NULL,
	validator_status VARCHAR(30) NOT NULL,
	route_regression_status VARCHAR(30) NOT NULL,
	manifest_signature_status VARCHAR(30) NOT NULL,
	android_evidence_status VARCHAR(30) NOT NULL,
	created_at TIMESTAMP NOT NULL,
	CONSTRAINT fk_datapack_release_evidence_candidate
		FOREIGN KEY (candidate_id) REFERENCES datapack_candidates(id)
		ON DELETE RESTRICT ON UPDATE RESTRICT,
	CONSTRAINT chk_datapack_release_evidence_status
		CHECK (
			validator_status IN ('PASS', 'FAIL', 'PENDING')
			AND route_regression_status IN ('PASS', 'FAIL', 'PENDING')
			AND manifest_signature_status IN ('PASS', 'FAIL', 'PENDING')
			AND android_evidence_status IN ('PASS', 'FAIL', 'PENDING')
		)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_datapack_release_evidence_candidate
	ON datapack_release_evidence_bundles (candidate_id ASC);

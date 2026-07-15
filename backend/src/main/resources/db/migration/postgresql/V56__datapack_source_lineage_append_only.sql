DO $$
BEGIN
	IF EXISTS (
		SELECT 1
		FROM data_source_snapshots child
		JOIN data_source_snapshots parent
			ON parent.snapshot_id = child.previous_snapshot_id
		WHERE child.retrieved_at <= parent.retrieved_at
	) THEN
		RAISE EXCEPTION 'data_source_snapshots contains non-monotonic lineage';
	END IF;
END
$$;

CREATE FUNCTION guard_data_source_snapshot_lineage()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
	parent_retrieved_at TIMESTAMP;
BEGIN
	IF TG_OP = 'UPDATE' AND (
		NEW.snapshot_id IS DISTINCT FROM OLD.snapshot_id
		OR NEW.source_id IS DISTINCT FROM OLD.source_id
		OR NEW.previous_snapshot_id IS DISTINCT FROM OLD.previous_snapshot_id
	) THEN
		RAISE EXCEPTION 'data source snapshot lineage identity is append-only';
	END IF;

	IF NEW.previous_snapshot_id IS NOT NULL THEN
		IF NEW.previous_snapshot_id = NEW.snapshot_id THEN
			RAISE EXCEPTION 'data source snapshot cannot reference itself';
		END IF;
		SELECT retrieved_at
		INTO parent_retrieved_at
		FROM data_source_snapshots
		WHERE snapshot_id = NEW.previous_snapshot_id
			AND source_id = NEW.source_id;
		IF parent_retrieved_at IS NOT NULL AND NEW.retrieved_at <= parent_retrieved_at THEN
			RAISE EXCEPTION 'data source snapshot lineage must be time-monotonic';
		END IF;
	END IF;
	RETURN NEW;
END
$$;

CREATE TRIGGER trg_data_source_snapshot_lineage
BEFORE INSERT OR UPDATE ON data_source_snapshots
FOR EACH ROW EXECUTE FUNCTION guard_data_source_snapshot_lineage();

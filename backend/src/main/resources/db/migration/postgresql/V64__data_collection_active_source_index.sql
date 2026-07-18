-- Serialize legacy writers with the final duplicate check and index creation.
-- The table is a low-volume admin batch ledger, so this bounded write pause is
-- preferable to a concurrent build that can admit a duplicate RUNNING row.
LOCK TABLE data_collection_runs IN SHARE ROW EXCLUSIVE MODE;

-- A failed earlier attempt can leave an index with this name behind.
DROP INDEX IF EXISTS ux_data_collection_runs_running_source;

DO $migration$
DECLARE
    duplicate_sources TEXT;
BEGIN
    SELECT STRING_AGG(FORMAT('%s (%s RUNNING rows)', source, running_count), ', ' ORDER BY source)
    INTO duplicate_sources
    FROM (
        SELECT source, COUNT(*) AS running_count
        FROM data_collection_runs
        WHERE status = 'RUNNING'
        GROUP BY source
        HAVING COUNT(*) > 1
    ) conflicts;

    IF duplicate_sources IS NOT NULL THEN
        RAISE EXCEPTION 'V64 migration blocked: duplicate RUNNING data_collection_runs by source: %', duplicate_sources
            USING HINT = 'Resolve each stale or duplicate run explicitly, then retry the migration.';
    END IF;
END
$migration$;

CREATE UNIQUE INDEX ux_data_collection_runs_running_source
    ON data_collection_runs (source)
    WHERE status = 'RUNNING';

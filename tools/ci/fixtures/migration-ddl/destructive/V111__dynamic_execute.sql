DO $migration$
BEGIN
    EXECUTE 'ALTER TABLE facility_reports DROP COLUMN legacy_note';
END
$migration$;

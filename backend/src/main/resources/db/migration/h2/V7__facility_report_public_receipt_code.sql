ALTER TABLE facility_reports
	ADD COLUMN IF NOT EXISTS public_receipt_code VARCHAR(16);

UPDATE facility_reports
SET public_receipt_code = 'ES-' || LPAD(CAST((
	SELECT COUNT(*)
	FROM facility_reports existing_report
	WHERE existing_report.report_id <= facility_reports.report_id
) AS VARCHAR), 12, '0')
WHERE public_receipt_code IS NULL;

ALTER TABLE facility_reports
	ALTER COLUMN public_receipt_code SET NOT NULL;

ALTER TABLE facility_reports
	ADD CONSTRAINT chk_facility_reports_public_receipt_code
		CHECK (public_receipt_code REGEXP '^ES-[0-9A-Z]{1,12}$');

CREATE UNIQUE INDEX IF NOT EXISTS ux_facility_reports_public_receipt_code
	ON facility_reports (public_receipt_code);

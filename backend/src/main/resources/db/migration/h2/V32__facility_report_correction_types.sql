ALTER TABLE facility_reports
	DROP CONSTRAINT IF EXISTS chk_facility_reports_report_type;

ALTER TABLE facility_reports
	ADD CONSTRAINT chk_facility_reports_report_type
		CHECK (report_type IN (
			'BROKEN',
			'UNDER_CONSTRUCTION',
			'CLOSED',
			'ROUTE_BLOCKED',
			'ELEVATOR_UNAVAILABLE',
			'STAIRS_PRESENT',
			'ETA_INACCURATE',
			'TRANSFER_IMPOSSIBLE',
			'LOCATION_WRONG',
			'INFORMATION_WRONG',
			'RECOVERED'
		));

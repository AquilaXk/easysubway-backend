-- Report photos are uploaded to object storage before report creation.

ALTER TABLE facility_reports
	DROP COLUMN IF EXISTS photo_data_base64;

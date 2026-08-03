ALTER DOMAIN postal_code ADD CONSTRAINT postal_code_format CHECK (VALUE ~ '^[0-9]+$');

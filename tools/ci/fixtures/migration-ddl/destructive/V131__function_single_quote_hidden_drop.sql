CREATE FUNCTION destructive_route_guard() RETURNS void AS 'BEGIN DROP TABLE hidden_table; END' LANGUAGE plpgsql;

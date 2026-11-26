-- H2 relies on V52 uniqueness/FK constraints and service guards; PostgreSQL V56 enforces PL/pgSQL trigger monotonicity.
SELECT 1 WHERE FALSE;

CREATE INDEX CONCURRENTLY idx_route_search_results_status_created ON route_search_results (status, created_at DESC, route_search_id ASC);

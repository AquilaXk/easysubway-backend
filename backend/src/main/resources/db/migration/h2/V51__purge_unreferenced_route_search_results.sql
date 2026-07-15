DELETE FROM route_search_results AS route
WHERE NOT EXISTS (
    SELECT 1 FROM favorite_routes AS favorite
    WHERE favorite.route_search_id = route.route_search_id
)
AND NOT EXISTS (
    SELECT 1 FROM favorite_route_stations AS station
    WHERE station.route_search_id = route.route_search_id
)
AND NOT EXISTS (
    SELECT 1 FROM route_feedbacks AS feedback
    WHERE feedback.route_search_id = route.route_search_id
);

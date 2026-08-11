package com.easysubway.route.application.service;

import com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableRealtimeQuery;
import com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableRealtimeUpdates;
import java.util.List;

@FunctionalInterface
public interface JourneyTimetableRealtimeResolver {

	TimetableRealtimeUpdates resolve(List<TimetableRealtimeQuery> queries);
}

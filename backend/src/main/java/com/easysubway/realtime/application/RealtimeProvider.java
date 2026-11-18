package com.easysubway.realtime.application;

import com.easysubway.realtime.domain.RealtimeArrival;
import com.easysubway.realtime.domain.RealtimeTrainPosition;
import java.util.List;

public interface RealtimeProvider {
	List<RealtimeArrival> arrivals(RealtimeQuery query);

	default List<RealtimeTrainPosition> trainPositions(RealtimeQuery query) {
		return List.of();
	}
}

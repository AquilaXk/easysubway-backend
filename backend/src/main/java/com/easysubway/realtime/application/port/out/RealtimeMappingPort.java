package com.easysubway.realtime.application.port.out;

import com.easysubway.realtime.application.RealtimeQuery;
import com.easysubway.realtime.domain.RealtimeMapping;
import java.util.Optional;

public interface RealtimeMappingPort {
	Optional<RealtimeMapping> findArrivalMapping(String providerId, RealtimeQuery query);

	Optional<RealtimeMapping> findTrainPositionMapping(String providerId, RealtimeQuery query);
}

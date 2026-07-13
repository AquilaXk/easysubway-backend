package com.easysubway.realtime.application.port.out;

import com.easysubway.realtime.domain.RealtimeArrivalObservation;
import java.time.Instant;
import java.util.List;

public interface RealtimeArrivalArchivePort {

	RealtimeArrivalArchivePort NO_OP = new RealtimeArrivalArchivePort() {
		@Override
		public void saveAll(List<RealtimeArrivalObservation> observations) {
		}

		@Override
		public int deleteExpired(Instant now) {
			return 0;
		}
	};

	void saveAll(List<RealtimeArrivalObservation> observations);

	int deleteExpired(Instant now);
}

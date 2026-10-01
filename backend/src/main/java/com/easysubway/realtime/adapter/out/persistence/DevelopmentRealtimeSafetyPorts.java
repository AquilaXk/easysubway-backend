package com.easysubway.realtime.adapter.out.persistence;

import com.easysubway.realtime.application.port.out.RealtimeArrivalArchivePort;
import com.easysubway.realtime.domain.RealtimeArrivalObservation;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile({"default", "dev", "test"})
public class DevelopmentRealtimeSafetyPorts implements RealtimeArrivalArchivePort {

	@Override
	public void saveAll(List<RealtimeArrivalObservation> observations) {
		// 로컬·테스트 profile은 운영 archive를 생성하지 않는다.
	}

	@Override
	public int deleteExpired(Instant now) {
		Objects.requireNonNull(now, "now must not be null");
		return 0;
	}
}


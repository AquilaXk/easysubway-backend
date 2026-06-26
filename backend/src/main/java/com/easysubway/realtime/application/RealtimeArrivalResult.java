package com.easysubway.realtime.application;

import com.easysubway.realtime.domain.RealtimeArrival;
import com.easysubway.realtime.domain.RealtimeStatus;
import java.util.List;

public record RealtimeArrivalResult(
	RealtimeStatus status,
	String fallbackCode,
	String message,
	String receivedAt,
	String providerId,
	List<RealtimeArrival> arrivals
) {
	public static RealtimeArrivalResult fresh(String receivedAt, List<RealtimeArrival> arrivals) {
		return new RealtimeArrivalResult(
			RealtimeStatus.FRESH,
			null,
			null,
			receivedAt,
			"seoul-topis",
			List.copyOf(arrivals)
		);
	}

	public static RealtimeArrivalResult unsupported(String fallbackCode, String message) {
		return new RealtimeArrivalResult(
			RealtimeStatus.UNSUPPORTED,
			fallbackCode,
			message,
			null,
			"seoul-topis",
			List.of()
		);
	}

	public RealtimeArrivalResult stale() {
		return new RealtimeArrivalResult(
			RealtimeStatus.STALE,
			"STALE_CACHE",
			"실시간 정보를 새로 받지 못해 마지막 정보를 보여줍니다.",
			receivedAt,
			providerId,
			arrivals
		);
	}

	public static RealtimeArrivalResult unavailable(String fallbackCode) {
		return new RealtimeArrivalResult(
			RealtimeStatus.UNAVAILABLE,
			fallbackCode,
			"실시간 정보를 불러오지 못했습니다. 역 정보와 경로 검색은 계속 이용할 수 있습니다.",
			null,
			"seoul-topis",
			List.of()
		);
	}
}

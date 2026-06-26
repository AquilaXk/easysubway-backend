package com.easysubway.realtime.application;

import com.easysubway.realtime.domain.RealtimeStatus;
import com.easysubway.realtime.domain.RealtimeTrainPosition;
import java.util.List;

public record RealtimeTrainPositionResult(
	RealtimeStatus status,
	String fallbackCode,
	String message,
	String receivedAt,
	String providerId,
	String sourceNotice,
	List<RealtimeTrainPosition> trainPositions
) {
	public static RealtimeTrainPositionResult fresh(String receivedAt, List<RealtimeTrainPosition> trainPositions) {
		return new RealtimeTrainPositionResult(
			RealtimeStatus.FRESH,
			null,
			null,
			receivedAt,
			"seoul-topis",
			"열차 위치는 GPS가 아니라 운행 정보 기준 위치입니다.",
			List.copyOf(trainPositions)
		);
	}

	public static RealtimeTrainPositionResult unsupported(String fallbackCode, String message) {
		return new RealtimeTrainPositionResult(
			RealtimeStatus.UNSUPPORTED,
			fallbackCode,
			message,
			null,
			"seoul-topis",
			"열차 위치는 GPS가 아니라 운행 정보 기준 위치입니다.",
			List.of()
		);
	}

	public RealtimeTrainPositionResult stale() {
		return new RealtimeTrainPositionResult(
			RealtimeStatus.STALE,
			"STALE_CACHE",
			"실시간 열차 위치를 새로 받지 못해 마지막 정보를 보여줍니다.",
			receivedAt,
			providerId,
			sourceNotice,
			trainPositions
		);
	}

	public static RealtimeTrainPositionResult unavailable(String fallbackCode) {
		return new RealtimeTrainPositionResult(
			RealtimeStatus.UNAVAILABLE,
			fallbackCode,
			"실시간 열차 위치를 불러오지 못했습니다.",
			null,
			"seoul-topis",
			"열차 위치는 GPS가 아니라 운행 정보 기준 위치입니다.",
			List.of()
		);
	}
}

package com.easysubway.field.application.service;

import com.easysubway.common.error.ResourceNotFoundException;
import com.easysubway.field.application.port.in.FieldVerificationUseCase;
import com.easysubway.field.domain.FieldVerificationItem;
import com.easysubway.field.domain.FieldVerificationItemType;
import com.easysubway.field.domain.FieldVerificationSession;
import com.easysubway.field.domain.FieldVerificationStatus;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class FieldVerificationService implements FieldVerificationUseCase {

	private static final String SANGNOKSU_STATION_ID = "station-sangnoksu";
	private static final FieldVerificationSession SANGNOKSU_BASELINE = new FieldVerificationSession(
		"field-verification-sangnoksu-2026-06",
		SANGNOKSU_STATION_ID,
		"상록수역",
		LocalDate.of(2026, 6, 19),
		"field-team",
		FieldVerificationStatus.IN_PROGRESS,
		"첫 현장 검증 지역 기준선",
		List.of(
			item("field-verification-sangnoksu-exit", FieldVerificationItemType.EXIT, "주요 출구 연결", FieldVerificationStatus.VERIFIED),
			item("field-verification-sangnoksu-elevator", FieldVerificationItemType.ELEVATOR, "엘리베이터 위치와 운행 상태", FieldVerificationStatus.VERIFIED),
			item("field-verification-sangnoksu-escalator", FieldVerificationItemType.ESCALATOR, "에스컬레이터 위치와 방향", FieldVerificationStatus.PLANNED),
			item("field-verification-sangnoksu-restroom", FieldVerificationItemType.RESTROOM, "일반/장애인 화장실 위치", FieldVerificationStatus.PLANNED),
			item("field-verification-sangnoksu-platform-transfer", FieldVerificationItemType.PLATFORM_TRANSFER, "승강장과 환승 접근 동선", FieldVerificationStatus.PLANNED)
		)
	);

	@Override
	public FieldVerificationSession getStationVerification(String stationId) {
		if (SANGNOKSU_STATION_ID.equals(stationId)) {
			return SANGNOKSU_BASELINE;
		}
		throw new ResourceNotFoundException("현장 검증 기준선을 찾을 수 없습니다.");
	}

	private static FieldVerificationItem item(
		String id,
		FieldVerificationItemType type,
		String targetName,
		FieldVerificationStatus status
	) {
		return new FieldVerificationItem(id, type, targetName, status, null);
	}
}

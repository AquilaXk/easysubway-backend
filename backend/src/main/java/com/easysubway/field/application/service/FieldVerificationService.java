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
	private static final String SADANG_STATION_ID = "station-sadang";
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
	private static final FieldVerificationSession SADANG_BASELINE = new FieldVerificationSession(
		"field-verification-sadang-2026-06",
		SADANG_STATION_ID,
		"사당역",
		LocalDate.of(2026, 6, 19),
		"field-team",
		FieldVerificationStatus.PLANNED,
		"주요 환승역 현장 검증 확대 기준선",
		List.of(
			item("field-verification-sadang-exit", FieldVerificationItemType.EXIT, "2호선/4호선 출구 연결", FieldVerificationStatus.PLANNED),
			item("field-verification-sadang-elevator", FieldVerificationItemType.ELEVATOR, "환승 구간 엘리베이터 위치와 운행 상태", FieldVerificationStatus.PLANNED),
			item("field-verification-sadang-escalator", FieldVerificationItemType.ESCALATOR, "환승 구간 에스컬레이터 위치와 방향", FieldVerificationStatus.PLANNED),
			item("field-verification-sadang-restroom", FieldVerificationItemType.RESTROOM, "일반/장애인 화장실 위치", FieldVerificationStatus.PLANNED),
			item("field-verification-sadang-platform-transfer", FieldVerificationItemType.PLATFORM_TRANSFER, "2호선과 4호선 환승 접근 동선", FieldVerificationStatus.PLANNED)
		)
	);

	@Override
	public List<FieldVerificationSession> listStationVerifications() {
		return List.of(SANGNOKSU_BASELINE, SADANG_BASELINE);
	}

	@Override
	public FieldVerificationSession getStationVerification(String stationId) {
		if (SANGNOKSU_STATION_ID.equals(stationId)) {
			return SANGNOKSU_BASELINE;
		}
		if (SADANG_STATION_ID.equals(stationId)) {
			return SADANG_BASELINE;
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

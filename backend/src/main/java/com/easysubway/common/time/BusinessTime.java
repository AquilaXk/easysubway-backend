package com.easysubway.common.time;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 업무 일자와 업무 시각의 기준 시간대(한국 표준시)를 한 곳에 명시한다.
 *
 * <p>사건 발생 시각은 UTC({@link java.time.Instant})로 기록하고 내보낸다. 반면 "오늘", 시설 정보
 * 갱신일, 일별 지표 날짜처럼 한국 사용자·운영자의 달력에 맞아야 하는 값은 이 시간대로 센다. 시계는
 * 주입된 {@link Clock}을 그대로 쓰고 시간대만 여기서 정한다.
 */
public final class BusinessTime {

	/** 어노테이션 속성(예: {@code @Scheduled(zone)})에 쓰는 시간대 이름. */
	public static final String ZONE_NAME = "Asia/Seoul";
	public static final ZoneId ZONE = ZoneId.of(ZONE_NAME);

	private BusinessTime() {
	}

	/** {@code clock}이 가리키는 순간의 한국 달력 날짜. */
	public static LocalDate today(Clock clock) {
		return LocalDate.ofInstant(clock.instant(), ZONE);
	}
}

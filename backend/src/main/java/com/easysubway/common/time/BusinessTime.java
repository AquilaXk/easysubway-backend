package com.easysubway.common.time;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

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
	/**
	 * SQL이 UTC로 저장된 시각을 한국 날짜로 묶을 때 더하는 시간. 한국은 서머타임이 없어 연중 같다.
	 * {@code BusinessTimeTest}가 {@link #ZONE}의 실제 오프셋과 같은지 확인한다.
	 */
	public static final int UTC_OFFSET_HOURS = 9;

	private BusinessTime() {
	}

	/** {@code clock}이 가리키는 순간의 한국 달력 날짜. */
	public static LocalDate today(Clock clock) {
		return LocalDate.ofInstant(clock.instant(), ZONE);
	}

	/**
	 * 한국 날짜가 시작되는 순간을 UTC 벽시계 시각으로 옮긴다. UTC로 저장된 시각과 한국 날짜 경계를
	 * 비교하는 범위 질의({@code >= 시작 AND < 다음 날 시작})에 쓴다.
	 */
	public static LocalDateTime startOfDayUtcLocal(LocalDate koreanDate) {
		return koreanDate.atStartOfDay(ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
	}

	/** 한국 날짜의 다음 날이 시작되는 순간을 UTC 벽시계 시각으로 옮긴다. 기간 필터의 제외({@code <}) 끝 경계에 쓴다. */
	public static LocalDateTime startOfNextDayUtcLocal(LocalDate koreanDate) {
		return startOfDayUtcLocal(koreanDate.plusDays(1));
	}

	/** 한국 날짜가 시작되는 순간. {@link Instant}로 저장된 시각과 비교하는 기간 필터에 쓴다. */
	public static Instant startOfDayInstant(LocalDate koreanDate) {
		return koreanDate.atStartOfDay(ZONE).toInstant();
	}

	/** 한국 날짜의 다음 날이 시작되는 순간. {@link Instant}로 저장된 시각과 비교하는 기간 필터의 제외({@code <}) 끝 경계에 쓴다. */
	public static Instant startOfNextDayInstant(LocalDate koreanDate) {
		return startOfDayInstant(koreanDate.plusDays(1));
	}

	/** 한국 벽시계로 입력된 시각을 UTC 벽시계 시각으로 옮긴다. 운영자가 입력한 시각을 UTC로 저장할 때 쓴다. */
	public static LocalDateTime toUtcLocal(LocalDateTime koreanWallClock) {
		return koreanWallClock.atZone(ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
	}

	/** UTC 벽시계로 저장된 시각을 한국 벽시계 시각으로 옮긴다. 저장된 시각을 운영자 화면에 보여 줄 때 쓴다. */
	public static LocalDateTime fromUtcLocal(LocalDateTime utcWallClock) {
		return utcWallClock.atOffset(ZoneOffset.UTC).atZoneSameInstant(ZONE).toLocalDateTime();
	}

	/** UTC 벽시계로 저장된 시각이 속한 한국 달력 날짜. */
	public static LocalDate dateOfUtc(LocalDateTime utcWallClock) {
		return utcWallClock.atOffset(ZoneOffset.UTC).atZoneSameInstant(ZONE).toLocalDate();
	}
}

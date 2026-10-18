package com.easysubway.common.web;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 저장소가 UTC 벽시계 시각({@link LocalDateTime})으로 들고 있는 사건 시각(접수·발행·갱신 시각)을
 * 응답용 {@link Instant}로 바꾼다. {@code Instant}는 {@code Z} offset이 붙은 ISO-8601로 직렬화되어
 * 받는 쪽이 시간대를 추측하지 않는다.
 *
 * <p>저장소의 JDBC 어댑터는 아직 {@code Timestamp.valueOf}·{@code toLocalDateTime}으로 UTC 벽시계를
 * JVM 기본 시간대를 거쳐 왕복한다. 쓰기와 읽기가 같은 변환이라 DST가 없는 시간대에서는 값이 보존되며, 이
 * 변환의 남은 사용처는 {@code JdbcDefaultZoneConversionAllowlist}가 고정한다.
 */
public final class UtcTimestamps {

	private UtcTimestamps() {
	}

	/** {@code utc}가 null이면 null이다. 값이 없는 선택 필드(처리 시각 등)를 그대로 내려보낸다. */
	public static Instant toInstant(LocalDateTime utc) {
		return utc == null ? null : utc.toInstant(ZoneOffset.UTC);
	}
}

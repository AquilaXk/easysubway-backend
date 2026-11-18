package com.easysubway.architecture;

import java.util.Set;

/**
 * 기본 시간대 JDBC 변환({@code Timestamp.valueOf}·{@code toLocalDateTime} 등)을 이미 쓰는 곳.
 * 키는 {@code 클래스#메서드}이며 모두 JDBC 어댑터의 행 매핑·바인딩이다. 어댑터를
 * {@code setObject(LocalDateTime)}·{@code getObject(LocalDateTime.class)}로 옮기면 해당 항목을 지운다.
 */
final class JdbcDefaultZoneConversionAllowlist {

	static final Set<String> SITES = Set.of();

	private JdbcDefaultZoneConversionAllowlist() {
	}
}

package com.easysubway.route.domain;

/**
 * 4대 핵심 페르소나 매핑 (McRAPTOR 파레토 프런티어 K=4 보존).
 *
 * <ul>
 *   <li>{@link #FASTEST}: 최속 경로 (비장애인 및 최단 소요시간 우선 승객)</li>
 *   <li>{@link #STEP_FREE}: 무단차 최적 (휠체어·유모차 이용자, 계단수 = 0, 엘리베이터 전용)</li>
 *   <li>{@link #MIN_WALK}: 최소 보행 (노약자 및 보행 장애인, 이동 거리 최소화)</li>
 *   <li>{@link #RELAXED_SLACK}: 여유 환승 (환승 서행 승객, 최소 환승 여유시간 5분 이상 확보)</li>
 * </ul>
 */
public enum RoutePersona {
	FASTEST,
	STEP_FREE,
	MIN_WALK,
	RELAXED_SLACK
}

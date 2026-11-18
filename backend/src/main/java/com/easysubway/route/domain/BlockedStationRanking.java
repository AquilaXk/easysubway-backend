package com.easysubway.route.domain;

/**
 * 차단(BLOCKED) 경로 검색에 얽힌 역별 집계(#1744). 출발·도착 어느 쪽으로든 차단 검색에 등장한
 * 횟수를 센다. 분석 화면의 "차단 상위 역 랭킹"에서 역 허브로 딥링크한다.
 *
 * @param stationId    역 식별자(역 허브 딥링크 대상)
 * @param stationName  역 이름(미해결 시 역 식별자로 대체)
 * @param blockedCount 차단 검색 등장 횟수
 */
public record BlockedStationRanking(String stationId, String stationName, long blockedCount) {

	public BlockedStationRanking {
		if (stationId == null || stationId.isBlank()) {
			throw new InvalidRouteSearchException("차단 상위 역 랭킹에는 역 식별자가 필요합니다.");
		}
		stationId = stationId.trim();
		stationName = stationName == null || stationName.isBlank() ? stationId : stationName.trim();
		if (blockedCount < 0) {
			throw new InvalidRouteSearchException("차단 검색 횟수는 0 이상이어야 합니다.");
		}
	}
}

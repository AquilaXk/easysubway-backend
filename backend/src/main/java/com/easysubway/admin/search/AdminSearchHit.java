package com.easysubway.admin.search;

/**
 * 통합 검색(#1738) 결과 한 건: 화면·엔티티로 이동하는 링크.
 *
 * @param label    표시 이름(예: "제보 확인 대기열", "상록수(station-sangnoksu)")
 * @param sublabel 보조 설명(예: 그룹명·유형)
 * @param href     이동 경로(관리자 내부 경로)
 */
public record AdminSearchHit(String label, String sublabel, String href) {
}

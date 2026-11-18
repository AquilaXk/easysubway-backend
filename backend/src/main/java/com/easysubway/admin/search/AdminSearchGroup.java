package com.easysubway.admin.search;

import java.util.List;

/**
 * 통합 검색(#1738) 결과 그룹(유형별): 메뉴·역·시설·제보·장애·데이터팩.
 *
 * @param type  유형 키(menu·station·facility·report·incident·datapack)
 * @param label 유형 표시 이름(예: "메뉴", "역")
 * @param hits  결과 목록
 */
public record AdminSearchGroup(String type, String label, List<AdminSearchHit> hits) {

	public AdminSearchGroup {
		hits = List.copyOf(hits);
	}

	public boolean isEmpty() {
		return hits.isEmpty();
	}
}

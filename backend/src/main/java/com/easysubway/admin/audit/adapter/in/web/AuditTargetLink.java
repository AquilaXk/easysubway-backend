package com.easysubway.admin.audit.adapter.in.web;

import org.springframework.web.util.UriComponentsBuilder;

/**
 * 감사 상세 드로어(#1747)의 target 딥링크. 감사 이벤트의 target 유형·ID를 아는 관리자 화면으로 연결한다
 * (제보 상세 #1740, 역 허브 #1741). 매핑이 없거나 ID가 비면 링크를 만들지 않는다(null).
 */
final class AuditTargetLink {

	private AuditTargetLink() {
	}

	static String hrefFor(String targetType, String targetId) {
		if (targetType == null || targetId == null || targetId.isBlank() || "-".equals(targetId)) {
			return null;
		}
		return switch (targetType) {
			case "FACILITY_REPORT" -> path("/admin/reports/" + targetId + "/page");
			case "STATION" -> path("/admin/stations/" + targetId + "/page");
			default -> null;
		};
	}

	private static String path(String rawPath) {
		return UriComponentsBuilder.fromPath(rawPath).build().encode().toUriString();
	}
}

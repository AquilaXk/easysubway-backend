package com.easysubway.admin.audit.adapter.in.web;

import com.easysubway.admin.audit.domain.AdminAuditEventType;
import com.easysubway.admin.audit.domain.AdminAuditOutcome;

/** 감사 화면(#1747)의 유형·결과 한글 라벨. 코드 값은 그대로 노출하지 않고 사람이 읽는 라벨로 치환한다. */
final class AuditLabels {

	private AuditLabels() {
	}

	static String eventType(AdminAuditEventType type) {
		return switch (type) {
			case LOGIN -> "로그인";
			case LOGIN_FAILURE -> "로그인 실패";
			case LOGOUT -> "로그아웃";
			case ADMIN_ACTION -> "관리자 작업";
			case PRIVACY_READ -> "개인정보 조회";
			case SYSTEM_CHANGE -> "시스템 변경";
			case BATCH_OPERATION -> "배치 작업";
			case COMMON_CODE_CHANGE -> "공통코드 변경";
			case INCIDENT_CHANGE -> "장애 변경";
			case MASTER_DATA_CHANGE -> "마스터 데이터 변경";
		};
	}

	static String outcome(AdminAuditOutcome outcome) {
		return switch (outcome) {
			case SUCCESS -> "성공";
			case FAILURE -> "실패";
		};
	}
}

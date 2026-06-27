package com.easysubway.admin.navigation;

import com.easysubway.admin.authorization.AdminAuthorization;
import com.easysubway.admin.authorization.AdminPermission;
import java.util.Arrays;
import java.util.List;
import org.springframework.security.core.Authentication;

public enum AdminProgram {
	DASHBOARD("a-dashboard", "접속·개요", "통합 대시보드", "/admin/dashboard/page", AdminPermission.ADMIN_VIEW),
	STATIONS("a-stations", "역·시설 마스터", "역 목록", "/admin/stations/page", AdminPermission.ADMIN_VIEW),
	FACILITIES("a-facilities", "역·시설 마스터", "시설 상태판", "/admin/facilities/page", AdminPermission.ADMIN_VIEW),
	LAYOUT_EDITOR("a-layout-editor", "역·시설 마스터", "역 구조·동선 편집", "/admin/facilities/editor/page", AdminPermission.MASTER_EDIT),
	REPORTS("a-reports", "제보·품질·검증", "제보 검수 큐", "/admin/reports/page", AdminPermission.REPORT_REVIEW),
	QUALITY("a-quality", "제보·품질·검증", "데이터 품질", "/admin/data-quality/page", AdminPermission.ADMIN_VIEW),
	FIELD("a-field-verifications", "제보·품질·검증", "현장 검증", "/admin/field-verifications/page", AdminPermission.FIELD_OPERATE),
	COLLECTIONS("a-collections", "운영·분석", "데이터 수집", "/admin/data-collections/page", AdminPermission.DATA_OPERATE),
	BATCHES("a-batches", "운영·분석", "배치 운영", "/admin/batches/page", AdminPermission.DATA_OPERATE),
	CODES("a-codes", "운영·분석", "공통코드", "/admin/codes/page", AdminPermission.OPERATIONS_MANAGE),
	INCIDENTS("a-incidents", "운영·분석", "장애관리", "/admin/incidents/page", AdminPermission.OPERATIONS_MANAGE),
	ROUTE_SEARCHES("a-route-searches", "운영·분석", "경로 검색 분석", "/admin/routes/searches/page", AdminPermission.ADMIN_VIEW),
	ROUTE_FEEDBACK("a-route-feedback", "운영·분석", "경로 피드백 분석", "/admin/routes/feedback/page", AdminPermission.ADMIN_VIEW),
	PUSH("a-push", "운영·분석", "푸시 알림", "/admin/notifications/push/page", AdminPermission.DATA_OPERATE),
	USAGE("a-usage", "운영·분석", "사용 현황", "/admin/usage/activity/page", AdminPermission.SECURITY_AUDIT),
	SYSTEM("a-system", "운영·분석", "시스템 상태", "/admin/system/page", AdminPermission.SECURITY_AUDIT),
	AUDITS("a-audits", "보안·감사", "관리자 감사", "/admin/audits/page", AdminPermission.AUDIT_READ),
	PRIVACY_AUDITS("a-privacy-audits", "보안·감사", "개인정보 조회 로그", "/admin/audits/privacy/page", AdminPermission.PRIVACY_LOG_READ);

	private final String id;
	private final String groupLabel;
	private final String label;
	private final String path;
	private final AdminPermission permission;

	AdminProgram(String id, String groupLabel, String label, String path, AdminPermission permission) {
		this.id = id;
		this.groupLabel = groupLabel;
		this.label = label;
		this.path = path;
		this.permission = permission;
	}

	public String id() {
		return id;
	}

	public String groupLabel() {
		return groupLabel;
	}

	public String label() {
		return label;
	}

	public String path() {
		return path;
	}

	public AdminPermission permission() {
		return permission;
	}

	public static List<AdminProgram> visibleTo(Authentication authentication) {
		return Arrays.stream(values())
			.filter(program -> AdminAuthorization.hasPermission(authentication, program.permission))
			.toList();
	}
}

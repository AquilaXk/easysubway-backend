package com.easysubway.admin.navigation;

import com.easysubway.admin.authorization.AdminAuthorization;
import com.easysubway.admin.authorization.AdminPermission;
import java.util.Arrays;
import java.util.List;
import org.springframework.security.core.Authentication;

/**
 * 관리자 콘솔의 화면(surface) 정본 레지스트리.
 *
 * <p>#2272 (Admin UX v6 / V6-00) inventory 고정: 이 enum은 29개 관리자 surface를 정의하며 그 수와 각
 * 항목의 {@code id}/{@code path}/{@code permission} 완결성은 {@code AdminNavigationAdviceTest}에서
 * source assertion으로 검증된다. operator surface(login 1 + report 5 = 6)는 별개 경계로
 * {@code AdminPhase3QualityGateTest}가 고정한다. route, {@link #visibleTo(Authentication)} permission,
 * behavior는 v6 이관 과정에서 변경하지 않는다.
 *
 * <p>Non-scope (V6-00에서 도입하지 않음, 상위 sub-issue 소관): dark mode 테마 전환, pinned/recent 메뉴,
 * {@code statusAuto} enum 전환. 이 항목들은 supersession ledger에만 기록하고 여기서 필드·상태를 추가하지 않는다.
 *
 * <p>#2277 (V6-05): 각 surface는 정확히 하나의 {@link AdminWorkspace}에 배정된다({@link #workspace()}).
 * §7 workspace→program 매핑은 이 필드가 정본이며 {@code AdminNavigationAdviceTest}가 문자 그대로 고정한다.
 * {@code groupLabel}은 통합 검색(#1981) 결과 표기·매칭에 계속 쓰이므로 workspace 도입과 별개로 유지한다.
 */
public enum AdminProgram {
	DASHBOARD("a-dashboard", "접속·개요", "통합 대시보드", "/admin/dashboard/page", AdminPermission.ADMIN_VIEW, AdminWorkspace.OVERVIEW),
	STATIONS("a-stations", "역·시설 마스터", "역 목록", "/admin/stations/page", AdminPermission.ADMIN_VIEW, AdminWorkspace.ACCESSIBILITY_DATA),
	FACILITIES("a-facilities", "역·시설 마스터", "시설 상태판", "/admin/facilities/page", AdminPermission.ADMIN_VIEW, AdminWorkspace.ACCESSIBILITY_DATA),
	LAYOUT_EDITOR("a-layout-editor", "역·시설 마스터", "역 구조·동선 편집", "/admin/facilities/editor/page", AdminPermission.MASTER_EDIT, AdminWorkspace.ACCESSIBILITY_DATA),
	REPORTS("a-reports", "제보·품질·확인", "제보 확인 대기열", "/admin/reports/page", AdminPermission.REPORT_REVIEW, AdminWorkspace.ACCESSIBILITY_DATA),
	QUALITY("a-quality", "제보·품질·확인", "데이터 품질", "/admin/data-quality/page", AdminPermission.ADMIN_VIEW, AdminWorkspace.ACCESSIBILITY_DATA),
	FIELD("a-field-verifications", "제보·품질·확인", "현장 확인", "/admin/field-verifications/page", AdminPermission.FIELD_OPERATE, AdminWorkspace.ACCESSIBILITY_DATA),
	COLLECTIONS("a-collections", "운영·분석", "데이터 수집", "/admin/data-collections/page", AdminPermission.DATA_OPERATE, AdminWorkspace.OPERATIONS),
	BATCHES("a-batches", "운영·분석", "배치 운영", "/admin/batches/page", AdminPermission.DATA_OPERATE, AdminWorkspace.OPERATIONS),
	CODES("a-codes", "운영·분석", "공통코드", "/admin/codes/page", AdminPermission.OPERATIONS_MANAGE, AdminWorkspace.SYSTEM_AUDIT),
	INCIDENTS("a-incidents", "운영·분석", "장애관리", "/admin/incidents/page", AdminPermission.OPERATIONS_MANAGE, AdminWorkspace.OPERATIONS),
	SERVICE_NOTICES("a-service-notices", "운영·분석", "운행 공지", "/admin/notices/page", AdminPermission.OPERATIONS_MANAGE, AdminWorkspace.COMMUNICATIONS),
	ADS("a-ads", "운영·분석", "광고 소재", "/admin/ads/page", AdminPermission.OPERATIONS_MANAGE, AdminWorkspace.COMMUNICATIONS),
	ROUTE_SEARCHES("a-route-searches", "운영·분석", "경로 검색 분석", "/admin/routes/searches/page", AdminPermission.ADMIN_VIEW, AdminWorkspace.ANALYTICS),
	ROUTE_FEEDBACK("a-route-feedback", "운영·분석", "경로 피드백 분석", "/admin/routes/feedback/page", AdminPermission.ADMIN_VIEW, AdminWorkspace.ANALYTICS),
	DATAPACK_PIPELINE("a-datapack-pipeline", "데이터팩", "파이프라인 개요", "/admin/datapack/pipeline/page", AdminPermission.DATAPACK_READ, AdminWorkspace.DATAPACK),
	DATAPACK_SOURCE_SNAPSHOTS("a-datapack-source-snapshots", "데이터팩", "원천 스냅샷", "/admin/datapack/source-snapshots/page", AdminPermission.DATAPACK_READ, AdminWorkspace.DATAPACK),
	DATAPACK_ALIAS_QUARANTINE("a-datapack-alias-quarantine", "데이터팩", "별칭·격리 검토", "/admin/datapack/alias-quarantine/page", AdminPermission.DATAPACK_READ, AdminWorkspace.DATAPACK),
	DATAPACK_FACILITY_EVIDENCE("a-datapack-facility-evidence", "데이터팩", "시설 근거 검토", "/admin/datapack/facility-evidence/page", AdminPermission.DATAPACK_READ, AdminWorkspace.DATAPACK),
	DATAPACK_ROUTE_GATES("a-datapack-route-gates", "데이터팩", "경로 게이트", "/admin/datapack/route-gates/page", AdminPermission.DATAPACK_READ, AdminWorkspace.DATAPACK),
	DATAPACK_MANUAL_OVERRIDES("a-datapack-manual-overrides", "데이터팩", "수동 오버라이드", "/admin/datapack/manual-overrides/page", AdminPermission.DATAPACK_READ, AdminWorkspace.DATAPACK),
	DATAPACK_CANDIDATES("a-datapack-candidates", "데이터팩", "후보 팩", "/admin/datapack/candidates/page", AdminPermission.DATAPACK_READ, AdminWorkspace.DATAPACK),
	DATAPACK_RELEASE_CHANNELS("a-datapack-release-channels", "데이터팩", "배포 채널", "/admin/datapack/release-channels/page", AdminPermission.DATAPACK_READ, AdminWorkspace.DATAPACK),
	DATAPACK_RELEASE_REQUESTS("a-datapack-release-requests", "데이터팩", "릴리스 요청", "/admin/datapack/release-requests/page", AdminPermission.DATAPACK_READ, AdminWorkspace.DATAPACK),
	PUSH("a-push", "운영·분석", "푸시 알림", "/admin/notifications/push/page", AdminPermission.DATA_OPERATE, AdminWorkspace.COMMUNICATIONS),
	USAGE("a-usage", "운영·분석", "사용 현황", "/admin/usage/activity/page", AdminPermission.SECURITY_AUDIT, AdminWorkspace.ANALYTICS),
	SYSTEM("a-system", "운영·분석", "시스템 상태", "/admin/system/page", AdminPermission.SECURITY_AUDIT, AdminWorkspace.SYSTEM_AUDIT),
	AUDITS("a-audits", "보안·감사", "관리자 감사", "/admin/audits/page", AdminPermission.AUDIT_READ, AdminWorkspace.SYSTEM_AUDIT),
	PRIVACY_AUDITS("a-privacy-audits", "보안·감사", "개인정보 조회 로그", "/admin/audits/privacy/page", AdminPermission.PRIVACY_LOG_READ, AdminWorkspace.SYSTEM_AUDIT);

	private final String id;
	private final String groupLabel;
	private final String label;
	private final String path;
	private final AdminPermission permission;
	private final AdminWorkspace workspace;

	AdminProgram(String id, String groupLabel, String label, String path, AdminPermission permission, AdminWorkspace workspace) {
		this.id = id;
		this.groupLabel = groupLabel;
		this.label = label;
		this.path = path;
		this.permission = permission;
		this.workspace = workspace;
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

	public AdminWorkspace workspace() {
		return workspace;
	}

	public static List<AdminProgram> visibleTo(Authentication authentication) {
		return Arrays.stream(values())
			.filter(program -> AdminAuthorization.hasPermission(authentication, program.permission))
			.toList();
	}
}

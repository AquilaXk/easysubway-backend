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
 */
public enum AdminProgram {
	DASHBOARD("a-dashboard", "접속·개요", "통합 대시보드", "/admin/dashboard/page", AdminPermission.ADMIN_VIEW),
	STATIONS("a-stations", "역·시설 마스터", "역 목록", "/admin/stations/page", AdminPermission.ADMIN_VIEW),
	FACILITIES("a-facilities", "역·시설 마스터", "시설 상태판", "/admin/facilities/page", AdminPermission.ADMIN_VIEW),
	LAYOUT_EDITOR("a-layout-editor", "역·시설 마스터", "역 구조·동선 편집", "/admin/facilities/editor/page", AdminPermission.MASTER_EDIT),
	REPORTS("a-reports", "제보·품질·확인", "제보 확인 대기열", "/admin/reports/page", AdminPermission.REPORT_REVIEW),
	QUALITY("a-quality", "제보·품질·확인", "데이터 품질", "/admin/data-quality/page", AdminPermission.ADMIN_VIEW),
	FIELD("a-field-verifications", "제보·품질·확인", "현장 확인", "/admin/field-verifications/page", AdminPermission.FIELD_OPERATE),
	COLLECTIONS("a-collections", "운영·분석", "데이터 수집", "/admin/data-collections/page", AdminPermission.DATA_OPERATE),
	BATCHES("a-batches", "운영·분석", "배치 운영", "/admin/batches/page", AdminPermission.DATA_OPERATE),
	CODES("a-codes", "운영·분석", "공통코드", "/admin/codes/page", AdminPermission.OPERATIONS_MANAGE),
	INCIDENTS("a-incidents", "운영·분석", "장애관리", "/admin/incidents/page", AdminPermission.OPERATIONS_MANAGE),
	SERVICE_NOTICES("a-service-notices", "운영·분석", "운행 공지", "/admin/notices/page", AdminPermission.OPERATIONS_MANAGE),
	ADS("a-ads", "운영·분석", "광고 소재", "/admin/ads/page", AdminPermission.OPERATIONS_MANAGE),
	ROUTE_SEARCHES("a-route-searches", "운영·분석", "경로 검색 분석", "/admin/routes/searches/page", AdminPermission.ADMIN_VIEW),
	ROUTE_FEEDBACK("a-route-feedback", "운영·분석", "경로 피드백 분석", "/admin/routes/feedback/page", AdminPermission.ADMIN_VIEW),
	DATAPACK_PIPELINE("a-datapack-pipeline", "데이터팩", "파이프라인 개요", "/admin/datapack/pipeline/page", AdminPermission.DATAPACK_READ),
	DATAPACK_SOURCE_SNAPSHOTS("a-datapack-source-snapshots", "데이터팩", "원천 스냅샷", "/admin/datapack/source-snapshots/page", AdminPermission.DATAPACK_READ),
	DATAPACK_ALIAS_QUARANTINE("a-datapack-alias-quarantine", "데이터팩", "별칭·격리 검토", "/admin/datapack/alias-quarantine/page", AdminPermission.DATAPACK_READ),
	DATAPACK_FACILITY_EVIDENCE("a-datapack-facility-evidence", "데이터팩", "시설 근거 검토", "/admin/datapack/facility-evidence/page", AdminPermission.DATAPACK_READ),
	DATAPACK_ROUTE_GATES("a-datapack-route-gates", "데이터팩", "경로 게이트", "/admin/datapack/route-gates/page", AdminPermission.DATAPACK_READ),
	DATAPACK_MANUAL_OVERRIDES("a-datapack-manual-overrides", "데이터팩", "수동 오버라이드", "/admin/datapack/manual-overrides/page", AdminPermission.DATAPACK_READ),
	DATAPACK_CANDIDATES("a-datapack-candidates", "데이터팩", "후보 팩", "/admin/datapack/candidates/page", AdminPermission.DATAPACK_READ),
	DATAPACK_RELEASE_CHANNELS("a-datapack-release-channels", "데이터팩", "배포 채널", "/admin/datapack/release-channels/page", AdminPermission.DATAPACK_READ),
	DATAPACK_RELEASE_REQUESTS("a-datapack-release-requests", "데이터팩", "릴리스 요청", "/admin/datapack/release-requests/page", AdminPermission.DATAPACK_READ),
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

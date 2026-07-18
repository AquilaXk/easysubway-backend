package com.easysubway.admin.navigation;

/**
 * 관리자 콘솔 shell의 업무 영역(workspace) 정본.
 *
 * <p>#2277 (Admin UX v6 / V6-05): 29개 {@link AdminProgram}을 7개 업무 영역으로 묶어 shell IA를
 * 구성한다. 각 program은 정확히 하나의 workspace에 배정되며(중복·누락 불가), 그 매핑은
 * {@link AdminProgram#workspace()}가 소유하고 {@code AdminNavigationAdviceTest}가 source assertion으로
 * §7 계약과 문자 그대로 일치하는지 고정한다.
 *
 * <p>이 enum은 {@code id}/{@code displayName}만 갖고 {@link AdminProgram}을 참조하지 않는다. 참조 방향을
 * program → workspace 한쪽으로만 두어 enum 정적 초기화 순환을 피한다. permission 필터 뒤 프로그램이 0개인
 * workspace를 렌더에서 제외하는 것은 advice/템플릿 책임이며 route·permission·{@link AdminProgram#visibleTo}는
 * 변경하지 않는다.
 */
public enum AdminWorkspace {
	OVERVIEW("overview", "개요"),
	ACCESSIBILITY_DATA("accessibility-data", "역·접근성 데이터"),
	OPERATIONS("operations", "운영"),
	COMMUNICATIONS("communications", "커뮤니케이션"),
	ANALYTICS("analytics", "분석"),
	DATAPACK("datapack", "데이터팩"),
	SYSTEM_AUDIT("system-audit", "시스템·감사");

	private final String id;
	private final String displayName;

	AdminWorkspace(String id, String displayName) {
		this.id = id;
		this.displayName = displayName;
	}

	public String id() {
		return id;
	}

	public String displayName() {
		return displayName;
	}
}

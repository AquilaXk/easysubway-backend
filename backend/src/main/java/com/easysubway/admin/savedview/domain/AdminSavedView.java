package com.easysubway.admin.savedview.domain;

import java.time.LocalDateTime;

/**
 * 관리자 표준 테이블(#1737)의 계정별 저장된 뷰.
 *
 * <p>화면(programId)별로 이름 있는 질의 파라미터 스냅샷을 저장한다. 소유자는 관리자 로그인 ID다.
 * 화면당 기본 뷰는 한 개만 허용되며(유일성은 서비스가 보장), 목록 진입 시 자동 적용 대상이다.
 */
public record AdminSavedView(
	String viewId,
	String adminLoginId,
	String programId,
	String name,
	String queryParams,
	boolean isDefault,
	LocalDateTime createdAt,
	LocalDateTime updatedAt
) {

	public static final int MAX_NAME_LENGTH = 120;
	public static final int MAX_QUERY_PARAMS_LENGTH = 2000;

	public AdminSavedView {
		requireText(viewId, "저장된 뷰 ID");
		requireText(adminLoginId, "관리자 로그인 ID");
		requireText(programId, "화면 ID");
		requireText(name, "저장된 뷰 이름");
		if (name.length() > MAX_NAME_LENGTH) {
			throw new InvalidAdminSavedViewException("저장된 뷰 이름은 %d자 이하여야 합니다.".formatted(MAX_NAME_LENGTH));
		}
		queryParams = queryParams == null ? "" : queryParams;
		if (queryParams.length() > MAX_QUERY_PARAMS_LENGTH) {
			throw new InvalidAdminSavedViewException(
				"저장된 뷰 질의는 %d자 이하여야 합니다.".formatted(MAX_QUERY_PARAMS_LENGTH));
		}
	}

	public AdminSavedView withQueryParams(String newQueryParams, LocalDateTime now) {
		return new AdminSavedView(viewId, adminLoginId, programId, name, newQueryParams, isDefault, createdAt, now);
	}

	public AdminSavedView withDefault(boolean value, LocalDateTime now) {
		return new AdminSavedView(viewId, adminLoginId, programId, name, queryParams, value, createdAt, now);
	}

	public boolean ownedBy(String loginId) {
		return adminLoginId.equals(loginId);
	}

	private static void requireText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new InvalidAdminSavedViewException("%s는 비어 있을 수 없습니다.".formatted(field));
		}
	}
}

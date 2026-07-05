package com.easysubway.admin.savedview.domain;

public class AdminSavedViewNotFoundException extends RuntimeException {

	public AdminSavedViewNotFoundException(String viewId) {
		super("저장된 뷰를 찾을 수 없습니다: %s".formatted(viewId));
	}
}

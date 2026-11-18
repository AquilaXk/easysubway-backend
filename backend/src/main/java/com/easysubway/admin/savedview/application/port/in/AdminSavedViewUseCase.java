package com.easysubway.admin.savedview.application.port.in;

import com.easysubway.admin.savedview.domain.AdminSavedView;
import java.util.List;
import java.util.Optional;

public interface AdminSavedViewUseCase {

	/** 이름 기준 upsert: 같은 (소유자·화면·이름)이 있으면 질의를 갱신, 없으면 생성. */
	AdminSavedView saveView(SaveAdminSavedViewCommand command);

	List<AdminSavedView> listViews(String adminLoginId, String programId);

	/** 화면의 기본 뷰(있으면). 목록 진입 시 자동 적용에 쓴다. */
	Optional<AdminSavedView> findDefaultView(String adminLoginId, String programId);

	/** 소유자 확인 후 해당 뷰를 화면의 기본 뷰로 지정(기존 기본은 해제). */
	AdminSavedView setDefaultView(String adminLoginId, String viewId);

	/** 소유자 확인 후 삭제. */
	AdminSavedView deleteView(String adminLoginId, String viewId);

	/** 저장된 뷰가 없는 읽기 전용 구현(경량 컨트롤러 단위 테스트용). 변경은 지원하지 않는다. */
	static AdminSavedViewUseCase readOnlyEmpty() {
		return new AdminSavedViewUseCase() {
			@Override
			public AdminSavedView saveView(SaveAdminSavedViewCommand command) {
				throw new UnsupportedOperationException("read-only saved view use case");
			}

			@Override
			public List<AdminSavedView> listViews(String adminLoginId, String programId) {
				return List.of();
			}

			@Override
			public Optional<AdminSavedView> findDefaultView(String adminLoginId, String programId) {
				return Optional.empty();
			}

			@Override
			public AdminSavedView setDefaultView(String adminLoginId, String viewId) {
				throw new UnsupportedOperationException("read-only saved view use case");
			}

			@Override
			public AdminSavedView deleteView(String adminLoginId, String viewId) {
				throw new UnsupportedOperationException("read-only saved view use case");
			}
		};
	}
}

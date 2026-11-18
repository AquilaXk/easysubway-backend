package com.easysubway.admin.savedview.application.port.out;

import com.easysubway.admin.savedview.domain.AdminSavedView;
import java.util.List;
import java.util.Optional;

public interface AdminSavedViewRepository {

	List<AdminSavedView> findByOwnerAndProgram(String adminLoginId, String programId);

	Optional<AdminSavedView> findByOwnerProgramAndName(String adminLoginId, String programId, String name);

	Optional<AdminSavedView> findById(String viewId);

	void save(AdminSavedView view);

	void deleteById(String viewId);

	/** 소유자·화면의 모든 뷰 기본 지정을 해제한다(기본 뷰 유일성 보장용). */
	void clearDefault(String adminLoginId, String programId);
}

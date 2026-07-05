package com.easysubway.admin.savedview.adapter.out.persistence;

import com.easysubway.admin.savedview.application.port.out.AdminSavedViewRepository;
import com.easysubway.admin.savedview.domain.AdminSavedView;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!prod & !staging & !release & !prod-like")
public class InMemoryAdminSavedViewRepository implements AdminSavedViewRepository {

	private final Map<String, AdminSavedView> views = new ConcurrentHashMap<>();

	@Override
	public List<AdminSavedView> findByOwnerAndProgram(String adminLoginId, String programId) {
		return views.values()
			.stream()
			.filter(view -> view.adminLoginId().equals(adminLoginId) && view.programId().equals(programId))
			.sorted(Comparator.comparing(AdminSavedView::isDefault).reversed()
				.thenComparing(AdminSavedView::name))
			.toList();
	}

	@Override
	public Optional<AdminSavedView> findByOwnerProgramAndName(String adminLoginId, String programId, String name) {
		return views.values()
			.stream()
			.filter(view -> view.adminLoginId().equals(adminLoginId)
				&& view.programId().equals(programId)
				&& view.name().equals(name))
			.findFirst();
	}

	@Override
	public Optional<AdminSavedView> findById(String viewId) {
		return Optional.ofNullable(views.get(viewId));
	}

	@Override
	public void save(AdminSavedView view) {
		views.put(view.viewId(), view);
	}

	@Override
	public void deleteById(String viewId) {
		views.remove(viewId);
	}

	@Override
	public void clearDefault(String adminLoginId, String programId) {
		views.replaceAll((id, view) -> {
			if (view.adminLoginId().equals(adminLoginId)
				&& view.programId().equals(programId)
				&& view.isDefault()) {
				// SQL UPDATE와 동일하게 기본 플래그만 끄고 타임스탬프는 보존한다.
				return new AdminSavedView(
					view.viewId(),
					view.adminLoginId(),
					view.programId(),
					view.name(),
					view.queryParams(),
					false,
					view.createdAt(),
					view.updatedAt()
				);
			}
			return view;
		});
	}
}

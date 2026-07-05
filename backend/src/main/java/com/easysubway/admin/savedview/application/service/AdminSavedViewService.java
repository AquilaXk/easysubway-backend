package com.easysubway.admin.savedview.application.service;

import com.easysubway.admin.savedview.application.port.in.AdminSavedViewUseCase;
import com.easysubway.admin.savedview.application.port.in.SaveAdminSavedViewCommand;
import com.easysubway.admin.savedview.application.port.out.AdminSavedViewRepository;
import com.easysubway.admin.savedview.domain.AdminSavedView;
import com.easysubway.admin.savedview.domain.AdminSavedViewNotFoundException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminSavedViewService implements AdminSavedViewUseCase {

	private final AdminSavedViewRepository repository;
	private final Clock clock;
	private final Supplier<String> viewIdGenerator;

	@Autowired
	public AdminSavedViewService(AdminSavedViewRepository repository, ObjectProvider<Clock> clockProvider) {
		this(
			repository,
			clockProvider.getIfAvailable(Clock::systemUTC),
			() -> UUID.randomUUID().toString()
		);
	}

	AdminSavedViewService(AdminSavedViewRepository repository, Clock clock, Supplier<String> viewIdGenerator) {
		this.repository = repository;
		this.clock = clock;
		this.viewIdGenerator = viewIdGenerator;
	}

	@Override
	@Transactional
	public AdminSavedView saveView(SaveAdminSavedViewCommand command) {
		LocalDateTime now = LocalDateTime.now(clock);
		AdminSavedView view = repository
			.findByOwnerProgramAndName(command.adminLoginId(), command.programId(), command.name())
			.map(existing -> existing.withQueryParams(command.queryParams(), now))
			.orElseGet(() -> new AdminSavedView(
				viewIdGenerator.get(),
				command.adminLoginId(),
				command.programId(),
				command.name(),
				command.queryParams(),
				false,
				now,
				now
			));
		if (command.makeDefault()) {
			repository.clearDefault(command.adminLoginId(), command.programId());
			view = view.withDefault(true, now);
		}
		repository.save(view);
		return view;
	}

	@Override
	public List<AdminSavedView> listViews(String adminLoginId, String programId) {
		return repository.findByOwnerAndProgram(adminLoginId, programId);
	}

	@Override
	public java.util.Optional<AdminSavedView> findDefaultView(String adminLoginId, String programId) {
		return repository.findByOwnerAndProgram(adminLoginId, programId).stream()
			.filter(AdminSavedView::isDefault)
			.findFirst();
	}

	@Override
	@Transactional
	public AdminSavedView setDefaultView(String adminLoginId, String viewId) {
		AdminSavedView view = requireOwnedView(adminLoginId, viewId);
		LocalDateTime now = LocalDateTime.now(clock);
		repository.clearDefault(adminLoginId, view.programId());
		AdminSavedView updated = view.withDefault(true, now);
		repository.save(updated);
		return updated;
	}

	@Override
	@Transactional
	public AdminSavedView deleteView(String adminLoginId, String viewId) {
		AdminSavedView view = requireOwnedView(adminLoginId, viewId);
		repository.deleteById(viewId);
		return view;
	}

	private AdminSavedView requireOwnedView(String adminLoginId, String viewId) {
		AdminSavedView view = repository.findById(viewId)
			.orElseThrow(() -> new AdminSavedViewNotFoundException(viewId));
		if (!view.ownedBy(adminLoginId)) {
			// 소유자가 아니면 다른 계정 뷰의 존재를 드러내지 않도록 not found로 처리한다.
			throw new AdminSavedViewNotFoundException(viewId);
		}
		return view;
	}
}

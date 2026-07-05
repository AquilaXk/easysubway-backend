package com.easysubway.admin.savedview.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.admin.savedview.adapter.out.persistence.InMemoryAdminSavedViewRepository;
import com.easysubway.admin.savedview.application.port.in.SaveAdminSavedViewCommand;
import com.easysubway.admin.savedview.domain.AdminSavedView;
import com.easysubway.admin.savedview.domain.AdminSavedViewNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("관리자 저장된 뷰 서비스")
class AdminSavedViewServiceTest {

	private AdminSavedViewService service;

	@BeforeEach
	void setUp() {
		AtomicInteger sequence = new AtomicInteger();
		service = new AdminSavedViewService(
			new InMemoryAdminSavedViewRepository(),
			Clock.fixed(Instant.parse("2026-07-05T00:00:00Z"), ZoneOffset.UTC),
			() -> "view-" + sequence.incrementAndGet()
		);
	}

	@Test
	@DisplayName("같은 이름으로 다시 저장하면 새 뷰를 만들지 않고 질의만 갱신한다")
	void saveViewUpsertsByName() {
		service.saveView(new SaveAdminSavedViewCommand("admin-1", "a-reports", "미확인 급증", "status=SUBMITTED", false));
		service.saveView(new SaveAdminSavedViewCommand("admin-1", "a-reports", "미확인 급증", "status=SUBMITTED&sort=created_at,asc", false));

		var views = service.listViews("admin-1", "a-reports");
		assertThat(views).hasSize(1);
		assertThat(views.get(0).viewId()).isEqualTo("view-1");
		assertThat(views.get(0).queryParams()).isEqualTo("status=SUBMITTED&sort=created_at,asc");
	}

	@Test
	@DisplayName("화면당 기본 뷰는 한 개만 유지된다")
	void onlyOneDefaultViewPerProgram() {
		service.saveView(new SaveAdminSavedViewCommand("admin-1", "a-reports", "첫 뷰", "status=SUBMITTED", true));
		service.saveView(new SaveAdminSavedViewCommand("admin-1", "a-reports", "둘째 뷰", "status=RESOLVED", true));

		var defaults = service.listViews("admin-1", "a-reports").stream().filter(AdminSavedView::isDefault).toList();
		assertThat(defaults).extracting(AdminSavedView::name).containsExactly("둘째 뷰");
	}

	@Test
	@DisplayName("기본 지정은 기존 기본을 해제하고 새 뷰만 기본으로 만든다")
	void setDefaultViewSwitchesDefault() {
		var first = service.saveView(new SaveAdminSavedViewCommand("admin-1", "a-reports", "첫 뷰", "status=SUBMITTED", true));
		var second = service.saveView(new SaveAdminSavedViewCommand("admin-1", "a-reports", "둘째 뷰", "status=RESOLVED", false));

		service.setDefaultView("admin-1", second.viewId());

		var views = service.listViews("admin-1", "a-reports");
		assertThat(views).filteredOn(AdminSavedView::isDefault).extracting(AdminSavedView::viewId)
			.containsExactly(second.viewId());
		assertThat(views).filteredOn(view -> view.viewId().equals(first.viewId()))
			.singleElement().extracting(AdminSavedView::isDefault).isEqualTo(false);
	}

	@Test
	@DisplayName("다른 계정의 뷰는 기본 지정·삭제할 수 없다(존재를 드러내지 않는다)")
	void cannotTouchAnotherOwnersView() {
		var view = service.saveView(new SaveAdminSavedViewCommand("admin-1", "a-reports", "내 뷰", "status=SUBMITTED", false));

		assertThatThrownBy(() -> service.setDefaultView("admin-2", view.viewId()))
			.isInstanceOf(AdminSavedViewNotFoundException.class);
		assertThatThrownBy(() -> service.deleteView("admin-2", view.viewId()))
			.isInstanceOf(AdminSavedViewNotFoundException.class);
		assertThat(service.listViews("admin-1", "a-reports")).hasSize(1);
	}

	@Test
	@DisplayName("소유자는 저장된 뷰를 삭제한다")
	void ownerDeletesView() {
		var view = service.saveView(new SaveAdminSavedViewCommand("admin-1", "a-reports", "삭제할 뷰", "status=SUBMITTED", false));

		service.deleteView("admin-1", view.viewId());

		assertThat(service.listViews("admin-1", "a-reports")).isEmpty();
	}
}

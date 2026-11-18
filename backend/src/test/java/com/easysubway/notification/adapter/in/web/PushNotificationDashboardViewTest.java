package com.easysubway.notification.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.notification.domain.PushDeliveryAvailability;
import com.easysubway.notification.domain.PushNotificationDashboardSummary;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("푸시 대시보드 상태 표시")
class PushNotificationDashboardViewTest {

	private static final PushNotificationDashboardSummary SUMMARY =
		new PushNotificationDashboardSummary(6, 3, 2, 1, "실패 사유");

	@Test
	@DisplayName("발송 가능하면 PENDING 행은 대기 중이다")
	void pendingRowIsWaitingWhenAvailable() {
		PushNotificationDashboardView view =
			PushNotificationDashboardView.from(SUMMARY, new PushDeliveryAvailability(true, List.of()));

		assertThat(view.statusRows().get(0).label()).isEqualTo("대기 중");
		assertThat(view.statusRows().get(0).description()).isEqualTo("아직 발송 처리 전");
		assertThat(view.statusRows().get(0).count()).isEqualTo(3);
	}

	@Test
	@DisplayName("가용성 판정이 없으면 기존 표시를 유지한다")
	void pendingRowIsWaitingWithoutAvailability() {
		assertThat(PushNotificationDashboardView.from(SUMMARY).statusRows().get(0).label()).isEqualTo("대기 중");
	}

	@Test
	@DisplayName("발송 불가면 PENDING 행만 발송 불가와 사유로 표시하고 완료·실패 행은 그대로다")
	void onlyPendingRowChangesWhenUnavailable() {
		PushNotificationDashboardView view = PushNotificationDashboardView.from(
			SUMMARY, new PushDeliveryAvailability(false, List.of("사유 A", "사유 B")));

		assertThat(view.statusRows().get(0).label()).isEqualTo("발송 불가");
		assertThat(view.statusRows().get(0).description()).contains("처리할 수 없어 쌓여 있습니다").contains("사유 A 사유 B");
		assertThat(view.statusRows().get(0).count()).isEqualTo(3);
		assertThat(view.statusRows().get(1).label()).isEqualTo("발송 완료");
		assertThat(view.statusRows().get(2).label()).isEqualTo("발송 실패");
		assertThat(view.pendingCount()).isEqualTo(3);
	}
}

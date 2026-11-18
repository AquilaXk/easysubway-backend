package com.easysubway.notification.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.notification.adapter.out.persistence.InMemoryNotificationPreferenceRepository;
import com.easysubway.notification.adapter.out.persistence.InMemoryPushNotificationOutboxRepository;
import com.easysubway.notification.application.port.in.RegisterDeviceCommand;
import com.easysubway.notification.application.port.in.ReportStatusChangedAlertCommand;
import com.easysubway.notification.application.port.in.SaveNotificationSettingsCommand;
import com.easysubway.notification.domain.DevicePlatform;
import com.easysubway.notification.domain.InvalidPushNotificationException;
import com.easysubway.notification.domain.PushNotificationType;
import com.easysubway.report.domain.FacilityReportStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("신고 처리 결과 알림 서비스")
class ReportStatusAlertServiceTest {

	private static final Clock CLOCK = Clock.fixed(
		Instant.parse("2026-06-14T05:00:00Z"),
		ZoneId.of("Asia/Seoul")
	);

	private final InMemoryNotificationPreferenceRepository notificationPreferenceRepository =
		new InMemoryNotificationPreferenceRepository();
	private final InMemoryPushNotificationOutboxRepository outboxRepository =
		new InMemoryPushNotificationOutboxRepository();
	private final NotificationPreferenceService preferenceService = new NotificationPreferenceService(
		notificationPreferenceRepository,
		notificationPreferenceRepository,
		notificationPreferenceRepository,
		CLOCK
	);
	private final PushNotificationDispatchService dispatchService = new PushNotificationDispatchService(
		notificationPreferenceRepository,
		outboxRepository,
		CLOCK
	);
	private final ReportStatusAlertService service = new ReportStatusAlertService(dispatchService);

	@Test
	@DisplayName("승인된 신고는 작성자에게 확인 완료 알림 후보를 만든다")
	void acceptedReportCreatesReportStatusPushCandidate() {
		registerDevice("anonymous-user-accepted", DevicePlatform.ANDROID, "accepted-token");

		service.alertReportStatusChanged(new ReportStatusChangedAlertCommand(
			"anonymous-user-accepted",
			"report-accepted",
			FacilityReportStatus.ACCEPTED
		));

		assertThat(outboxRepository.loadPushNotifications("anonymous-user-accepted"))
			.extracting("type")
			.containsExactly(PushNotificationType.REPORT_STATUS);
		assertThat(outboxRepository.loadPushNotifications("anonymous-user-accepted"))
			.extracting("body")
			.containsExactly("제보해 주신 신고가 확인되어 시설 정보에 반영되었습니다.");
	}

	@Test
	@DisplayName("반려와 중복 처리된 신고는 작성자에게 결과 알림 후보를 만든다")
	void rejectedAndDuplicateReportsCreateReportStatusPushCandidate() {
		registerDevice("anonymous-user-rejected", DevicePlatform.IOS, "rejected-token");
		registerDevice("anonymous-user-duplicate", DevicePlatform.ANDROID, "duplicate-token");

		service.alertReportStatusChanged(new ReportStatusChangedAlertCommand(
			"anonymous-user-rejected",
			"report-rejected",
			FacilityReportStatus.REJECTED
		));
		service.alertReportStatusChanged(new ReportStatusChangedAlertCommand(
			"anonymous-user-duplicate",
			"report-duplicate",
			FacilityReportStatus.DUPLICATE
		));

		assertThat(outboxRepository.loadPushNotifications("anonymous-user-rejected"))
			.extracting("body")
			.containsExactly("제보해 주신 신고를 검토했지만 이번에는 반영되지 않았습니다.");
		assertThat(outboxRepository.loadPushNotifications("anonymous-user-duplicate"))
			.extracting("body")
			.containsExactly("제보해 주신 신고는 이미 접수된 내용과 같아 중복으로 정리되었습니다.");
	}

	@Test
	@DisplayName("사용자가 꺼둔 신고 처리 알림은 outbox 후보를 만들지 않는다")
	void disabledReportStatusAlertDoesNotCreateOutboxCandidate() {
		registerDevice("anonymous-user-disabled", DevicePlatform.ANDROID, "disabled-token");
		preferenceService.saveNotificationSettings(new SaveNotificationSettingsCommand(
			"anonymous-user-disabled",
			true,
			true,
			false,
			false
		));

		service.alertReportStatusChanged(new ReportStatusChangedAlertCommand(
			"anonymous-user-disabled",
			"report-disabled",
			FacilityReportStatus.ACCEPTED
		));

		assertThat(outboxRepository.loadPushNotifications("anonymous-user-disabled")).isEmpty();
	}

	@Test
	@DisplayName("신고 처리 알림 명령은 사용자와 신고와 상태를 요구한다")
	void reportStatusAlertCommandRequiresUserReportAndStatus() {
		assertThatThrownBy(() -> new ReportStatusChangedAlertCommand(
			"",
			"report-1",
			FacilityReportStatus.ACCEPTED
		))
			.isInstanceOf(InvalidPushNotificationException.class)
			.hasMessage("사용자 식별자가 필요합니다.");
		assertThatThrownBy(() -> new ReportStatusChangedAlertCommand(
			"anonymous-user-1",
			"",
			FacilityReportStatus.ACCEPTED
		))
			.isInstanceOf(InvalidPushNotificationException.class)
			.hasMessage("신고 식별자가 필요합니다.");
		assertThatThrownBy(() -> new ReportStatusChangedAlertCommand(
			"anonymous-user-1",
			"report-1",
			null
		))
			.isInstanceOf(InvalidPushNotificationException.class)
			.hasMessage("신고 상태를 선택해야 합니다.");
	}

	private void registerDevice(String userId, DevicePlatform platform, String token) {
		preferenceService.registerDevice(new RegisterDeviceCommand(userId, platform, token));
	}
}

package com.easysubway.notification.adapter.in.web;

import com.easysubway.notification.domain.PushDeliveryAvailability;
import com.easysubway.notification.domain.PushNotificationDashboardSummary;
import java.util.List;
import java.util.Locale;

record PushNotificationDashboardView(
	long totalCount,
	long pendingCount,
	long sentCount,
	long failedCount,
	long deliveryAttemptCount,
	String successRateLabel,
	String failureRateLabel,
	String failureAlertLabel,
	String failureAlertDescription,
	String failureAlertClass,
	String latestFailureReason,
	List<StatusCountRow> statusRows
) {

	static PushNotificationDashboardView from(PushNotificationDashboardSummary summary) {
		return from(summary, null);
	}

	// availability가 발송 불가면 PENDING 건을 일반 대기가 아니라 "발송 불가"와 사유로 표시한다.
	// null이면(API 등 가용성 판정을 쓰지 않는 호출) 기존 표시를 그대로 유지한다.
	static PushNotificationDashboardView from(
		PushNotificationDashboardSummary summary,
		PushDeliveryAvailability availability
	) {
		long deliveryAttemptCount = summary.sentCount() + summary.failedCount();
		return new PushNotificationDashboardView(
			summary.totalCount(),
			summary.pendingCount(),
			summary.sentCount(),
			summary.failedCount(),
			deliveryAttemptCount,
			percentageLabel(summary.sentCount(), deliveryAttemptCount),
			percentageLabel(summary.failedCount(), deliveryAttemptCount),
			failureAlertLabel(summary.failedCount(), deliveryAttemptCount),
			failureAlertDescription(summary.failedCount(), deliveryAttemptCount),
			failureAlertClass(summary.failedCount(), deliveryAttemptCount),
			summary.latestFailureReason(),
			List.of(
				pendingRow(summary.pendingCount(), availability),
				new StatusCountRow("발송 완료", "외부 발송 성공", summary.sentCount()),
				new StatusCountRow("발송 실패", failedDescription(summary.latestFailureReason()), summary.failedCount())
			)
		);
	}

	private static StatusCountRow pendingRow(long pendingCount, PushDeliveryAvailability availability) {
		if (availability == null || availability.available()) {
			return new StatusCountRow("대기 중", "아직 발송 처리 전", pendingCount);
		}
		return new StatusCountRow(
			PushNotificationHistoryRow.PENDING_UNAVAILABLE_LABEL,
			"처리할 수 없어 쌓여 있습니다 · " + String.join(" ", availability.reasons()),
			pendingCount
		);
	}

	private static String percentageLabel(long numerator, long denominator) {
		if (denominator == 0) {
			return "0.0%";
		}
		return String.format(Locale.ROOT, "%.1f%%", numerator * 100.0 / denominator);
	}

	private static String failureAlertLabel(long failedCount, long deliveryAttemptCount) {
		if (deliveryAttemptCount == 0) {
			return "발송 대기";
		}
		return failedCount == 0 ? "정상" : "점검 필요";
	}

	private static String failureAlertDescription(long failedCount, long deliveryAttemptCount) {
		if (deliveryAttemptCount == 0) {
			return "아직 발송 시도 기록이 없습니다.";
		}
		if (failedCount == 0) {
			return "발송 실패 없이 처리되고 있습니다.";
		}
		return "발송 실패가 있어 푸시 어댑터와 provider 상태를 확인하세요.";
	}

	private static String failureAlertClass(long failedCount, long deliveryAttemptCount) {
		if (deliveryAttemptCount == 0) {
			return "pending";
		}
		return failedCount == 0 ? "ok" : "failure";
	}

	private static String failedDescription(String latestFailureReason) {
		if (latestFailureReason == null || latestFailureReason.isBlank()) {
			return "발송 어댑터 실패 또는 예외";
		}
		return "발송 어댑터 실패 또는 예외 · 최근 실패: " + latestFailureReason;
	}

	record StatusCountRow(String label, String description, long count) {
	}
}

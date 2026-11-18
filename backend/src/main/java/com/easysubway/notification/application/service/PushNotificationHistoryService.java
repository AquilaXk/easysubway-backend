package com.easysubway.notification.application.service;

import com.easysubway.common.domain.PageResult;
import com.easysubway.notification.application.port.in.PushNotificationHistoryQuery;
import com.easysubway.notification.application.port.in.PushNotificationHistoryUseCase;
import com.easysubway.notification.application.port.out.SearchPushNotificationOutboxPort;
import com.easysubway.notification.domain.PushNotification;
import com.easysubway.notification.domain.PushNotificationFailureReasonCount;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class PushNotificationHistoryService implements PushNotificationHistoryUseCase {

	private final SearchPushNotificationOutboxPort searchPushNotificationOutboxPort;

	public PushNotificationHistoryService(SearchPushNotificationOutboxPort searchPushNotificationOutboxPort) {
		this.searchPushNotificationOutboxPort = searchPushNotificationOutboxPort;
	}

	@Override
	public PageResult<PushNotification> searchPushNotifications(PushNotificationHistoryQuery query) {
		List<PushNotification> items = searchPushNotificationOutboxPort.searchPushNotifications(query);
		return new PageResult<>(items, query.page(), query.size(), false);
	}

	@Override
	public long countPushNotifications(PushNotificationHistoryQuery query) {
		return searchPushNotificationOutboxPort.countPushNotifications(query);
	}

	@Override
	public List<PushNotificationFailureReasonCount> summarizeFailureReasons(PushNotificationHistoryQuery query) {
		return searchPushNotificationOutboxPort.countFailureReasons(query);
	}
}

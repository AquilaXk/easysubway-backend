package com.easysubway.notification.domain;

import java.util.List;

/**
 * 푸시를 실제로 내보낼 수 있는 상태인지와, 보낼 수 없을 때의 사유. 사유는 운영자에게 그대로 보여 준다.
 */
public record PushDeliveryAvailability(boolean available, List<String> reasons) {

	public PushDeliveryAvailability {
		reasons = List.copyOf(reasons);
	}
}

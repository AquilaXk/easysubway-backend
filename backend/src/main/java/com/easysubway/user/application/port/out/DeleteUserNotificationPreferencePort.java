package com.easysubway.user.application.port.out;

public interface DeleteUserNotificationPreferencePort {

	/**
	 * @return 삭제 대상 알림 설정이 존재해 제거됐으면 true, 없었으면 false
	 */
	boolean deleteNotificationSettings(String userId);

	int deleteRegisteredDevices(String userId);
}

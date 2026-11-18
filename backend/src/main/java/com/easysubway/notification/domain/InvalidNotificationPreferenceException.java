package com.easysubway.notification.domain;

import com.easysubway.common.error.InvalidRequestException;

public class InvalidNotificationPreferenceException extends InvalidRequestException {

	public InvalidNotificationPreferenceException(String message) {
		super(message);
	}
}

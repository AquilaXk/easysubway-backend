package com.easysubway.notification.domain;

import com.easysubway.common.error.InvalidRequestException;

public class InvalidPushNotificationException extends InvalidRequestException {

	public InvalidPushNotificationException(String message) {
		super(message);
	}
}

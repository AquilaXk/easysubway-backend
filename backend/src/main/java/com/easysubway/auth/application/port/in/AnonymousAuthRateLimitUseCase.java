package com.easysubway.auth.application.port.in;

public interface AnonymousAuthRateLimitUseCase {

	void check(String clientKey);
}

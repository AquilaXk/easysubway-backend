package com.easysubway.auth.application.port.out;

import java.time.Duration;

public interface ConsumeAnonymousAuthRateLimitPort {

	long consume(String clientKey, Duration window);
}

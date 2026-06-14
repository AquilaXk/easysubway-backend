package com.easysubway.auth.adapter.out.redis;

import com.easysubway.auth.application.port.out.ConsumeAnonymousAuthRateLimitPort;
import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Component
public class RedisAnonymousAuthRateLimitAdapter implements ConsumeAnonymousAuthRateLimitPort {

	private static final String KEY_PREFIX = "easysubway:auth:anonymous:rate-limit:";
	private static final RedisScript<Long> CONSUME_SCRIPT = RedisScript.of("""
		local count = redis.call('INCR', KEYS[1])
		if count == 1 then
			redis.call('PEXPIRE', KEYS[1], ARGV[1])
		end
		return count
		""", Long.class);

	private final StringRedisTemplate redisTemplate;

	public RedisAnonymousAuthRateLimitAdapter(StringRedisTemplate redisTemplate) {
		this.redisTemplate = redisTemplate;
	}

	@Override
	public long consume(String clientKey, Duration window) {
		long ttlMillis = ttlMillisFrom(window);
		Long count = redisTemplate.execute(
			CONSUME_SCRIPT,
			List.of(KEY_PREFIX + clientKey),
			String.valueOf(ttlMillis)
		);
		if (count == null) {
			throw new IllegalStateException("익명 인증 발급 제한을 확인할 수 없습니다.");
		}
		return count;
	}

	private long ttlMillisFrom(Duration window) {
		if (window == null || window.toMillis() < 1) {
			throw new IllegalArgumentException("익명 인증 발급 제한 시간은 1ms 이상이어야 합니다.");
		}
		return window.toMillis();
	}
}

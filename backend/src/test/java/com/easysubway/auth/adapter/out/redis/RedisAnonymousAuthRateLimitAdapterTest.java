package com.easysubway.auth.adapter.out.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

@DisplayName("Redis 익명 인증 발급 제한 어댑터")
class RedisAnonymousAuthRateLimitAdapterTest {

	@Test
	@DisplayName("클라이언트 키별 카운터와 TTL을 Redis script로 원자 처리한다")
	@SuppressWarnings("unchecked")
	void consumeUsesRedisScriptWithRateLimitKeyAndWindowTtl() {
		var redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
		var adapter = new RedisAnonymousAuthRateLimitAdapter(redisTemplate);
		when(redisTemplate.execute(
			any(RedisScript.class),
			eq(List.of("easysubway:auth:anonymous:rate-limit:client-1")),
			eq("600000")
		)).thenReturn(2L);

		long count = adapter.consume("client-1", Duration.ofMinutes(10));

		assertThat(count).isEqualTo(2L);
		verify(redisTemplate).execute(
			any(RedisScript.class),
			eq(List.of("easysubway:auth:anonymous:rate-limit:client-1")),
			eq("600000")
		);
	}

	@Test
	@DisplayName("Redis 응답이 없으면 발급 제한 확인 실패를 드러낸다")
	@SuppressWarnings("unchecked")
	void consumeFailsWhenRedisDoesNotReturnCount() {
		var redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
		var adapter = new RedisAnonymousAuthRateLimitAdapter(redisTemplate);
		when(redisTemplate.execute(any(RedisScript.class), eq(List.of("easysubway:auth:anonymous:rate-limit:client-1")), eq("600000")))
			.thenReturn(null);

		assertThatThrownBy(() -> adapter.consume("client-1", Duration.ofMinutes(10)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("익명 인증 발급 제한을 확인할 수 없습니다.");
	}

	@Test
	@DisplayName("TTL이 1ms보다 작으면 Redis 호출 전에 거부한다")
	void consumeRejectsSubMillisecondWindowBeforeRedisCall() {
		var redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
		var adapter = new RedisAnonymousAuthRateLimitAdapter(redisTemplate);

		assertThatThrownBy(() -> adapter.consume("client-1", Duration.ZERO))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("익명 인증 발급 제한 시간은 1ms 이상이어야 합니다.");
		verifyNoInteractions(redisTemplate);
	}
}

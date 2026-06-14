package com.easysubway.auth.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.auth.application.port.out.ConsumeAnonymousAuthRateLimitPort;
import com.easysubway.auth.domain.AnonymousAuthRateLimitExceededException;
import jakarta.validation.Validation;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("익명 인증 발급 제한 서비스")
class AnonymousAuthRateLimitServiceTest {

	@Test
	@DisplayName("허용 횟수 안에서는 같은 클라이언트 발급을 허용한다")
	void checkAllowsRequestsWithinLimit() {
		var port = new RecordingConsumeRateLimitPort(1, 2);
		var service = new AnonymousAuthRateLimitService(rateLimitProperties(2), port);

		assertThatCode(() -> {
			service.check("client-1");
			service.check("client-1");
		}).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("허용 횟수를 넘으면 같은 클라이언트 발급을 거부한다")
	void checkRejectsRequestsOverLimit() {
		var service = new AnonymousAuthRateLimitService(rateLimitProperties(1), new RecordingConsumeRateLimitPort(1, 2));

		service.check("client-1");

		assertThatThrownBy(() -> service.check("client-1"))
			.isInstanceOf(AnonymousAuthRateLimitExceededException.class)
			.hasMessage("잠시 후 다시 시도해 주세요.");
	}

	@Test
	@DisplayName("빈 클라이언트 키는 공통 키로 정규화한다")
	void checkNormalizesBlankClientKey() {
		var port = new RecordingConsumeRateLimitPort(1);
		var service = new AnonymousAuthRateLimitService(rateLimitProperties(1), port);

		service.check("  ");

		assertThat(port.clientKeys).containsExactly("unknown");
	}

	@Test
	@DisplayName("제한 시간 설정은 최소 1ms 이상이어야 한다")
	void propertiesRejectsWindowShorterThanOneMillisecond() {
		var properties = rateLimitProperties(1);
		properties.setWindow(Duration.ZERO);

		try (var validatorFactory = Validation.buildDefaultValidatorFactory()) {
			var violations = validatorFactory.getValidator().validate(properties);

			assertThat(violations)
				.anySatisfy(violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("window"));
		}
	}

	private AnonymousAuthRateLimitProperties rateLimitProperties(int maxRequests) {
		var properties = new AnonymousAuthRateLimitProperties();
		properties.setMaxRequests(maxRequests);
		properties.setWindow(Duration.ofMinutes(10));
		return properties;
	}

	private static final class RecordingConsumeRateLimitPort implements ConsumeAnonymousAuthRateLimitPort {

		private final long[] counts;
		private final List<String> clientKeys = new ArrayList<>();
		private int index;

		private RecordingConsumeRateLimitPort(long... counts) {
			this.counts = counts;
		}

		@Override
		public long consume(String clientKey, Duration window) {
			clientKeys.add(clientKey);
			long count = counts[Math.min(index, counts.length - 1)];
			index++;
			return count;
		}
	}
}

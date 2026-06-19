package com.easysubway.usage.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.usage.application.port.out.RecordUserActivityPort;
import com.easysubway.usage.application.port.out.RecordApiTrafficPort;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName("사용자 활동 기록 필터")
class UserActivityTrackingFilterTest {

	private static final Clock FIXED_CLOCK = Clock.fixed(
		Instant.parse("2026-06-17T00:00:00Z"),
		ZoneId.of("Asia/Seoul")
	);

	private final RecordingUserActivityPort port = new RecordingUserActivityPort();
	private final MutableClock clock = new MutableClock(FIXED_CLOCK.instant(), FIXED_CLOCK.getZone());
	private final TestTicker ticker = new TestTicker();
	private final UserActivityTrackingFilter filter = new UserActivityTrackingFilter(port, port, clock, ticker::nanoTime);

	@Test
	@DisplayName("성공한 인증 사용자 API 요청은 활동으로 기록한다")
	void successfulAuthenticatedApiRequestRecordsActivity() throws Exception {
		MockHttpServletRequest request = apiRequest("/api/v1/routes/search", "anonymous-user-1");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, successfulChain(Duration.ofMillis(125)));

		assertThat(port.records)
			.extracting(record -> record.userId() + ":" + record.occurredAt())
			.containsExactly("anonymous-user-1:2026-06-17T09:00");
		assertThat(port.apiTrafficRecords)
			.extracting(record -> record.statusCode() + ":" + record.durationMillis() + ":" + record.occurredAt())
			.containsExactly("200:125:2026-06-17T09:00");
	}

	@Test
	@DisplayName("실패 응답은 API 오류율에 기록하고 활성 사용자 지표에서는 제외한다")
	void failedApiRequestsRecordTrafficAndSkipActiveUserMetric() throws Exception {
		filter.doFilter(
			apiRequest("/api/v1/routes/search", "anonymous-user-1"),
			new MockHttpServletResponse(),
			failingChain(Duration.ofMillis(480))
		);

		assertThat(port.records).isEmpty();
		assertThat(port.apiTrafficRecords)
			.extracting(record -> record.statusCode() + ":" + record.durationMillis() + ":" + record.occurredAt())
			.containsExactly("500:480:2026-06-17T09:00");
	}

	@Test
	@DisplayName("관리자 요청은 제외하고 일반 API 요청은 오류율에 기록한다")
	void adminRequestsAreIgnoredFromTrafficMetric() throws Exception {
		filter.doFilter(apiRequest("/admin/routes/searches/page", "admin-user"), new MockHttpServletResponse(), successfulChain(Duration.ofMillis(95)));
		filter.doFilter(apiRequest("/api/v1/routes/search", null), new MockHttpServletResponse(), successfulChain(Duration.ofMillis(110)));

		assertThat(port.records).isEmpty();
		assertThat(port.apiTrafficRecords)
			.extracting(record -> record.statusCode() + ":" + record.durationMillis() + ":" + record.occurredAt())
			.containsExactly("200:110:2026-06-17T09:00");
	}

	@Test
	@DisplayName("Spring Security anonymous 사용자의 공개 API 요청은 기록하지 않는다")
	void anonymousAuthenticationIsIgnored() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/routes/search");
		request.setUserPrincipal(new AnonymousAuthenticationToken(
			"anonymous-key",
			"anonymousUser",
			AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")
		));

		filter.doFilter(request, new MockHttpServletResponse(), successfulChain(Duration.ofMillis(100)));

		assertThat(port.records).isEmpty();
	}

	private static MockHttpServletRequest apiRequest(String path, String userId) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
		if (userId != null) {
			request.setUserPrincipal((Principal) () -> userId);
		}
		return request;
	}

	private FilterChain successfulChain(Duration duration) {
		return (request, response) -> {
			ticker.advance(duration);
		};
	}

	private FilterChain failingChain(Duration duration) {
		return (request, response) -> {
			ticker.advance(duration);
			((MockHttpServletResponse) response).setStatus(500);
		};
	}

	private static final class RecordingUserActivityPort implements RecordUserActivityPort, RecordApiTrafficPort {

		private final List<Record> records = new ArrayList<>();
		private final List<ApiTrafficRecord> apiTrafficRecords = new ArrayList<>();

		@Override
		public void recordUserActivity(String userId, LocalDateTime occurredAt) {
			records.add(new Record(userId, occurredAt));
		}

		@Override
		public void recordApiTraffic(int statusCode, long durationMillis, LocalDateTime occurredAt) {
			apiTrafficRecords.add(new ApiTrafficRecord(statusCode, durationMillis, occurredAt));
		}
	}

	private static final class MutableClock extends Clock {

		private Instant instant;
		private final ZoneId zone;

		private MutableClock(Instant instant, ZoneId zone) {
			this.instant = instant;
			this.zone = zone;
		}

		@Override
		public ZoneId getZone() {
			return zone;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return new MutableClock(instant, zone);
		}

		@Override
		public Instant instant() {
			return instant;
		}

		private void advance(Duration duration) {
			instant = instant.plus(duration);
		}
	}

	private static final class TestTicker {

		private long nanos;

		private long nanoTime() {
			return nanos;
		}

		private void advance(Duration duration) {
			nanos += duration.toNanos();
		}
	}

	private record Record(String userId, LocalDateTime occurredAt) {
	}

	private record ApiTrafficRecord(int statusCode, long durationMillis, LocalDateTime occurredAt) {
	}
}

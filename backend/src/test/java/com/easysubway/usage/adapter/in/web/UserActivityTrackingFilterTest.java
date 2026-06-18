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
	private final UserActivityTrackingFilter filter = new UserActivityTrackingFilter(port, port, FIXED_CLOCK);

	@Test
	@DisplayName("성공한 인증 사용자 API 요청은 활동으로 기록한다")
	void successfulAuthenticatedApiRequestRecordsActivity() throws Exception {
		MockHttpServletRequest request = apiRequest("/api/v1/routes/search", "anonymous-user-1");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, successfulChain());

		assertThat(port.records)
			.extracting(record -> record.userId() + ":" + record.occurredAt())
			.containsExactly("anonymous-user-1:2026-06-17T09:00");
		assertThat(port.apiTrafficRecords)
			.extracting(record -> record.statusCode() + ":" + record.occurredAt())
			.containsExactly("200:2026-06-17T09:00");
	}

	@Test
	@DisplayName("실패 응답은 API 오류율에 기록하고 활성 사용자 지표에서는 제외한다")
	void failedApiRequestsRecordTrafficAndSkipActiveUserMetric() throws Exception {
		filter.doFilter(apiRequest("/api/v1/routes/search", "anonymous-user-1"), new MockHttpServletResponse(), failingChain());

		assertThat(port.records).isEmpty();
		assertThat(port.apiTrafficRecords)
			.extracting(record -> record.statusCode() + ":" + record.occurredAt())
			.containsExactly("500:2026-06-17T09:00");
	}

	@Test
	@DisplayName("인증 발급과 관리자 요청은 제외하고 일반 API 요청은 오류율에 기록한다")
	void authAndAdminRequestsAreIgnoredFromTrafficMetric() throws Exception {
		filter.doFilter(apiRequest("/api/v1/auth/anonymous", "anonymous-user-1"), new MockHttpServletResponse(), successfulChain());
		filter.doFilter(apiRequest("/admin/routes/searches/page", "admin-user"), new MockHttpServletResponse(), successfulChain());
		filter.doFilter(apiRequest("/api/v1/routes/search", null), new MockHttpServletResponse(), successfulChain());

		assertThat(port.records).isEmpty();
		assertThat(port.apiTrafficRecords)
			.extracting(record -> record.statusCode() + ":" + record.occurredAt())
			.containsExactly("200:2026-06-17T09:00");
	}

	@Test
	@DisplayName("Spring Security 익명 인증 사용자의 공개 API 요청은 기록하지 않는다")
	void anonymousAuthenticationIsIgnored() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/routes/search");
		request.setUserPrincipal(new AnonymousAuthenticationToken(
			"anonymous-key",
			"anonymousUser",
			AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")
		));

		filter.doFilter(request, new MockHttpServletResponse(), successfulChain());

		assertThat(port.records).isEmpty();
	}

	private static MockHttpServletRequest apiRequest(String path, String userId) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
		if (userId != null) {
			request.setUserPrincipal((Principal) () -> userId);
		}
		return request;
	}

	private static FilterChain successfulChain() {
		return (request, response) -> {
		};
	}

	private static FilterChain failingChain() {
		return (request, response) -> ((MockHttpServletResponse) response).setStatus(500);
	}

	private static final class RecordingUserActivityPort implements RecordUserActivityPort, RecordApiTrafficPort {

		private final List<Record> records = new ArrayList<>();
		private final List<ApiTrafficRecord> apiTrafficRecords = new ArrayList<>();

		@Override
		public void recordUserActivity(String userId, LocalDateTime occurredAt) {
			records.add(new Record(userId, occurredAt));
		}

		@Override
		public void recordApiTraffic(int statusCode, LocalDateTime occurredAt) {
			apiTrafficRecords.add(new ApiTrafficRecord(statusCode, occurredAt));
		}
	}

	private record Record(String userId, LocalDateTime occurredAt) {
	}

	private record ApiTrafficRecord(int statusCode, LocalDateTime occurredAt) {
	}
}

package com.easysubway.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.common.web.TrustedClientIpResolver;
import jakarta.servlet.FilterChain;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName("관리자 로그인 속도 제한 필터")
class AdminLoginRateLimitFilterTest {

	@Test
	@DisplayName("유효하지 않은 속도 제한 설정은 거부하고 null IP 해석기는 directOnly로 대체한다")
	void constructorValidatesLimitAndDefaultsNullResolver() throws Exception {
		Clock clock = Clock.fixed(Instant.parse("2026-10-17T00:00:00Z"), ZoneOffset.UTC);

		assertThatThrownBy(() -> new AdminLoginRateLimitFilter(TrustedClientIpResolver.directOnly(), 0, clock))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("maxRequestsPerMinute must be positive");
		assertThatThrownBy(() -> new AdminLoginRateLimitFilter(TrustedClientIpResolver.directOnly(), 5, null))
			.isInstanceOf(NullPointerException.class);

		var filter = new AdminLoginRateLimitFilter(null, 1, clock);
		MockHttpServletRequest first = postRequest("/admin/login", "198.51.100.1");
		MockHttpServletResponse firstRes = new MockHttpServletResponse();
		filter.doFilter(first, firstRes, okChain());
		assertThat(firstRes.getStatus()).isEqualTo(HttpStatus.OK.value());

		MockHttpServletRequest second = postRequest("/admin/login", "198.51.100.1");
		MockHttpServletResponse secondRes = new MockHttpServletResponse();
		filter.doFilter(second, secondRes, okChain());
		assertThat(secondRes.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
	}

	@Test
	@DisplayName("로그인 POST와 Basic 인증 시도 외 요청은 제한 없이 통과시킨다")
	void bypassesNonLoginAndNonBasicRequests() throws Exception {
		Clock clock = Clock.fixed(Instant.parse("2026-10-17T00:00:00Z"), ZoneOffset.UTC);
		var filter = new AdminLoginRateLimitFilter(TrustedClientIpResolver.directOnly(), 1, clock);
		AtomicInteger chainCalls = new AtomicInteger();
		FilterChain countingChain = (req, res) -> chainCalls.incrementAndGet();

		for (int i = 0; i < 3; i++) {
			MockHttpServletRequest getLogin = new MockHttpServletRequest("GET", "/admin/login");
			getLogin.setRemoteAddr("198.51.100.2");
			filter.doFilter(getLogin, new MockHttpServletResponse(), countingChain);

			MockHttpServletRequest postOther = postRequest("/admin/dashboard", "198.51.100.2");
			filter.doFilter(postOther, new MockHttpServletResponse(), countingChain);

			MockHttpServletRequest shortBasicHeader = new MockHttpServletRequest("GET", "/admin/dashboard");
			shortBasicHeader.setRemoteAddr("198.51.100.2");
			shortBasicHeader.addHeader(HttpHeaders.AUTHORIZATION, "Basic ");
			filter.doFilter(shortBasicHeader, new MockHttpServletResponse(), countingChain);

			MockHttpServletRequest bearerHeader = new MockHttpServletRequest("GET", "/admin/dashboard");
			bearerHeader.setRemoteAddr("198.51.100.2");
			bearerHeader.addHeader(HttpHeaders.AUTHORIZATION, "Bearer secret-token");
			filter.doFilter(bearerHeader, new MockHttpServletResponse(), countingChain);
		}

		assertThat(chainCalls.get()).isEqualTo(12);
	}

	@Test
	@DisplayName("관리자·운영기관 폼 로그인은 IP 단위로 제한하고 윈도우 경과 후 초기화된다")
	void formLoginRateLimitEnforcesWindowAndResetsAfterMinute() throws Exception {
		var clock = new MutableClock(Instant.parse("2026-10-17T00:00:00Z"));
		var filter = new AdminLoginRateLimitFilter(TrustedClientIpResolver.directOnly(), 2, clock);

		for (int i = 0; i < 2; i++) {
			MockHttpServletResponse res = new MockHttpServletResponse();
			filter.doFilter(postRequest("/operator/login", "198.51.100.3"), res, okChain());
			assertThat(res.getStatus()).isEqualTo(HttpStatus.OK.value());
		}

		MockHttpServletResponse blocked = new MockHttpServletResponse();
		filter.doFilter(postRequest("/admin/login", "198.51.100.3"), blocked, okChain());
		assertThat(blocked.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
		assertThat(blocked.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
		assertThat(blocked.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("60");
		assertThat(blocked.getContentAsString(StandardCharsets.UTF_8))
			.isEqualTo("{\"error\":\"TOO_MANY_REQUESTS\",\"retryable\":true}");

		clock.advance(Duration.ofSeconds(60));
		MockHttpServletResponse reset = new MockHttpServletResponse();
		filter.doFilter(postRequest("/admin/login", "198.51.100.3"), reset, okChain());
		assertThat(reset.getStatus()).isEqualTo(HttpStatus.OK.value());
	}

	@Test
	@DisplayName("Basic 인증 실패가 한도를 넘으면 차단하고 성공 및 윈도우 초기화와 HEAD 응답을 처리한다")
	void basicAuthFailuresAreRateLimitedAndHeadOmitsBody() throws Exception {
		var clock = new MutableClock(Instant.parse("2026-10-17T00:00:00Z"));
		var filter = new AdminLoginRateLimitFilter(TrustedClientIpResolver.directOnly(), 2, clock);

		// 성공 응답(200)은 실패 횟수에 누적되지 않는다.
		MockHttpServletResponse successRes = new MockHttpServletResponse();
		filter.doFilter(basicRequest("GET", "/admin/dashboard", "198.51.100.4"), successRes, okChain());
		assertThat(successRes.getStatus()).isEqualTo(HttpStatus.OK.value());

		// 첫 번째 401 실패 -> 아직 한도(2) 미만이라 다음 요청 통과 가능
		MockHttpServletResponse firstFail = new MockHttpServletResponse();
		filter.doFilter(basicRequest("GET", "/admin/dashboard", "198.51.100.4"), firstFail, unauthorizedChain());
		assertThat(firstFail.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());

		MockHttpServletResponse stillAllowed = new MockHttpServletResponse();
		filter.doFilter(basicRequest("GET", "/admin/dashboard", "198.51.100.4"), stillAllowed, unauthorizedChain());
		assertThat(stillAllowed.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());

		// 두 번째 401 이후 GET은 429 + JSON 본문, HEAD는 429 + 빈 본문 반환
		clock.advance(Duration.ofSeconds(15));
		MockHttpServletResponse blockedGet = new MockHttpServletResponse();
		filter.doFilter(basicRequest("GET", "/admin/dashboard", "198.51.100.4"), blockedGet, okChain());
		assertThat(blockedGet.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
		assertThat(blockedGet.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("45");
		assertThat(blockedGet.getContentAsString(StandardCharsets.UTF_8))
			.isEqualTo("{\"error\":\"TOO_MANY_REQUESTS\",\"retryable\":true}");

		MockHttpServletResponse blockedHead = new MockHttpServletResponse();
		filter.doFilter(basicRequest("HEAD", "/admin/dashboard", "198.51.100.4"), blockedHead, okChain());
		assertThat(blockedHead.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
		assertThat(blockedHead.getContentAsByteArray()).isEmpty();

		// 윈도우(60초) 경과 후에는 차단이 해제되고 다음 401에서 새 윈도우를 시작한다.
		clock.advance(Duration.ofSeconds(45));
		MockHttpServletResponse afterWindow = new MockHttpServletResponse();
		filter.doFilter(basicRequest("GET", "/admin/dashboard", "198.51.100.4"), afterWindow, unauthorizedChain());
		assertThat(afterWindow.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
	}

	@Test
	@DisplayName("미상 클라이언트 IP는 절반 한도로 unknown 버킷에 격리하고 최대 키 수를 초과하면 오래된 버킷을 제거한다")
	void unknownClientUsesHalfLimitAndBoundedMapEvictsOldestEntries() throws Exception {
		Clock clock = Clock.fixed(Instant.parse("2026-10-17T00:00:00Z"), ZoneOffset.UTC);
		var filter = new AdminLoginRateLimitFilter(TrustedClientIpResolver.directOnly(), 4, clock);

		// unknown 클라이언트는 4 / 2 = 2회까지 허용되고 3회째 차단된다.
		for (int i = 0; i < 2; i++) {
			MockHttpServletResponse res = new MockHttpServletResponse();
			filter.doFilter(postRequest("/admin/login", ""), res, okChain());
			assertThat(res.getStatus()).isEqualTo(HttpStatus.OK.value());
		}
		MockHttpServletResponse thirdUnknown = new MockHttpServletResponse();
		filter.doFilter(postRequest("/admin/login", ""), thirdUnknown, okChain());
		assertThat(thirdUnknown.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

		// 16,384개를 초과하는 서로 다른 IP가 로그인 요청을 보내면 removeEldestEntry가 동작하고 가장 오래된 IP가 축출된다.
		for (int i = 0; i <= 16_384; i++) {
			String ip = "10." + ((i >> 16) & 0xFF) + "." + ((i >> 8) & 0xFF) + "." + (i & 0xFF);
			MockHttpServletResponse res = new MockHttpServletResponse();
			filter.doFilter(postRequest("/admin/login", ip), res, okChain());
			assertThat(res.getStatus()).isEqualTo(HttpStatus.OK.value());
		}

		// 맨 처음 등록된 IP("10.0.0.0")는 축출되었으므로 다시 4회 모두 허용된다.
		for (int i = 0; i < 4; i++) {
			MockHttpServletResponse res = new MockHttpServletResponse();
			filter.doFilter(postRequest("/admin/login", "10.0.0.0"), res, okChain());
			assertThat(res.getStatus()).isEqualTo(HttpStatus.OK.value());
		}
	}

	private static MockHttpServletRequest postRequest(String path, String remoteAddr) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
		request.setRemoteAddr(remoteAddr);
		return request;
	}

	private static MockHttpServletRequest basicRequest(String method, String path, String remoteAddr) {
		MockHttpServletRequest request = new MockHttpServletRequest(method, path);
		request.setRemoteAddr(remoteAddr);
		request.addHeader(HttpHeaders.AUTHORIZATION, "basic dXNlcjpwYXNz");
		return request;
	}

	private static FilterChain okChain() {
		return (request, response) -> ((MockHttpServletResponse) response).setStatus(HttpStatus.OK.value());
	}

	private static FilterChain unauthorizedChain() {
		return (request, response) -> ((MockHttpServletResponse) response).setStatus(HttpStatus.UNAUTHORIZED.value());
	}

	private static final class MutableClock extends Clock {

		private Instant instant;

		private MutableClock(Instant instant) {
			this.instant = instant;
		}

		@Override
		public java.time.ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return instant;
		}

		private void advance(Duration duration) {
			instant = instant.plus(duration);
		}
	}
}

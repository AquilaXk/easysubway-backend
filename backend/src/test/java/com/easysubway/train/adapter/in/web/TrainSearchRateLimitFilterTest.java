package com.easysubway.train.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TrainSearchRateLimitFilterTest {

	@Test
	void returns429NoStoreAndIntegerRetryAfter() throws Exception {
		var filter = new TrainSearchRateLimitFilter(
			new ObjectMapper(),
			new TrainSearchRateLimiter(1, 10, fixedClock()),
			new TrainSearchClientIdentityResolver("")
		);
		var first = request();
		first.setParameter("trainType", "KTX");
		var firstResponse = new MockHttpServletResponse();
		filter.doFilter(first, firstResponse, new MockFilterChain());
		assertThat(firstResponse.getStatus()).isEqualTo(200);

		var limited = request();
		limited.setParameter("trainType", "KTX");
		var limitedResponse = new MockHttpServletResponse();
		filter.doFilter(limited, limitedResponse, new MockFilterChain());

		assertThat(limitedResponse.getStatus()).isEqualTo(429);
		assertThat(limitedResponse.getHeader("Cache-Control")).isEqualTo("no-store");
		assertThat(limitedResponse.getHeader("Retry-After")).isEqualTo("30");
		assertThat(limitedResponse.getContentAsString()).contains("TRAIN_SEARCH_RATE_LIMITED");
	}

	@Test
	void chargesOneTwoEightOrSixteenTokensFromSearchShape() throws Exception {
		var filter = new TrainSearchRateLimitFilter(
			new ObjectMapper(),
			new TrainSearchRateLimiter(24, 10, fixedClock()),
			new TrainSearchClientIdentityResolver("")
		);

		assertAllowed(filter, "KTX", null);
		assertAllowed(filter, "KTX", "2026-07-20");
		assertAllowed(filter, null, null);
		assertThat(filtered(filter, null, "2026-07-20").getStatus()).isEqualTo(429);
	}

	@Test
	void appliesTheSearchLimiterToHeadRequestsHandledByGetMappings() throws Exception {
		var filter = limiterWithOneToken();

		assertThat(filteredRequest(filter, request("HEAD", "/api/v1/trains/search"), "KTX").getStatus())
			.isEqualTo(200);
		var limited = filteredRequest(filter, request("HEAD", "/api/v1/trains/search"), "KTX");
		assertThat(limited.getStatus()).isEqualTo(429);
		assertThat(limited.getHeader("Cache-Control")).isEqualTo("no-store");
		assertThat(limited.getHeader("Retry-After")).isEqualTo("30");
		assertThat(limited.getContentAsByteArray()).isEmpty();
	}

	@Test
	void normalizesMatrixParametersBeforeSelectingEitherLimiter() throws Exception {
		var searchFilter = limiterWithOneToken();
		assertThat(filteredRequest(
			searchFilter,
			request("GET", "/api/v1/trains/search;x=1"),
			"KTX"
		).getStatus()).isEqualTo(200);
		assertThat(filteredRequest(
			searchFilter,
			request("GET", "/api/v1/trains/search;x=2"),
			"KTX"
		).getStatus()).isEqualTo(429);

		var stationFilter = limiterWithOneToken();
		assertThat(filteredRequest(stationFilter, request("GET", "/api/v1/trains/stations;x=1"), null).getStatus())
			.isEqualTo(200);
		assertThat(filteredRequest(stationFilter, request("GET", "/api/v1/trains/stations;x=2"), null).getStatus())
			.isEqualTo(429);
	}

	@Test
	void trustsForwardedChainOnlyWhenTheDirectPeerIsConfigured() {
		var resolver = new TrainSearchClientIdentityResolver("10.0.0.0/8,172.16.0.0/12,2001:db8::/32");
		var trusted = request();
		trusted.setRemoteAddr("172.20.0.1");
		trusted.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");
		assertThat(resolver.resolve(trusted)).isEqualTo("ip:203.0.113.7");

		var trustedIpv6 = request();
		trustedIpv6.setRemoteAddr("2001:db8::1");
		trustedIpv6.addHeader("X-Forwarded-For", "2001:db9::7");
		assertThat(resolver.resolve(trustedIpv6)).isEqualTo("ip:2001:db9:0:0:0:0:0:7");

		var untrusted = request();
		untrusted.setRemoteAddr("198.51.100.9");
		untrusted.addHeader("X-Forwarded-For", "203.0.113.7");
		assertThat(resolver.resolve(untrusted)).isEqualTo("ip:198.51.100.9");
	}

	@Test
	void maxIdentityCardinalityFailsClosedForANewClient() {
		var limiter = new TrainSearchRateLimiter(24, 1, fixedClock());
		assertThat(limiter.acquire("first", 1).allowed()).isTrue();
		assertThat(limiter.acquire("second", 1).allowed()).isFalse();
	}

	@Test
	void clearsTheWholeIdentityMapOnceWhenTheFixedWindowChanges() {
		var clock = new TestClock(Instant.parse("2026-07-19T00:00:30Z"));
		var limiter = new TrainSearchRateLimiter(24, 1, clock);
		assertThat(limiter.acquire("first", 1).allowed()).isTrue();
		assertThat(limiter.acquire("second", 1).allowed()).isFalse();

		clock.advanceSeconds(30);

		assertThat(limiter.acquire("second", 1).allowed()).isTrue();
		assertThat(limiter.acquire("first", 1).allowed()).isFalse();
	}

	@Test
	void acquireSerializesLookupCleanupRolloverAndTokenConsumption() throws Exception {
		var acquire = TrainSearchRateLimiter.class.getDeclaredMethod("acquire", String.class, int.class);

		assertThat(Modifier.isSynchronized(acquire.getModifiers())).isTrue();
	}

	@Test
	void dailySearchCostBudgetFailsClosedAndResetsAtTheNextKstDay() {
		var clock = new TestClock(Instant.parse("2026-07-19T14:59:30Z"));
		var limiter = new TrainSearchRateLimiter(24, 2, 10, clock, ZoneId.of("Asia/Seoul"));

		assertThat(limiter.acquire("client", 2)).isEqualTo(new TrainSearchRateLimiter.AcquireResult(true, 0));
		assertThat(limiter.acquire("client", 1)).isEqualTo(new TrainSearchRateLimiter.AcquireResult(false, 30));

		clock.advanceSeconds(30);

		assertThat(limiter.acquire("client", 1)).isEqualTo(new TrainSearchRateLimiter.AcquireResult(true, 0));
	}

	@Test
	void dailyBudgetRejectsAnOverBudgetCostWithoutPartiallyChargingTheMinuteWindow() {
		var limiter = new TrainSearchRateLimiter(2, 1, 10, fixedClock(), ZoneId.of("Asia/Seoul"));

		assertThat(limiter.acquire("client", 2).allowed()).isFalse();
		assertThat(limiter.acquire("client", 1).allowed()).isTrue();
	}

	@Test
	void dailyLimiterCardinalityDenialRetriesAtTheNextKstDay() {
		var clock = Clock.fixed(Instant.parse("2026-07-19T14:58:30Z"), ZoneOffset.UTC);
		var limiter = new TrainSearchRateLimiter(24, 64, 1, clock, ZoneId.of("Asia/Seoul"));
		assertThat(limiter.acquire("first", 1).allowed()).isTrue();

		assertThat(limiter.acquire("second", 1))
			.isEqualTo(new TrainSearchRateLimiter.AcquireResult(false, 90));
	}

	@Test
	void roundsASubsecondDailyRetryAfterUpToOneSecond() {
		var clock = Clock.fixed(Instant.parse("2026-07-19T14:59:59.500Z"), ZoneOffset.UTC);
		var limiter = new TrainSearchRateLimiter(24, 1, 10, clock, ZoneId.of("Asia/Seoul"));
		assertThat(limiter.acquire("client", 1).allowed()).isTrue();

		assertThat(limiter.acquire("client", 1))
			.isEqualTo(new TrainSearchRateLimiter.AcquireResult(false, 1));
	}

	private void assertAllowed(TrainSearchRateLimitFilter filter, String trainType, String returnDate) throws Exception {
		assertThat(filtered(filter, trainType, returnDate).getStatus()).isEqualTo(200);
	}

	private MockHttpServletResponse filtered(
		TrainSearchRateLimitFilter filter,
		String trainType,
		String returnDate
	) throws Exception {
		var request = request();
		if (trainType != null) request.setParameter("trainType", trainType);
		if (returnDate != null) request.setParameter("returnDate", returnDate);
		var response = new MockHttpServletResponse();
		filter.doFilter(request, response, new MockFilterChain());
		return response;
	}

	private MockHttpServletResponse filteredRequest(
		TrainSearchRateLimitFilter filter,
		MockHttpServletRequest request,
		String trainType
	) throws Exception {
		if (trainType != null) request.setParameter("trainType", trainType);
		var response = new MockHttpServletResponse();
		filter.doFilter(request, response, new MockFilterChain());
		return response;
	}

	private TrainSearchRateLimitFilter limiterWithOneToken() {
		return new TrainSearchRateLimitFilter(
			new ObjectMapper(),
			new TrainSearchRateLimiter(1, 10, fixedClock()),
			new TrainSearchClientIdentityResolver("")
		);
	}

	private MockHttpServletRequest request() {
		return request("GET", "/api/v1/trains/search");
	}

	private MockHttpServletRequest request(String method, String path) {
		var request = new MockHttpServletRequest(method, path);
		request.setRemoteAddr("203.0.113.1");
		return request;
	}

	private Clock fixedClock() {
		return Clock.fixed(Instant.parse("2026-07-19T00:00:30Z"), ZoneOffset.UTC);
	}

	private static final class TestClock extends Clock {
		private Instant instant;

		private TestClock(Instant instant) {
			this.instant = instant;
		}

		void advanceSeconds(long seconds) {
			instant = instant.plusSeconds(seconds);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return instant;
		}
	}
}

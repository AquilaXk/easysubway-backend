package com.easysubway.route.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.easysubway.route.application.port.out.RouteV2AccessStore;
import com.easysubway.route.application.port.out.RouteV2AccessStore.SessionStatus;
import com.easysubway.route.application.port.out.RouteV2AccessStore.SessionUse;
import jakarta.servlet.FilterChain;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName("Route V2 production ingress filter")
class RouteV2IngressFilterTest {

	private static final Instant NOW = Instant.parse("2026-07-16T09:00:00Z");

	@Test
	@DisplayName("origin 증명 없는 direct-origin 요청은 controller 전에 exact 403으로 거부한다")
	void originGateRejectsDirectOrigin() throws Exception {
		var filter = new RouteV2OriginGateFilter("origin-secret");
		var request = request("/api/v2/routes/search");
		var response = new MockHttpServletResponse();
		FilterChain chain = mock(FilterChain.class);

		filter.doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(403);
		assertThat(response.getContentAsString()).contains("\"code\":\"ROUTE_ORIGIN_FORBIDDEN\"");
		verify(chain, never()).doFilter(request, response);
	}

	@Test
	@DisplayName("gateway 경유 검색의 session 없음·unknown은 exact 401로 거부한다")
	void sessionFilterRejectsMissingAndUnknownSession() throws Exception {
		RouteV2AccessStore store = mock(RouteV2AccessStore.class);
		var filter = filter(store);
		FilterChain chain = mock(FilterChain.class);

		var missingRequest = request("/api/v2/routes/search");
		var missingResponse = new MockHttpServletResponse();
		filter.doFilter(missingRequest, missingResponse, chain);
		assertThat(missingResponse.getStatus()).isEqualTo(401);
		assertThat(missingResponse.getContentAsString()).contains("\"code\":\"ROUTE_SESSION_REQUIRED\"");

		var unknownRequest = request("/api/v2/routes/search");
		unknownRequest.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + "A".repeat(43));
		when(store.consumeSession(anyString(), eq(NOW))).thenReturn(new SessionUse(SessionStatus.MISSING, null, null));
		var unknownResponse = new MockHttpServletResponse();
		filter.doFilter(unknownRequest, unknownResponse, chain);
		assertThat(unknownResponse.getStatus()).isEqualTo(401);
		assertThat(unknownResponse.getContentAsString()).contains("\"code\":\"ROUTE_SESSION_REQUIRED\"");
		verify(chain, never()).doFilter(unknownRequest, unknownResponse);
	}

	@Test
	@DisplayName("50회를 소비한 session은 exact 429와 정수 Retry-After를 반환한다")
	void sessionFilterReturnsRateLimitContract() throws Exception {
		RouteV2AccessStore store = mock(RouteV2AccessStore.class);
		var request = request("/api/v2/routes/search");
		request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + "B".repeat(43));
		when(store.consumeSession(anyString(), eq(NOW)))
			.thenReturn(new SessionUse(SessionStatus.LIMITED, "route:v2:itx", NOW.plusSeconds(37)));
		var response = new MockHttpServletResponse();
		FilterChain chain = mock(FilterChain.class);

		filter(store).doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(429);
		assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("37");
		assertThat(response.getContentAsString()).contains("\"code\":\"ROUTE_RATE_LIMITED\"");
		verify(chain, never()).doFilter(request, response);
	}

	@Test
	@DisplayName("만료 session은 exact 401이고 controller를 호출하지 않는다")
	void sessionFilterRejectsExpiredSession() throws Exception {
		RouteV2AccessStore store = mock(RouteV2AccessStore.class);
		var request = request("/api/v2/routes/search");
		request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + "D".repeat(43));
		when(store.consumeSession(anyString(), eq(NOW)))
			.thenReturn(new SessionUse(SessionStatus.EXPIRED, "route:v2:itx", NOW.minusSeconds(1)));
		var response = new MockHttpServletResponse();
		FilterChain chain = mock(FilterChain.class);

		filter(store).doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(response.getContentAsString()).contains("\"code\":\"ROUTE_SESSION_REQUIRED\"");
		verify(chain, never()).doFilter(request, response);
	}

	@Test
	@DisplayName("다른 scope session은 exact 422이고 controller를 호출하지 않는다")
	void sessionFilterRejectsWrongScope() throws Exception {
		RouteV2AccessStore store = mock(RouteV2AccessStore.class);
		var request = request("/api/v2/routes/search");
		request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + "E".repeat(43));
		when(store.consumeSession(anyString(), eq(NOW)))
			.thenReturn(new SessionUse(SessionStatus.VALID, "route:v2:subway", NOW.plusSeconds(600)));
		var response = new MockHttpServletResponse();
		FilterChain chain = mock(FilterChain.class);

		filter(store).doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(422);
		assertThat(response.getContentAsString()).contains("\"code\":\"ROUTE_SCOPE_INVALID\"");
		verify(chain, never()).doFilter(request, response);
	}

	@Test
	@DisplayName("유효 session만 scope를 확인한 뒤 controller로 전달한다")
	void sessionFilterAllowsValidScopedSession() throws Exception {
		RouteV2AccessStore store = mock(RouteV2AccessStore.class);
		var request = request("/api/v2/routes/search");
		request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + "C".repeat(43));
		when(store.consumeSession(anyString(), eq(NOW)))
			.thenReturn(new SessionUse(SessionStatus.VALID, "route:v2:itx", NOW.plusSeconds(600)));
		var response = new MockHttpServletResponse();
		FilterChain chain = mock(FilterChain.class);

		filter(store).doFilter(request, response, chain);

		verify(chain).doFilter(request, response);
	}

	private RouteV2SessionFilter filter(RouteV2AccessStore store) {
		return new RouteV2SessionFilter(store, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private MockHttpServletRequest request(String path) {
		return new MockHttpServletRequest("POST", path);
	}

}

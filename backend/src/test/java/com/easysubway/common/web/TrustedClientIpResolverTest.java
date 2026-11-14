package com.easysubway.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

@DisplayName("신뢰 클라이언트 IP 및 User-Agent 정제기")
class TrustedClientIpResolverTest {

	@Test
	@DisplayName("신뢰 프록시가 설정된 경우에만 X-Forwarded-For 오른쪽부터 첫 비신뢰 IP를 선택한다")
	void walksForwardedChainRightToLeftOnlyForTrustedProxies() {
		var resolver = TrustedClientIpResolver.fromCidrs("10.0.0.0/8,172.16.0.0/12,2001:db8::/32");

		var trusted = new MockHttpServletRequest();
		trusted.setRemoteAddr("172.20.0.1");
		trusted.addHeader("X-Forwarded-For", "198.51.100.1, 203.0.113.7, 10.0.0.1");
		assertThat(resolver.resolve(trusted)).isEqualTo("203.0.113.7");

		var trustedIpv6 = new MockHttpServletRequest();
		trustedIpv6.setRemoteAddr("2001:db8::1");
		trustedIpv6.addHeader("X-Forwarded-For", "2001:db9::7");
		assertThat(resolver.resolve(trustedIpv6)).isEqualTo("2001:db9:0:0:0:0:0:7");

		var untrusted = new MockHttpServletRequest();
		untrusted.setRemoteAddr("198.51.100.9");
		untrusted.addHeader("X-Forwarded-For", "203.0.113.7");
		assertThat(resolver.resolve(untrusted)).isEqualTo("198.51.100.9");
	}

	@Test
	@DisplayName("비정상 hop·전체 신뢰 체인·과도한 hop 수는 unknown으로 Fail-Closed 처리한다")
	void failsClosedOnMalformedAllTrustedOrExcessiveHops() {
		var resolver = TrustedClientIpResolver.fromCidrs("10.0.0.0/8");

		var malformed = new MockHttpServletRequest();
		malformed.setRemoteAddr("10.1.2.3");
		malformed.addHeader("X-Forwarded-For", "203.0.113.7, not-an-ip");
		assertThat(resolver.resolve(malformed)).isEqualTo(TrustedClientIpResolver.UNKNOWN);

		var allTrusted = new MockHttpServletRequest();
		allTrusted.setRemoteAddr("10.1.2.3");
		allTrusted.addHeader("X-Forwarded-For", "10.0.0.1, 10.0.0.2");
		assertThat(resolver.resolve(allTrusted)).isEqualTo(TrustedClientIpResolver.UNKNOWN);

		var excessive = new MockHttpServletRequest();
		excessive.setRemoteAddr("10.1.2.3");
		excessive.addHeader("X-Forwarded-For", "203.0.113.9," + "10.0.0.1,".repeat(TrustedClientIpResolver.MAX_XFF_HOPS) + "10.0.0.2");
		assertThat(resolver.resolve(excessive)).isEqualTo(TrustedClientIpResolver.UNKNOWN);

		assertThatThrownBy(() -> TrustedClientIpResolver.fromCidrs("10.0.0.0/33"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> TrustedClientIpResolver.fromCidrs("/24"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> TrustedClientIpResolver.fromCidrs("2001:db8::/129"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("비정렬 CIDR, 다중 X-Forwarded-For 헤더, IPv4-mapped IPv6 및 포트 표기를 일관되게 처리한다")
	void handlesUnalignedCidrMultiHeaderBracketedPortAndMappedIpv6() {
		var unaligned = TrustedClientIpResolver.fromCidrs("192.168.1.16/28");
		var matched = new MockHttpServletRequest();
		matched.setRemoteAddr("192.168.1.20");
		matched.addHeader("X-Forwarded-For", "203.0.113.5");
		assertThat(unaligned.resolve(matched)).isEqualTo("203.0.113.5");

		var unmatched = new MockHttpServletRequest();
		unmatched.setRemoteAddr("192.168.1.35");
		unmatched.addHeader("X-Forwarded-For", "203.0.113.5");
		assertThat(unaligned.resolve(unmatched)).isEqualTo("192.168.1.35");

		var multiHeaderResolver = TrustedClientIpResolver.fromCidrs("10.0.0.1,10.0.0.2");
		var multi = new MockHttpServletRequest();
		multi.setRemoteAddr("10.0.0.1");
		multi.addHeader("X-Forwarded-For", "[2001:db8::1]:8080");
		multi.addHeader("X-Forwarded-For", "10.0.0.2");
		assertThat(multiHeaderResolver.resolve(multi)).isEqualTo("2001:db8:0:0:0:0:0:1");

		var mapped = new MockHttpServletRequest();
		mapped.setRemoteAddr("10.0.0.1");
		mapped.addHeader("X-Forwarded-For", "::ffff:203.0.113.55");
		assertThat(multiHeaderResolver.resolve(mapped)).isEqualTo("203.0.113.55");

		assertThat(TrustedClientIpResolver.isValidIpLiteral("192.168.1.1:8080")).isTrue();
		assertThat(TrustedClientIpResolver.isValidIpLiteral("not-an-ip")).isFalse();
	}

	@Test
	@DisplayName("User-Agent에서 제어문자를 제거하고 256자로 제한한다")
	void sanitizesControlCharactersAndCapsUserAgentLength() {
		assertThat(TrustedClientIpResolver.sanitizeUserAgent(null)).isEqualTo("missing");
		assertThat(TrustedClientIpResolver.sanitizeUserAgent(" \r\n\t\u0000\u007f ")).isEqualTo("missing");
		assertThat(TrustedClientIpResolver.sanitizeUserAgent("Mozilla/5.0\r\nFakeHeader: 1\t\u0000OK\u007f"))
			.isEqualTo("Mozilla/5.0FakeHeader: 1OK");

		String oversized = "A".repeat(300);
		assertThat(TrustedClientIpResolver.sanitizeUserAgent(oversized))
			.hasSize(TrustedClientIpResolver.MAX_USER_AGENT_LENGTH)
			.isEqualTo("A".repeat(256));
	}
}

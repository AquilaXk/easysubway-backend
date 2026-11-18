package com.easysubway.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

@DisplayName("서브넷 기반 HMAC 클라이언트 IP 해시 리졸버")
class ClientIpHashResolverTest {

	@Test
	@DisplayName("IPv4 /24 와 IPv6 /64 서브넷 단위로 동일 HMAC 키를 생성하고 원 IP를 포함하지 않는다")
	void masksIpv4To24AndIpv6To64WithoutExposingRawIp() {
		var resolver = ClientIpHashResolver.fromTrustedProxies("10.0.0.0/8", "unit-test-secret");

		var ipv4a = request("10.0.0.1", "203.0.113.7");
		var ipv4b = request("10.0.0.1", "203.0.113.250");
		var ipv4OtherSubnet = request("10.0.0.1", "203.0.114.7");

		String key4a = resolver.resolve(ipv4a);
		String key4b = resolver.resolve(ipv4b);
		String key4Other = resolver.resolve(ipv4OtherSubnet);

		assertThat(key4a)
			.startsWith("subnet-hmac:")
			.isEqualTo(key4b)
			.isNotEqualTo(key4Other)
			.doesNotContain("203.0.113")
			.doesNotContain("203")
			.doesNotContain("113");

		var ipv6a = request("10.0.0.1", "2001:db8:abcd:0001::10");
		var ipv6b = request("10.0.0.1", "2001:db8:abcd:0001:ffff::9999");
		var ipv6OtherSubnet = request("10.0.0.1", "2001:db8:abcd:0002::10");

		String key6a = resolver.resolve(ipv6a);
		assertThat(key6a)
			.startsWith("subnet-hmac:")
			.isEqualTo(resolver.resolve(ipv6b))
			.isNotEqualTo(resolver.resolve(ipv6OtherSubnet))
			.doesNotContain("2001")
			.doesNotContain("abcd");
	}

	@Test
	@DisplayName("비신뢰 피어의 X-Forwarded-For는 무시하고 잘못된 IP는 고정 unknown 키로 Fail-Closed 한다")
	void ignoresUntrustedForwardedForAndFailsClosedOnUnknown() {
		var resolver = ClientIpHashResolver.fromTrustedProxies("10.0.0.0/8", "unit-test-secret");

		var spoofed = new MockHttpServletRequest();
		spoofed.setRemoteAddr("198.51.100.9");
		spoofed.addHeader("X-Forwarded-For", "203.0.113.7");
		assertThat(resolver.resolve(spoofed)).isEqualTo(resolver.hashIpLiteral("198.51.100.55"));

		var invalid = request("10.0.0.1", "not-an-ip");
		assertThat(resolver.resolve(invalid)).isEqualTo(ClientIpHashResolver.UNKNOWN_KEY);
	}

	@Test
	@DisplayName("HMAC 비밀키가 비어 있으면 인스턴스마다 독립적인 랜덤 키를 생성한다")
	void usesDistinctEphemeralKeysWhenSecretIsBlank() {
		var first = ClientIpHashResolver.fromTrustedProxies("", "");
		var second = ClientIpHashResolver.fromTrustedProxies("", "");

		assertThat(first.hashIpLiteral("203.0.113.7"))
			.startsWith("subnet-hmac:")
			.isNotEqualTo(second.hashIpLiteral("203.0.113.7"));
	}

	private static MockHttpServletRequest request(String remoteAddr, String forwardedFor) {
		var req = new MockHttpServletRequest();
		req.setRemoteAddr(remoteAddr);
		req.addHeader("X-Forwarded-For", forwardedFor);
		return req;
	}
}

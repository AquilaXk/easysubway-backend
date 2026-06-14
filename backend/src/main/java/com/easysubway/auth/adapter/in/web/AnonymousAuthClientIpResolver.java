package com.easysubway.auth.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.stereotype.Component;

@Component
class AnonymousAuthClientIpResolver {

	private static final String X_FORWARDED_FOR = "X-Forwarded-For";

	private final AnonymousAuthClientIpProperties properties;

	AnonymousAuthClientIpResolver(AnonymousAuthClientIpProperties properties) {
		this.properties = properties;
	}

	String resolve(HttpServletRequest request) {
		String remoteAddress = request.getRemoteAddr();
		if (!isTrustedProxy(remoteAddress)) {
			return remoteAddress;
		}
		// 전달 헤더는 신뢰 프록시에서 들어온 요청일 때만 rate limit 키 후보로 사용한다.
		String forwardedClientIp = firstUntrustedForwardedClientIp(request.getHeader(X_FORWARDED_FOR));
		if (forwardedClientIp == null) {
			return remoteAddress;
		}
		return forwardedClientIp;
	}

	private String firstUntrustedForwardedClientIp(String forwardedFor) {
		if (forwardedFor == null || forwardedFor.isBlank()) {
			return null;
		}
		String[] hops = forwardedFor.split(",");
		for (int index = hops.length - 1; index >= 0; index--) {
			String candidate = hops[index].trim();
			if (candidate.isBlank() || !isIpAddress(candidate)) {
				return null;
			}
			if (!isTrustedProxy(candidate)) {
				return candidate;
			}
		}
		return null;
	}

	private boolean isTrustedProxy(String remoteAddress) {
		return properties.getTrustedProxies().stream()
			.anyMatch(trustedProxy -> matchesTrustedProxy(remoteAddress, trustedProxy));
	}

	private boolean matchesTrustedProxy(String remoteAddress, String trustedProxy) {
		if (trustedProxy == null || trustedProxy.isBlank()) {
			return false;
		}
		String normalizedTrustedProxy = trustedProxy.trim();
		if (normalizedTrustedProxy.contains("/")) {
			return matchesCidr(remoteAddress, normalizedTrustedProxy);
		}
		return normalizedTrustedProxy.equals(remoteAddress);
	}

	private boolean matchesCidr(String remoteAddress, String cidr) {
		String[] parts = cidr.split("/", 2);
		if (parts.length != 2) {
			return false;
		}
		try {
			InetAddress remote = parseIpAddress(remoteAddress);
			InetAddress network = parseIpAddress(parts[0]);
			int prefixLength = Integer.parseInt(parts[1]);
			return matchesPrefix(remote.getAddress(), network.getAddress(), prefixLength);
		} catch (NumberFormatException | UnknownHostException exception) {
			return false;
		}
	}

	private boolean matchesPrefix(byte[] remoteAddress, byte[] networkAddress, int prefixLength) {
		if (remoteAddress.length != networkAddress.length || prefixLength < 0 || prefixLength > remoteAddress.length * 8) {
			return false;
		}
		int fullBytes = prefixLength / 8;
		int remainingBits = prefixLength % 8;
		for (int index = 0; index < fullBytes; index++) {
			if (remoteAddress[index] != networkAddress[index]) {
				return false;
			}
		}
		if (remainingBits == 0) {
			return true;
		}
		int mask = (0xFF << (8 - remainingBits)) & 0xFF;
		return (remoteAddress[fullBytes] & mask) == (networkAddress[fullBytes] & mask);
	}

	private boolean isIpAddress(String value) {
		try {
			parseIpAddress(value);
			return true;
		} catch (UnknownHostException exception) {
			return false;
		}
	}

	private InetAddress parseIpAddress(String value) throws UnknownHostException {
		String trimmed = value.trim();
		if (trimmed.contains(":")) {
			return InetAddress.getByName(trimmed);
		}
		return InetAddress.getByAddress(parseIpv4Address(trimmed));
	}

	private byte[] parseIpv4Address(String value) throws UnknownHostException {
		String[] octets = value.split("\\.", -1);
		if (octets.length != 4) {
			throw new UnknownHostException(value);
		}
		byte[] address = new byte[4];
		for (int index = 0; index < octets.length; index++) {
			address[index] = parseIpv4Octet(octets[index], value);
		}
		return address;
	}

	private byte parseIpv4Octet(String octet, String originalValue) throws UnknownHostException {
		try {
			int parsed = Integer.parseInt(octet);
			if (parsed < 0 || parsed > 255) {
				throw new UnknownHostException(originalValue);
			}
			return (byte) parsed;
		} catch (NumberFormatException exception) {
			UnknownHostException unknownHostException = new UnknownHostException(originalValue);
			unknownHostException.initCause(exception);
			throw unknownHostException;
		}
	}
}

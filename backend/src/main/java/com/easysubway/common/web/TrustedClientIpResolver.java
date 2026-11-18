package com.easysubway.common.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * Resolves verified client IP addresses from trusted reverse-proxy chains
 * without falling back to unverified hostnames or spoofing-prone leftmost headers.
 */
public final class TrustedClientIpResolver {

	public static final String UNKNOWN = "unknown";
	public static final String MISSING_USER_AGENT = "missing";
	public static final int MAX_XFF_HOPS = 32;
	public static final int MAX_USER_AGENT_LENGTH = 256;

	private static final int IPV4_BYTE_LENGTH = 4;
	private static final int IPV6_BYTE_LENGTH = 16;

	private final List<IpCidr> trustedProxies;

	private TrustedClientIpResolver(List<IpCidr> trustedProxies) {
		this.trustedProxies = trustedProxies;
	}

	public static TrustedClientIpResolver directOnly() {
		return new TrustedClientIpResolver(List.of());
	}

	public static TrustedClientIpResolver fromCidrs(String trustedProxyCidrs) {
		if (trustedProxyCidrs == null || trustedProxyCidrs.isBlank()) {
			return directOnly();
		}
		List<IpCidr> cidrs = new ArrayList<>();
		for (String part : trustedProxyCidrs.split(",")) {
			String normalized = part.trim();
			if (!normalized.isEmpty()) {
				cidrs.add(IpCidr.parse(normalized));
			}
		}
		return new TrustedClientIpResolver(List.copyOf(cidrs));
	}

	public String resolve(HttpServletRequest request) {
		byte[] addressBytes = resolveAddressBytes(request);
		if (addressBytes == null) {
			return UNKNOWN;
		}
		return formatBytes(addressBytes);
	}

	public byte[] resolveAddressBytes(HttpServletRequest request) {
		if (request == null) {
			return null;
		}
		byte[] remoteBytes = parseLiteralBytesOrNull(request.getRemoteAddr());
		if (remoteBytes == null) {
			return null;
		}
		if (!isTrustedProxy(remoteBytes)) {
			return remoteBytes;
		}
		List<String> headerValues = collectForwardedForHeaders(request);
		if (headerValues.isEmpty()) {
			return remoteBytes;
		}
		return walkForwardedChainRightToLeft(headerValues);
	}

	public static boolean isValidIpLiteral(String value) {
		return parseLiteralBytesOrNull(value) != null;
	}

	public static InetAddress parseLiteral(String value) {
		if (value == null) {
			throw new IllegalArgumentException("IP address cannot be null");
		}
		String candidate = stripPortAndZone(value);
		if (candidate.isEmpty()) {
			throw new IllegalArgumentException("invalid IP address: " + value);
		}
		if (candidate.contains(":")) {
			if (!isValidIpv6Chars(candidate)) {
				throw new IllegalArgumentException("invalid IPv6 address: " + value);
			}
		} else if (!isValidIpv4Literal(candidate)) {
			throw new IllegalArgumentException("invalid IPv4 address: " + value);
		}
		try {
			return InetAddress.getByName(candidate);
		} catch (UnknownHostException exception) {
			throw new IllegalArgumentException("invalid IP address: " + value, exception);
		}
	}

	public static String sanitizeUserAgent(String userAgent) {
		if (userAgent == null) {
			return MISSING_USER_AGENT;
		}
		int len = userAgent.length();
		StringBuilder cleaned = new StringBuilder(Math.min(len, MAX_USER_AGENT_LENGTH));
		for (int i = 0; i < len && cleaned.length() < MAX_USER_AGENT_LENGTH; i++) {
			char ch = userAgent.charAt(i);
			if (ch <= 0x1F || ch == 0x7F) {
				continue;
			}
			cleaned.append(ch);
		}
		String trimmed = cleaned.toString().trim();
		return trimmed.isEmpty() ? MISSING_USER_AGENT : trimmed;
	}

	private byte[] walkForwardedChainRightToLeft(List<String> headerValues) {
		int seenHops = 0;
		for (int h = headerValues.size() - 1; h >= 0; h--) {
			String header = headerValues.get(h);
			int end = header.length();
			while (end >= 0) {
				int comma = header.lastIndexOf(',', end - 1);
				String hop = header.substring(comma + 1, end).trim();
				seenHops++;
				if (seenHops > MAX_XFF_HOPS) {
					return null;
				}
				byte[] hopBytes = parseLiteralBytesOrNull(hop);
				if (hopBytes == null) {
					return null;
				}
				if (!isTrustedProxy(hopBytes)) {
					return hopBytes;
				}
				if (comma < 0) {
					break;
				}
				end = comma;
			}
		}
		return null;
	}

	private static List<String> collectForwardedForHeaders(HttpServletRequest request) {
		List<String> values = new ArrayList<>(2);
		Enumeration<String> headers = request.getHeaders("X-Forwarded-For");
		if (headers != null) {
			while (headers.hasMoreElements()) {
				String value = headers.nextElement();
				if (value != null && !value.isBlank()) {
					values.add(value);
				}
			}
		}
		if (values.isEmpty()) {
			String single = request.getHeader("X-Forwarded-For");
			if (single != null && !single.isBlank()) {
				values.add(single);
			}
		}
		return values;
	}

	private boolean isTrustedProxy(byte[] addressBytes) {
		for (int i = 0; i < trustedProxies.size(); i++) {
			if (trustedProxies.get(i).contains(addressBytes)) {
				return true;
			}
		}
		return false;
	}

	static byte[] parseLiteralBytesOrNull(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return parseLiteral(value).getAddress();
		} catch (IllegalArgumentException exception) {
			return null;
		}
	}

	private static String formatBytes(byte[] bytes) {
		if (bytes.length != IPV4_BYTE_LENGTH && bytes.length != IPV6_BYTE_LENGTH) {
			return UNKNOWN;
		}
		try {
			return InetAddress.getByAddress(bytes).getHostAddress().toLowerCase(Locale.ROOT);
		} catch (UnknownHostException exception) {
			return UNKNOWN;
		}
	}

	private static String stripPortAndZone(String value) {
		String candidate = value.trim();
		if (candidate.startsWith("[")) {
			int closeBracket = candidate.indexOf(']');
			if (closeBracket <= 1) {
				throw new IllegalArgumentException("invalid bracketed IPv6 address: " + value);
			}
			String afterBracket = candidate.substring(closeBracket + 1);
			if (!afterBracket.isEmpty() && !isValidPortSuffix(afterBracket)) {
				throw new IllegalArgumentException("invalid port suffix in IPv6 address: " + value);
			}
			candidate = candidate.substring(1, closeBracket);
		} else {
			int firstColon = candidate.indexOf(':');
			int lastColon = candidate.lastIndexOf(':');
			if (firstColon >= 0 && firstColon == lastColon) {
				if (!isValidPortSuffix(candidate.substring(firstColon))) {
					throw new IllegalArgumentException("invalid port suffix in IPv4 address: " + value);
				}
				candidate = candidate.substring(0, firstColon);
			}
		}
		int zoneIdx = candidate.indexOf('%');
		if (zoneIdx >= 0) {
			candidate = candidate.substring(0, zoneIdx);
		}
		return candidate;
	}

	private static boolean isValidPortSuffix(String suffix) {
		if (suffix.length() < 2 || suffix.charAt(0) != ':') {
			return false;
		}
		for (int i = 1; i < suffix.length(); i++) {
			char ch = suffix.charAt(i);
			if (ch < '0' || ch > '9') {
				return false;
			}
		}
		return true;
	}

	private static boolean isValidIpv6Chars(String candidate) {
		int colonCount = 0;
		for (int i = 0; i < candidate.length(); i++) {
			char ch = candidate.charAt(i);
			if (ch == ':') {
				colonCount++;
			} else if (!((ch >= '0' && ch <= '9')
				|| (ch >= 'a' && ch <= 'f')
				|| (ch >= 'A' && ch <= 'F')
				|| ch == '.')) {
				return false;
			}
		}
		return colonCount >= 2;
	}

	private static boolean isValidIpv4Literal(String value) {
		String[] parts = value.split("\\.", -1);
		if (parts.length != 4) {
			return false;
		}
		for (String part : parts) {
			if (part.isEmpty() || part.length() > 3) {
				return false;
			}
			int octet = 0;
			for (int i = 0; i < part.length(); i++) {
				char ch = part.charAt(i);
				if (ch < '0' || ch > '9') {
					return false;
				}
				octet = octet * 10 + (ch - '0');
			}
			if (octet > 255) {
				return false;
			}
		}
		return true;
	}

	private static final class IpCidr {

		private final byte[] address;
		private final int fullBytes;
		private final int remainderMask;

		private IpCidr(byte[] address, int prefixLength) {
			this.address = address;
			this.fullBytes = prefixLength / Byte.SIZE;
			int remainingBits = prefixLength % Byte.SIZE;
			this.remainderMask = remainingBits == 0 ? 0 : (0xFF << (Byte.SIZE - remainingBits)) & 0xFF;
		}

		private static IpCidr parse(String value) {
			String[] parts = value.split("/", -1);
			if (parts.length < 1 || parts.length > 2 || parts[0].isBlank()) {
				throw new IllegalArgumentException("invalid IP CIDR: " + value);
			}
			byte[] addressBytes = parseLiteral(parts[0].trim()).getAddress();
			int maxPrefix = addressBytes.length * Byte.SIZE;
			int prefixLength;
			try {
				prefixLength = parts.length == 2 ? Integer.parseInt(parts[1].trim()) : maxPrefix;
			} catch (NumberFormatException exception) {
				throw new IllegalArgumentException("invalid IP CIDR prefix: " + value, exception);
			}
			if (prefixLength < 0 || prefixLength > maxPrefix) {
				throw new IllegalArgumentException("invalid IP CIDR prefix: " + value);
			}
			return new IpCidr(addressBytes, prefixLength);
		}

		private boolean contains(byte[] other) {
			if (other == null || address.length != other.length) {
				return false;
			}
			for (int i = 0; i < fullBytes; i++) {
				if (address[i] != other[i]) {
					return false;
				}
			}
			if (remainderMask == 0) {
				return true;
			}
			return (address[fullBytes] & remainderMask) == (other[fullBytes] & remainderMask);
		}
	}
}

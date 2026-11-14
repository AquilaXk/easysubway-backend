package com.easysubway.common.web;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Produces keyed HMAC-SHA256 rate-limit identities over masked IP subnets
 * (/24 for IPv4, /64 for IPv6) so raw client IP addresses are never retained in memory.
 */
public final class ClientIpHashResolver {

	public static final String UNKNOWN_KEY = "subnet-hmac:unknown";

	private static final String KEY_PREFIX = "subnet-hmac:";
	private static final String HMAC_ALGORITHM = "HmacSHA256";
	private static final SecureRandom EPHEMERAL_KEY_RNG = new SecureRandom();
	private static final byte[] KEY_DERIVATION_DOMAIN =
		"easysubway-client-ip-hmac-key-v1:".getBytes(StandardCharsets.UTF_8);
	private static final byte[] VALUE_DOMAIN =
		"easysubway-client-ip-subnet-v1:".getBytes(StandardCharsets.UTF_8);
	private static final int IPV4_BYTE_LENGTH = 4;
	private static final int IPV6_BYTE_LENGTH = 16;
	private static final int IPV4_PREFIX_BYTES = 3;
	private static final int IPV6_PREFIX_BYTES = 8;

	private final TrustedClientIpResolver ipResolver;
	private final SecretKeySpec hmacKeySpec;

	private ClientIpHashResolver(TrustedClientIpResolver ipResolver, SecretKeySpec hmacKeySpec) {
		this.ipResolver = ipResolver;
		this.hmacKeySpec = hmacKeySpec;
	}

	public static ClientIpHashResolver fromTrustedProxies(String trustedProxyCidrs, String hmacSecret) {
		return create(TrustedClientIpResolver.fromCidrs(trustedProxyCidrs), hmacSecret);
	}

	public static ClientIpHashResolver create(TrustedClientIpResolver ipResolver, String hmacSecret) {
		TrustedClientIpResolver resolver = ipResolver == null ? TrustedClientIpResolver.directOnly() : ipResolver;
		byte[] keyBytes = deriveHmacKey(hmacSecret);
		SecretKeySpec keySpec = new SecretKeySpec(keyBytes, HMAC_ALGORITHM);
		Arrays.fill(keyBytes, (byte) 0);
		return new ClientIpHashResolver(resolver, keySpec);
	}

	public String resolve(HttpServletRequest request) {
		return hashAddressBytes(ipResolver.resolveAddressBytes(request));
	}

	public String hashIpLiteral(String ipLiteral) {
		return hashAddressBytes(TrustedClientIpResolver.parseLiteralBytesOrNull(ipLiteral));
	}

	private String hashAddressBytes(byte[] addressBytes) {
		if (addressBytes == null) {
			return UNKNOWN_KEY;
		}
		int keepBytes;
		if (addressBytes.length == IPV4_BYTE_LENGTH) {
			keepBytes = IPV4_PREFIX_BYTES;
		} else if (addressBytes.length == IPV6_BYTE_LENGTH) {
			keepBytes = IPV6_PREFIX_BYTES;
		} else {
			return UNKNOWN_KEY;
		}
		byte[] masked = new byte[addressBytes.length];
		System.arraycopy(addressBytes, 0, masked, 0, keepBytes);
		byte[] digest = computeHmac((byte) masked.length, masked);
		return KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
	}

	private byte[] computeHmac(byte addressLengthTag, byte[] maskedSubnet) {
		try {
			Mac mac = Mac.getInstance(HMAC_ALGORITHM);
			mac.init(hmacKeySpec);
			mac.update(VALUE_DOMAIN);
			mac.update(addressLengthTag);
			return mac.doFinal(maskedSubnet);
		} catch (GeneralSecurityException exception) {
			throw new IllegalStateException("HmacSHA256 failure", exception);
		}
	}

	private static byte[] deriveHmacKey(String hmacSecret) {
		if (hmacSecret == null || hmacSecret.isBlank()) {
			byte[] ephemeral = new byte[32];
			EPHEMERAL_KEY_RNG.nextBytes(ephemeral);
			return ephemeral;
		}
		try {
			MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
			sha256.update(KEY_DERIVATION_DOMAIN);
			return sha256.digest(hmacSecret.getBytes(StandardCharsets.UTF_8));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 unavailable", exception);
		}
	}
}

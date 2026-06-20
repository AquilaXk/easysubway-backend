package com.easysubway.report.adapter.in.web;

import com.easysubway.report.domain.InvalidFacilityReportException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

interface FacilityReportUploadUrlSigner {

	SignedUploadUrl sign(FacilityReportUploadIntents.CreatedUploadIntent intent);

	record SignedUploadUrl(String uploadUrl, String uploadMethod, Map<String, String> uploadHeaders) {
	}
}

@Component
@Profile("!prod")
class LocalFacilityReportUploadUrlSigner implements FacilityReportUploadUrlSigner {

	@Override
	public SignedUploadUrl sign(FacilityReportUploadIntents.CreatedUploadIntent intent) {
		return new SignedUploadUrl("/api/v1/report-uploads/" + intent.uploadId(), "PUT", Map.of());
	}
}

@Component
@Profile("prod")
class ObjectStorageFacilityReportUploadUrlSigner implements FacilityReportUploadUrlSigner {

	private static final String HMAC_ALGORITHM = "HmacSHA256";
	private static final String SIGNING_ALGORITHM = "AWS4-HMAC-SHA256";
	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")
		.withZone(ZoneOffset.UTC);
	private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
		.withZone(ZoneOffset.UTC);

	private final String endpoint;
	private final String bucket;
	private final String accessKey;
	private final String secretKey;
	private final String region;

	ObjectStorageFacilityReportUploadUrlSigner(
		@Value("${easysubway.report.upload.object-storage-endpoint:}") String endpoint,
		@Value("${easysubway.report.upload.bucket:}") String bucket,
		@Value("${easysubway.report.upload.object-storage-access-key:}") String accessKey,
		@Value("${easysubway.report.upload.object-storage-secret-key:}") String secretKey,
		@Value("${easysubway.report.upload.object-storage-region:us-east-1}") String region
	) {
		this.endpoint = requireText(endpoint, "운영 object storage endpoint 설정이 필요합니다.");
		this.bucket = requireText(bucket, "운영 report upload bucket 설정이 필요합니다.");
		this.accessKey = requireText(accessKey, "운영 object storage access key 설정이 필요합니다.");
		this.secretKey = requireText(secretKey, "운영 object storage secret 설정이 필요합니다.");
		this.region = requireText(region, "운영 object storage region 설정이 필요합니다.");
	}

	@Override
	public SignedUploadUrl sign(FacilityReportUploadIntents.CreatedUploadIntent intent) {
		URI endpointUri = requireAbsoluteEndpoint(endpoint);
		String host = endpointUri.getRawAuthority().toLowerCase();
		String date = DATE_FORMAT.format(intent.issuedAt());
		String dateTime = DATE_TIME_FORMAT.format(intent.issuedAt());
		String credentialScope = "%s/%s/s3/aws4_request".formatted(date, region);
		Map<String, String> uploadHeaders = storageUploadHeaders(intent);
		String signedHeaders = signedHeaders(uploadHeaders);
		String canonicalUri = canonicalUri(bucket, intent.objectKey());
		long expiresSeconds = Duration.between(intent.issuedAt(), intent.expiresAt()).toSeconds();
		TreeMap<String, String> query = new TreeMap<>();
		query.put("X-Amz-Algorithm", SIGNING_ALGORITHM);
		query.put("X-Amz-Credential", "%s/%s".formatted(accessKey, credentialScope));
		query.put("X-Amz-Date", dateTime);
		query.put("X-Amz-Expires", String.valueOf(expiresSeconds));
		query.put("X-Amz-SignedHeaders", signedHeaders);
		String canonicalQuery = canonicalQuery(query);
		String canonicalRequest = String.join("\n",
			"PUT",
			canonicalUri,
			canonicalQuery,
			canonicalHeaders(host, uploadHeaders),
			signedHeaders,
			"UNSIGNED-PAYLOAD"
		);
		String stringToSign = String.join("\n",
			SIGNING_ALGORITHM,
			dateTime,
			credentialScope,
			sha256Hex(canonicalRequest)
		);
		query.put("X-Amz-Signature", signature(date, stringToSign));
		String uploadUrl = endpoint.replaceAll("/+$", "") + canonicalUri + "?" + canonicalQuery(query);
		return new SignedUploadUrl(uploadUrl, "PUT", uploadHeaders);
	}

	private Map<String, String> storageUploadHeaders(FacilityReportUploadIntents.CreatedUploadIntent intent) {
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("content-type", intent.contentType());
		headers.put("x-amz-meta-easysubway-sha256", intent.sha256());
		headers.put("x-amz-meta-easysubway-size", String.valueOf(intent.sizeBytes()));
		return headers;
	}

	private String signature(String date, String stringToSign) {
		byte[] dateKey = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date);
		byte[] regionKey = hmac(dateKey, region);
		byte[] serviceKey = hmac(regionKey, "s3");
		byte[] signingKey = hmac(serviceKey, "aws4_request");
		return HexFormat.of().formatHex(hmac(signingKey, stringToSign));
	}

	private byte[] hmac(byte[] key, String value) {
		try {
			Mac mac = Mac.getInstance(HMAC_ALGORITHM);
			mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
			return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
		} catch (java.security.GeneralSecurityException exception) {
			throw new IllegalStateException("HMAC-SHA256 is required", exception);
		}
	}

	private String sha256Hex(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (java.security.GeneralSecurityException exception) {
			throw new IllegalStateException("SHA-256 is required", exception);
		}
	}

	private static URI requireAbsoluteEndpoint(String endpoint) {
		URI uri = URI.create(endpoint);
		if (uri.getScheme() == null || uri.getRawAuthority() == null) {
			throw new InvalidFacilityReportException("운영 object storage endpoint 설정이 필요합니다.");
		}
		return uri;
	}

	private static String canonicalUri(String bucket, String objectKey) {
		return "/" + encodePathSegment(bucket) + "/" + encodePath(objectKey);
	}

	private static String canonicalQuery(Map<String, String> values) {
		return values.entrySet()
			.stream()
			.map(entry -> urlEncode(entry.getKey()) + "=" + urlEncode(entry.getValue()))
			.reduce((left, right) -> left + "&" + right)
			.orElse("");
	}

	private static String canonicalHeaders(String host, Map<String, String> uploadHeaders) {
		StringBuilder builder = new StringBuilder();
		builder.append("content-type:").append(uploadHeaders.getOrDefault("content-type", "")).append('\n');
		builder.append("host:").append(host).append('\n');
		uploadHeaders.entrySet()
			.stream()
			.filter(entry -> entry.getKey().startsWith("x-amz-"))
			.sorted(Map.Entry.comparingByKey())
			.forEach(entry -> builder.append(entry.getKey()).append(':').append(entry.getValue().trim()).append('\n'));
		return builder.toString();
	}

	private static String signedHeaders(Map<String, String> uploadHeaders) {
		return uploadHeaders.keySet()
			.stream()
			.filter(key -> key.startsWith("x-amz-"))
			.sorted()
			.reduce("content-type;host", (left, right) -> left + ";" + right);
	}

	private static String requireText(String value, String message) {
		if (value == null || value.isBlank()) {
			throw new InvalidFacilityReportException(message);
		}
		return value.trim();
	}

	private static String encodePath(String value) {
		return java.util.Arrays.stream(value.split("/", -1))
			.map(ObjectStorageFacilityReportUploadUrlSigner::encodePathSegment)
			.reduce((left, right) -> left + "/" + right)
			.orElse("");
	}

	private static String encodePathSegment(String value) {
		return urlEncode(value).replace("%2F", "/");
	}

	private static String urlEncode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8)
			.replace("+", "%20")
			.replace("*", "%2A")
			.replace("%7E", "~");
	}
}

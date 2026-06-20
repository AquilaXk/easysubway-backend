package com.easysubway.report.adapter.out.storage;

import com.easysubway.report.application.port.out.DeleteFacilityReportPhotoPort;
import com.easysubway.report.application.port.out.LoadFacilityReportPhotoPort;
import com.easysubway.report.application.port.out.LoadFacilityReportPhotoPort.LoadedFacilityReportPhoto;
import com.easysubway.report.application.port.out.StoreFacilityReportPhotoPort;
import com.easysubway.report.application.port.out.StoreFacilityReportUploadedPhotoPort;
import com.easysubway.report.application.port.out.StoreFacilityReportUploadedPhotoPort.StoreUploadedReportPhotoCommand;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
public class ObjectStorageFacilityReportPhotoStorage implements
	StoreFacilityReportPhotoPort,
	LoadFacilityReportPhotoPort,
	DeleteFacilityReportPhotoPort,
	StoreFacilityReportUploadedPhotoPort {

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
	private final HttpClient httpClient;
	private final Clock clock;

	@Autowired
	public ObjectStorageFacilityReportPhotoStorage(
		@Value("${easysubway.report.upload.object-storage-endpoint:}") String endpoint,
		@Value("${easysubway.report.upload.bucket:}") String bucket,
		@Value("${easysubway.report.upload.object-storage-access-key:}") String accessKey,
		@Value("${easysubway.report.upload.object-storage-secret-key:}") String secretKey,
		@Value("${easysubway.report.upload.object-storage-region:us-east-1}") String region
	) {
		this(endpoint, bucket, accessKey, secretKey, region, HttpClient.newHttpClient(), Clock.systemUTC());
	}

	ObjectStorageFacilityReportPhotoStorage(
		String endpoint,
		String bucket,
		String accessKey,
		String secretKey,
		String region,
		HttpClient httpClient,
		Clock clock
	) {
		this.endpoint = requireText(endpoint, "운영 object storage endpoint 설정이 필요합니다.");
		this.bucket = requireText(bucket, "운영 report upload bucket 설정이 필요합니다.");
		this.accessKey = requireText(accessKey, "운영 object storage access key 설정이 필요합니다.");
		this.secretKey = requireText(secretKey, "운영 object storage secret 설정이 필요합니다.");
		this.region = requireText(region, "운영 object storage region 설정이 필요합니다.");
		this.httpClient = httpClient;
		this.clock = clock;
	}

	@Override
	public StoredFacilityReportPhoto storeFacilityReportPhoto(StoreFacilityReportPhotoCommand command) {
		String extension = extensionFor(command.contentType());
		String objectKey = "facility-reports/%s/%s%s".formatted(command.reportId(), command.sha256(), extension);
		String thumbnailObjectKey = "facility-reports/%s/%s-thumbnail%s".formatted(
			command.reportId(),
			command.sha256(),
			extension
		);
		putObject(objectKey, command.contentType(), command.storedBytes());
		putObject(thumbnailObjectKey, command.contentType(), command.thumbnailBytes());
		return new StoredFacilityReportPhoto(objectKey, thumbnailObjectKey);
	}

	@Override
	public void storeUploadedReportPhoto(StoreUploadedReportPhotoCommand command) {
		putObject(command.objectKey(), contentTypeFor(command.objectKey()), command.bytes());
	}

	@Override
	public Optional<LoadedFacilityReportPhoto> loadFacilityReportPhoto(String objectKey) {
		if (objectKey == null || objectKey.isBlank()) {
			return Optional.empty();
		}
		HttpResponse<byte[]> response = send(signedRequest("GET", objectKey.trim(), null, null));
		if (response.statusCode() == 404) {
			return Optional.empty();
		}
		requireSuccess(response.statusCode(), "Failed to load facility report photo object");
		String contentType = response.headers()
			.firstValue("content-type")
			.map(value -> value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT))
			.filter(value -> !value.isBlank())
			.orElseGet(() -> contentTypeFor(objectKey));
		return Optional.of(new LoadedFacilityReportPhoto(contentType, response.body()));
	}

	@Override
	public void deleteFacilityReportPhoto(String objectKey) {
		if (objectKey == null || objectKey.isBlank()) {
			return;
		}
		HttpResponse<byte[]> response = send(signedRequest("DELETE", objectKey.trim(), null, null));
		if (response.statusCode() == 404) {
			return;
		}
		requireSuccess(response.statusCode(), "Failed to delete facility report photo object");
	}

	private void putObject(String objectKey, String contentType, byte[] bytes) {
		HttpResponse<byte[]> response = send(signedRequest("PUT", objectKey, contentType, bytes));
		requireSuccess(response.statusCode(), "Failed to store facility report photo object");
	}

	private HttpRequest signedRequest(String method, String objectKey, String contentType, byte[] body) {
		byte[] payload = body == null ? new byte[0] : body;
		URI endpointUri = requireAbsoluteEndpoint(endpoint);
		String host = endpointUri.getRawAuthority().toLowerCase(Locale.ROOT);
		Instant now = clock.instant();
		String date = DATE_FORMAT.format(now);
		String dateTime = DATE_TIME_FORMAT.format(now);
		String credentialScope = "%s/%s/s3/aws4_request".formatted(date, region);
		String payloadHash = sha256Hex(payload);
		TreeMap<String, String> headers = new TreeMap<>();
		headers.put("host", host);
		headers.put("x-amz-content-sha256", payloadHash);
		headers.put("x-amz-date", dateTime);
		if (contentType != null && !contentType.isBlank()) {
			headers.put("content-type", contentType.trim());
		}
		String canonicalUri = canonicalUri(bucket, objectKey);
		String canonicalRequest = String.join("\n",
			method,
			canonicalUri,
			"",
			canonicalHeaders(headers),
			signedHeaders(headers),
			payloadHash
		);
		String stringToSign = String.join("\n",
			SIGNING_ALGORITHM,
			dateTime,
			credentialScope,
			sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8))
		);
		String authorization = "%s Credential=%s/%s, SignedHeaders=%s, Signature=%s".formatted(
			SIGNING_ALGORITHM,
			accessKey,
			credentialScope,
			signedHeaders(headers),
			signature(date, stringToSign)
		);
		HttpRequest.Builder builder = HttpRequest.newBuilder()
			.uri(URI.create(endpoint.replaceAll("/+$", "") + canonicalUri))
			.header("Authorization", authorization)
			.header("x-amz-content-sha256", payloadHash)
			.header("x-amz-date", dateTime);
		if (contentType != null && !contentType.isBlank()) {
			builder.header("Content-Type", contentType.trim());
		}
		if ("PUT".equals(method)) {
			return builder.PUT(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
		}
		if ("DELETE".equals(method)) {
			return builder.DELETE().build();
		}
		return builder.GET().build();
	}

	private HttpResponse<byte[]> send(HttpRequest request) {
		try {
			return httpClient.send(request, BodyHandlers.ofByteArray());
		} catch (IOException exception) {
			throw new UncheckedIOException("Failed to call facility report object storage", exception);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while calling facility report object storage", exception);
		}
	}

	private void requireSuccess(int statusCode, String message) {
		if (statusCode < 200 || statusCode >= 300) {
			throw new IllegalStateException(message + ": HTTP " + statusCode);
		}
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

	private String sha256Hex(byte[] value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
		} catch (java.security.GeneralSecurityException exception) {
			throw new IllegalStateException("SHA-256 is required", exception);
		}
	}

	private static String canonicalHeaders(Map<String, String> headers) {
		StringBuilder builder = new StringBuilder();
		headers.forEach((key, value) -> builder.append(key).append(':').append(value.trim()).append('\n'));
		return builder.toString();
	}

	private static String signedHeaders(Map<String, String> headers) {
		return String.join(";", headers.keySet());
	}

	private static URI requireAbsoluteEndpoint(String endpoint) {
		URI uri = URI.create(endpoint);
		if (uri.getScheme() == null || uri.getRawAuthority() == null) {
			throw new IllegalStateException("운영 object storage endpoint 설정이 필요합니다.");
		}
		return uri;
	}

	private static String canonicalUri(String bucket, String objectKey) {
		return "/" + ObjectStorageUrlEncoding.encodePathSegment(bucket) + "/" + ObjectStorageUrlEncoding.encodePath(objectKey);
	}

	private static String requireText(String value, String message) {
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(message);
		}
		return value.trim();
	}

	private String extensionFor(String contentType) {
		return switch (contentType) {
			case "image/png" -> ".png";
			case "image/webp" -> ".webp";
			default -> ".jpg";
		};
	}

	private String contentTypeFor(String objectKey) {
		if (objectKey.endsWith(".png")) {
			return "image/png";
		}
		if (objectKey.endsWith(".webp")) {
			return "image/webp";
		}
		return "image/jpeg";
	}
}
